package pocapp.jr;

import org.teavm.interop.Address;

import static pocapp.jr.N.*;

/**
 * An existing Authenticode signature becomes invalid the moment anything in the file changes, and
 * UpdateResource does not handle the certificate table correctly, so it is removed first: the
 * security directory entry is cleared and, when the certificate data sits at the end of the file
 * (it always does in a normally signed exe), it is cut off. Mirrors resedit.c's reStripSignature.
 *
 * Uses SetFilePointer (32-bit) rather than SetFilePointerEx: the latter takes its distance BY VALUE
 * (a LARGE_INTEGER union), which TeaVM cannot pass in either direction (a Structure is always a
 * void* in its generated C - see jextract-teavm/README.md "Structs by value"). Every seek here is to
 * a PE header field or the security directory, always well under 2 GB even for a large signed exe,
 * so the 32-bit distance never truncates in practice.
 */
public final class RePe {
    private RePe() {}

    /** Returns true if a signature was found and stripped, false if there was none to strip. */
    public static boolean stripSignature(String path) {
        var h = WinApi.createFileW(wcstr(path), WinApi.GENERIC_READ | WinApi.GENERIC_WRITE, 0, NULL,
                WinApi.OPEN_EXISTING, 0, NULL);
        if (h == WinApi.INVALID_HANDLE_VALUE) {
            throw new ReError("Cannot open for writing (error " + WinApi.getLastError() + ") - is it running?");
        }
        try {
            var got = intVar();

            var dos = alloc(WinOffsets.IMAGE_DOS_HEADER.SIZE);
            if (WinApi.readFile(h, dos, WinOffsets.IMAGE_DOS_HEADER.SIZE, got, NULL) == 0
                    || got.getInt() != WinOffsets.IMAGE_DOS_HEADER.SIZE
                    || WinOffsets.IMAGE_DOS_HEADER.e_magic(dos) != (short) WinApi.IMAGE_DOS_SIGNATURE) {
                throw new ReError("Not a Windows executable");
            }
            var lfanew = WinOffsets.IMAGE_DOS_HEADER.e_lfanew(dos);

            var sig = intVar();
            if (!seek(h, lfanew) || WinApi.readFile(h, sig, 4, got, NULL) == 0 || got.getInt() != 4
                    || sig.getInt() != WinApi.IMAGE_NT_SIGNATURE) {
                throw new ReError("Not a Windows executable");
            }

            // The optional header follows the 20-byte file header; its magic says 32 or 64 bit
            var magicBuf = intVar();
            if (!seek(h, lfanew + 4 + WinOffsets.IMAGE_FILE_HEADER.SIZE)
                    || WinApi.readFile(h, magicBuf, 2, got, NULL) == 0 || got.getInt() != 2) {
                throw new ReError("Not a Windows executable");
            }
            var magic = magicBuf.getShort();
            int dataDirectoryOffset;
            if (magic == (short) WinApi.IMAGE_NT_OPTIONAL_HDR64_MAGIC) {
                dataDirectoryOffset = WinOffsets.IMAGE_OPTIONAL_HEADER64.DataDirectory;
            } else if (magic == (short) WinApi.IMAGE_NT_OPTIONAL_HDR32_MAGIC) {
                dataDirectoryOffset = WinOffsets.IMAGE_OPTIONAL_HEADER32.DataDirectory;
            } else {
                throw new ReError("Not a Windows executable");
            }

            var dirPos = lfanew + 4 + WinOffsets.IMAGE_FILE_HEADER.SIZE + dataDirectoryOffset
                    + WinApi.IMAGE_DIRECTORY_ENTRY_SECURITY * WinOffsets.IMAGE_DATA_DIRECTORY.SIZE;
            var dir = alloc(WinOffsets.IMAGE_DATA_DIRECTORY.SIZE);
            if (!seek(h, dirPos) || WinApi.readFile(h, dir, WinOffsets.IMAGE_DATA_DIRECTORY.SIZE, got, NULL) == 0
                    || got.getInt() != WinOffsets.IMAGE_DATA_DIRECTORY.SIZE) {
                throw new ReError("Not a Windows executable");
            }

            var virtualAddress = WinOffsets.IMAGE_DATA_DIRECTORY.VirtualAddress(dir);
            var size = WinOffsets.IMAGE_DATA_DIRECTORY.Size(dir);
            if (virtualAddress == 0 || size == 0) {
                return false; // nothing to strip
            }

            var zero = alloc(WinOffsets.IMAGE_DATA_DIRECTORY.SIZE); // alloc() already zero-fills
            if (!seek(h, dirPos) || WinApi.writeFile(h, zero, WinOffsets.IMAGE_DATA_DIRECTORY.SIZE, got, NULL) == 0) {
                throw new ReError("Cannot remove the existing signature (error " + WinApi.getLastError() + ")");
            }
            // For this directory, VirtualAddress is a file offset, not an RVA
            var fileSize = longVar();
            if (WinApi.getFileSizeEx(h, fileSize) != 0
                    && (virtualAddress & 0xFFFFFFFFL) + (size & 0xFFFFFFFFL) >= fileSize.getLong()
                    && seek(h, virtualAddress)) {
                WinApi.setEndOfFile(h);
            }
            return true;
        } finally {
            WinApi.closeHandle(h);
        }
    }

    private static boolean seek(Address h, int pos) {
        return WinApi.setFilePointer(h, pos, NULL, WinApi.FILE_BEGIN) != -1;
    }
}
