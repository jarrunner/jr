package pocapp.jr;

import org.teavm.interop.Address;

/**
 * File size/mtime via GetFileAttributesExA - a WIN32_FILE_ATTRIBUTE_DATA struct, read as a raw
 * byte buffer rather than via org.teavm.interop.Structure (see guidelines.teavmcpp.md). Field
 * offsets come from WinOffsets, verified against the real compiler (see PRP-08).
 */
public final class FileInfo {
    private FileInfo() {}

    private static final int GET_FILEEX_INFO_STANDARD = 0;
    private static final int STRUCT_SIZE = WinOffsets.WIN32_FILE_ATTRIBUTE_DATA.SIZE;

    /** Raw 64-bit FILETIME value of the file's last-write-time, used only as a change-detection
     *  signal (not converted to a calendar date) - or -1 if the file cannot be read. */
    public static long lastWriteTimeRaw(String path) {
        var buf = new byte[STRUCT_SIZE];
        var addr = Address.ofData(buf);
        if (WinApi.getFileAttributesExA(Cstr.of(path), GET_FILEEX_INFO_STANDARD, addr) == 0) {
            return -1;
        }
        var ftOffset = WinOffsets.WIN32_FILE_ATTRIBUTE_DATA.ftLastWriteTime;
        var low = addr.add(ftOffset + WinOffsets.FILETIME.dwLowDateTime).getInt() & 0xFFFFFFFFL;
        var high = addr.add(ftOffset + WinOffsets.FILETIME.dwHighDateTime).getInt() & 0xFFFFFFFFL;
        return (high << 32) | low;
    }

    /** File size in bytes, or -1 if the file cannot be read. */
    public static long size(String path) {
        var buf = new byte[STRUCT_SIZE];
        var addr = Address.ofData(buf);
        if (WinApi.getFileAttributesExA(Cstr.of(path), GET_FILEEX_INFO_STANDARD, addr) == 0) {
            return -1;
        }
        var high = addr.add(WinOffsets.WIN32_FILE_ATTRIBUTE_DATA.nFileSizeHigh).getInt() & 0xFFFFFFFFL;
        var low = addr.add(WinOffsets.WIN32_FILE_ATTRIBUTE_DATA.nFileSizeLow).getInt() & 0xFFFFFFFFL;
        return (high << 32) | low;
    }
}
