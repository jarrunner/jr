package jarrunner.jr;

import static jarrunner.jr.N.*;

/** SHA-256 file checksum via BCrypt (Windows CNG) - verifies a downloaded JDK zip against the
 *  checksum Foojay reports before anything gets extracted. Mirrors javainstall.c's jiSha256File.
 *  BCrypt keeps a pointer to the hash object for the whole hash, so it must be native memory: a
 *  Java array known only by its Address can be freed by a GC while BCrypt is still using it. */
public final class Sha256 {
    private Sha256() {}

    private static final int READ_CHUNK = 65536;
    private static final String HEX = "0123456789abcdef";

    /** Returns the lowercase hex SHA-256 of the file, or null on any failure. */
    public static String ofFile(String path) {
        return memScoped(() -> {
            var algHandleVar = ptrVar();
            if (WinApi.bCryptOpenAlgorithmProvider(algHandleVar, wcstr(WinApi.BCRYPT_SHA256_ALGORITHM), NULL, 0) < 0) {
                return null;
            }
            var algHandle = ptrOf(algHandleVar);

            var prop = intVar();
            var result = intVar();
            WinApi.bCryptGetProperty(algHandle, WinApi.BCRYPT_OBJECT_LENGTH, prop, 4, result, 0);
            var hashObjLen = intOf(prop);
            // NOTE: the real constant (verified against llvm-mingw's bcrypt.h, not memory - see
            // guidelines.teavmcpp.md) is "HashDigestLength", NOT "HashLength" as MSDN examples'
            // BCRYPT_HASH_LENGTH name would suggest - passing the wrong string here silently
            // returns STATUS_INFO_LENGTH_MISMATCH rather than "property not found".
            WinApi.bCryptGetProperty(algHandle, WinApi.BCRYPT_HASH_LENGTH, prop, 4, result, 0);
            var hashLen = intOf(prop);

            if (hashObjLen <= 0 || hashLen <= 0) {
                WinApi.bCryptCloseAlgorithmProvider(algHandle, 0);
                return null;
            }

            var hashObj = alloc(hashObjLen);
            var hashHandleVar = ptrVar();
            if (WinApi.bCryptCreateHash(algHandle, hashHandleVar, hashObj, hashObjLen, NULL, 0, 0) < 0) {
                WinApi.bCryptCloseAlgorithmProvider(algHandle, 0);
                return null;
            }
            var hashHandle = ptrOf(hashHandleVar);

            var f = FileIo.open(path, "rb");
            if (f.toLong() == 0) {
                WinApi.bCryptDestroyHash(hashHandle);
                WinApi.bCryptCloseAlgorithmProvider(algHandle, 0);
                return null;
            }

            var buf = alloc(READ_CHUNK);
            while (true) {
                var n = WinApi.fread(buf, 1, READ_CHUNK, f);
                if (n <= 0) {
                    break;
                }
                WinApi.bCryptHashData(hashHandle, buf, (int) n, 0);
            }
            WinApi.fclose(f);

            var hashVal = alloc(hashLen);
            var finished = WinApi.bCryptFinishHash(hashHandle, hashVal, hashLen, 0) >= 0;

            WinApi.bCryptDestroyHash(hashHandle);
            WinApi.bCryptCloseAlgorithmProvider(algHandle, 0);

            if (!finished) {
                return null;
            }

            var sb = new StringBuilder(hashLen * 2);
            for (var x : bytesOf(hashVal, hashLen)) {
                var b = x & 0xFF;
                sb.append(HEX.charAt(b >> 4)).append(HEX.charAt(b & 0xF));
            }
            return sb.toString();
        });
    }
}
