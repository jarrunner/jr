package littlejlib.jr;

import static littlejlib.jr.N.*;

/**
 * File size/mtime via GetFileAttributesExA - a WIN32_FILE_ATTRIBUTE_DATA struct, read as a raw
 * byte buffer rather than via org.teavm.interop.Structure (see guidelines.teavmcpp.md). Field
 * offsets come from WinOffsets, verified against the real compiler (see PRP-08).
 */
public final class FileInfo {
    private FileInfo() {}

    private static final int GET_FILEEX_INFO_STANDARD = WinApi.GET_FILEEX_INFO_STANDARD;
    private static final int STRUCT_SIZE = WinOffsets.WIN32_FILE_ATTRIBUTE_DATA.SIZE;

    /** Raw 64-bit FILETIME value of the file's last-write-time, used only as a change-detection
     *  signal (not converted to a calendar date) - or -1 if the file cannot be read. */
    public static long lastWriteTimeRaw(String path) {
        var addr = alloc(STRUCT_SIZE);
        if (WinApi.getFileAttributesExA(cstr(path), GET_FILEEX_INFO_STANDARD, addr) == 0) {
            return -1;
        }
        var ftOffset = WinOffsets.WIN32_FILE_ATTRIBUTE_DATA.ftLastWriteTime;
        var low = WinOffsets.FILETIME.dwLowDateTime(addr.add(ftOffset)) & 0xFFFFFFFFL;
        var high = WinOffsets.FILETIME.dwHighDateTime(addr.add(ftOffset)) & 0xFFFFFFFFL;
        return (high << 32) | low;
    }

    /** File size in bytes, or -1 if the file cannot be read. */
    public static long size(String path) {
        var addr = alloc(STRUCT_SIZE);
        if (WinApi.getFileAttributesExA(cstr(path), GET_FILEEX_INFO_STANDARD, addr) == 0) {
            return -1;
        }
        var high = WinOffsets.WIN32_FILE_ATTRIBUTE_DATA.nFileSizeHigh(addr) & 0xFFFFFFFFL;
        var low = WinOffsets.WIN32_FILE_ATTRIBUTE_DATA.nFileSizeLow(addr) & 0xFFFFFFFFL;
        return (high << 32) | low;
    }
}
