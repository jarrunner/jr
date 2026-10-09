package jarrunner.jr;

import static jarrunner.jr.N.*;

/** Feeds a file to a checksum a chunk at a time (PRP-36): the POSIX Sha256 and Crc32 are plain Java, where Windows
 *  calls BCrypt and ntdll. */
abstract class FileChunks {
    private static final int CHUNK = 1 << 20;

    abstract void update(byte[] b);

    /** Reads the whole file through update; false if it cannot be opened. */
    final boolean readFile(String path) {
        return memScoped(() -> {
            var f = PosixApi.fopen(path, "rb");
            if (f.toLong() == 0) {
                return false;
            }
            var buf = Buf.alloc(CHUNK);
            long n;
            while ((n = PosixApi.fread(buf.ptr(), 1, CHUNK, f)) > 0) {
                update(bytesOf(buf.ptr(), (int) n));
            }
            PosixApi.fclose(f);
            return true;
        });
    }
}
