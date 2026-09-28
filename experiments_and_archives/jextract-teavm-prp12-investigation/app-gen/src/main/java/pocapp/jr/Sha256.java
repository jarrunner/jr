package pocapp.jr;

import org.teavm.interop.Address;

/** SHA-256 file checksum via BCrypt (Windows CNG) - verifies a downloaded JDK zip against the
 *  checksum Foojay reports before anything gets extracted. Mirrors javainstall.c's jiSha256File. */
public final class Sha256 {
    private Sha256() {}

    private static final int READ_CHUNK = 65536;
    private static final String HEX = "0123456789abcdef";

    /** Returns the lowercase hex SHA-256 of the file, or null on any failure. */
    public static String ofFile(String path) {
        var algHandleBuf = new byte[8];
        var algHandleAddr = Address.ofData(algHandleBuf);
        if (WinApi.bCryptOpenAlgorithmProvider(algHandleAddr, Wstr.of(WinApi.BCRYPT_SHA256_ALGORITHM), Address.fromInt(0), 0) < 0) {
            return null;
        }
        var algHandle = algHandleAddr.getAddress();

        var propBuf = new byte[4];
        var propAddr = Address.ofData(propBuf);
        var resultBuf = new byte[4];
        var resultAddr = Address.ofData(resultBuf);

        WinApi.bCryptGetProperty(algHandle, Wstr.of(WinApi.BCRYPT_OBJECT_LENGTH), propAddr, 4, resultAddr, 0);
        var hashObjLen = propAddr.getInt();
        // NOTE: the real constant (verified against llvm-mingw's bcrypt.h, not memory - see
        // guidelines.teavmcpp.md) is "HashDigestLength", NOT "HashLength" as MSDN examples'
        // BCRYPT_HASH_LENGTH name would suggest - passing the wrong string here silently
        // returns STATUS_INFO_LENGTH_MISMATCH rather than "property not found".
        WinApi.bCryptGetProperty(algHandle, Wstr.of(WinApi.BCRYPT_HASH_LENGTH), propAddr, 4, resultAddr, 0);
        var hashLen = propAddr.getInt();

        if (hashObjLen <= 0 || hashLen <= 0) {
            WinApi.bCryptCloseAlgorithmProvider(algHandle, 0);
            return null;
        }

        var hashObj = new byte[hashObjLen];
        var hashObjAddr = Address.ofData(hashObj);
        var hashHandleBuf = new byte[8];
        var hashHandleAddr = Address.ofData(hashHandleBuf);
        if (WinApi.bCryptCreateHash(algHandle, hashHandleAddr, hashObjAddr, hashObjLen, Address.fromInt(0), 0, 0)
                < 0) {
            WinApi.bCryptCloseAlgorithmProvider(algHandle, 0);
            return null;
        }
        var hashHandle = hashHandleAddr.getAddress();

        var f = WinApi.fopen(Cstr.of(path), Cstr.of("rb"));
        if (f.toLong() == 0) {
            WinApi.bCryptDestroyHash(hashHandle);
            WinApi.bCryptCloseAlgorithmProvider(algHandle, 0);
            return null;
        }

        var buf = new byte[READ_CHUNK];
        var bufAddr = Address.ofData(buf);
        while (true) {
            var n = WinApi.fread(bufAddr, 1, buf.length, f);
            if (n <= 0) {
                break;
            }
            WinApi.bCryptHashData(hashHandle, bufAddr, (int) n, 0);
        }
        WinApi.fclose(f);

        var hashVal = new byte[hashLen];
        var hashValAddr = Address.ofData(hashVal);
        var finished = WinApi.bCryptFinishHash(hashHandle, hashValAddr, hashLen, 0) >= 0;

        WinApi.bCryptDestroyHash(hashHandle);
        WinApi.bCryptCloseAlgorithmProvider(algHandle, 0);

        if (!finished) {
            return null;
        }

        var sb = new StringBuilder(hashLen * 2);
        for (var i = 0; i < hashLen; i++) {
            var b = hashVal[i] & 0xFF;
            sb.append(HEX.charAt(b >> 4)).append(HEX.charAt(b & 0xF));
        }
        return sb.toString();
    }
}
