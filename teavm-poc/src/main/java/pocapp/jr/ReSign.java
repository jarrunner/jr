package pocapp.jr;

import org.teavm.interop.Address;

import static pocapp.jr.N.*;

/**
 * (iii) Authenticode signing through mssign32!SignerSignEx2, which is what signtool.exe itself
 * calls. SHA-256 file digest; optional RFC 3161 timestamp. Mirrors resedit.c's reSign. Unlike the C
 * original (which loads mssign32.dll and looks up SignerSignEx2 with LoadLibrary/GetProcAddress,
 * because no SDK ships an import library for it), llvm-mingw DOES carry libmssign32.a, so this
 * binds as a plain @Import through bindings/mssign.h - see that header's own comment.
 */
public final class ReSign {
    private ReSign() {}

    private static final String OID_SHA256 = "2.16.840.1.101.3.4.2.1"; // szOID_NIST_sha256
    private static final int SIGNER_SUBJECT_FILE = 1;
    private static final int SIGNER_CERT_STORE = 2;
    private static final int SIGNER_CERT_POLICY_CHAIN_NO_ROOT = 8;
    private static final int SIGNER_NO_ATTR = 0;
    private static final int SIGNER_TIMESTAMP_RFC3161 = 2;

    public static void sign(String targetPath, ReStamp s, StringBuilder report) {
        var cert = NULL;
        var store = NULL;
        try {
            if (!s.signPfx.isEmpty()) {
                var pfx = FileIo.readAllNative(s.signPfx);
                if (pfx == null) {
                    throw new ReError("Cannot read certificate file: " + s.signPfx);
                }
                var env = Cstr.readEnv("JR_SIGN_PASSWORD");
                var password = wcstr(env == null ? "" : env);

                var blob = alloc(WinOffsets.CRYPT_DATA_BLOB.SIZE);
                WinOffsets.CRYPT_DATA_BLOB.cbData(blob, pfx.size());
                WinOffsets.CRYPT_DATA_BLOB.pbData(blob, pfx.data());
                // NO_PERSIST_KEY: the private key lives only in this process's memory, so signing
                // leaves nothing behind in the user's key store
                store = WinApi.pfxImportCertStore(blob, password,
                        WinApi.PKCS12_NO_PERSIST_KEY | WinApi.PKCS12_ALWAYS_CNG_KSP);
                wipe(password, env == null ? 0 : env.length());
                if (store.toLong() == 0) {
                    throw new ReError("Cannot open " + s.signPfx + " (error 0x" + hex8(WinApi.getLastError())
                            + ") - wrong password? It is read from JR_SIGN_PASSWORD");
                }
                var c = NULL;
                while (true) {
                    c = WinApi.certEnumCertificatesInStore(store, c);
                    if (c.toLong() == 0) {
                        break;
                    }
                    if (hasPrivateKey(c)) {
                        cert = c; // stays owned; freed in the finally block
                        break;
                    }
                }
                if (cert.toLong() == 0) {
                    throw new ReError(s.signPfx + " holds no certificate with a private key");
                }
            } else {
                var hash = parseThumbprint(s.signThumbprint);
                if (hash == null) {
                    throw new ReError("-Xjr:sign.thumbprint must be a 40-hex-digit SHA-1 thumbprint");
                }
                var hashBuf = alloc(hash.length);
                for (var i = 0; i < hash.length; i++) {
                    hashBuf.add(i).putByte(hash[i]);
                }
                var hashBlob = alloc(WinOffsets.CRYPT_DATA_BLOB.SIZE);
                WinOffsets.CRYPT_DATA_BLOB.cbData(hashBlob, hash.length);
                WinOffsets.CRYPT_DATA_BLOB.pbData(hashBlob, hashBuf);

                int[] locations = {WinApi.CERT_SYSTEM_STORE_CURRENT_USER, WinApi.CERT_SYSTEM_STORE_LOCAL_MACHINE};
                for (var loc : locations) {
                    if (cert.toLong() != 0) {
                        break;
                    }
                    if (store.toLong() != 0) {
                        WinApi.certCloseStore(store, 0);
                    }
                    store = WinApi.certOpenStore(WinApi.CERT_STORE_PROV_SYSTEM_W, 0, 0L,
                            loc | WinApi.CERT_STORE_READONLY_FLAG, wcstr("MY"));
                    if (store.toLong() != 0) {
                        cert = WinApi.certFindCertificateInStore(store,
                                WinApi.X509_ASN_ENCODING | WinApi.PKCS_7_ASN_ENCODING, 0, WinApi.CERT_FIND_HASH,
                                hashBlob, NULL);
                    }
                }
                if (cert.toLong() == 0) {
                    throw new ReError("No certificate with thumbprint " + s.signThumbprint + " in the Personal store");
                }
                if (!hasPrivateKey(cert)) {
                    throw new ReError("The certificate " + s.signThumbprint + " has no usable private key");
                }
            }

            var fileInfo = alloc(WinOffsets.SIGNER_FILE_INFO.SIZE);
            WinOffsets.SIGNER_FILE_INFO.cbSize(fileInfo, WinOffsets.SIGNER_FILE_INFO.SIZE);
            WinOffsets.SIGNER_FILE_INFO.pwszFileName(fileInfo, wcstr(targetPath));
            WinOffsets.SIGNER_FILE_INFO.hFile(fileInfo, NULL);

            var subject = alloc(WinOffsets.SIGNER_SUBJECT_INFO.SIZE);
            WinOffsets.SIGNER_SUBJECT_INFO.cbSize(subject, WinOffsets.SIGNER_SUBJECT_INFO.SIZE);
            WinOffsets.SIGNER_SUBJECT_INFO.pdwIndex(subject, intVar());
            WinOffsets.SIGNER_SUBJECT_INFO.dwSubjectChoice(subject, SIGNER_SUBJECT_FILE);
            WinOffsets.SIGNER_SUBJECT_INFO.pSignerFileInfo(subject, fileInfo);

            var storeInfo = alloc(WinOffsets.SIGNER_CERT_STORE_INFO.SIZE);
            WinOffsets.SIGNER_CERT_STORE_INFO.cbSize(storeInfo, WinOffsets.SIGNER_CERT_STORE_INFO.SIZE);
            WinOffsets.SIGNER_CERT_STORE_INFO.pSigningCert(storeInfo, cert);
            WinOffsets.SIGNER_CERT_STORE_INFO.dwCertPolicy(storeInfo, SIGNER_CERT_POLICY_CHAIN_NO_ROOT);
            WinOffsets.SIGNER_CERT_STORE_INFO.hCertStore(storeInfo, store);

            var signerCert = alloc(WinOffsets.SIGNER_CERT.SIZE);
            WinOffsets.SIGNER_CERT.cbSize(signerCert, WinOffsets.SIGNER_CERT.SIZE);
            WinOffsets.SIGNER_CERT.dwCertChoice(signerCert, SIGNER_CERT_STORE);
            WinOffsets.SIGNER_CERT.pCertStoreInfo(signerCert, storeInfo);
            WinOffsets.SIGNER_CERT.hwnd(signerCert, NULL);

            var sigInfo = alloc(WinOffsets.SIGNER_SIGNATURE_INFO.SIZE);
            WinOffsets.SIGNER_SIGNATURE_INFO.cbSize(sigInfo, WinOffsets.SIGNER_SIGNATURE_INFO.SIZE);
            WinOffsets.SIGNER_SIGNATURE_INFO.algidHash(sigInfo, WinApi.CALG_SHA_256);
            WinOffsets.SIGNER_SIGNATURE_INFO.dwAttrChoice(sigInfo, SIGNER_NO_ATTR);

            var hasTimestamp = !s.signTimestamp.isEmpty();
            var contextPtr = ptrVar();
            var hr = WinApi.signerSignEx2(0, subject, signerCert, sigInfo, NULL,
                    hasTimestamp ? SIGNER_TIMESTAMP_RFC3161 : 0,
                    hasTimestamp ? cstr(OID_SHA256) : NULL,
                    hasTimestamp ? wcstr(s.signTimestamp) : NULL,
                    NULL, NULL, contextPtr, NULL, NULL);
            if (hr < 0) {
                throw new ReError("Signing failed (HRESULT 0x" + hex8(hr) + ")"
                        + (hasTimestamp ? " - check the timestamp URL and the network" : ""));
            }
            var context = contextPtr.getAddress();
            if (context.toLong() != 0) {
                WinApi.signerFreeSignerContext(context);
            }

            var nameBuf = alloc(256 * 2);
            var subjectName = "";
            if (WinApi.certGetNameStringW(cert, WinApi.CERT_NAME_SIMPLE_DISPLAY_TYPE, 0, NULL, nameBuf, 256) != 0) {
                subjectName = wstring(nameBuf, 256);
            }
            report.append("Signed (SHA-256) by: ").append(subjectName).append("\n");
            if (hasTimestamp) {
                report.append("Timestamped by: ").append(s.signTimestamp).append("\n");
            }
        } finally {
            if (cert.toLong() != 0) {
                WinApi.certFreeCertificateContext(cert);
            }
            if (store.toLong() != 0) {
                WinApi.certCloseStore(store, 0);
            }
        }
    }

    private static boolean hasPrivateKey(Address cert) {
        // CACHE_FLAG: the key handle stays attached to the certificate, nothing to free
        return WinApi.cryptAcquireCertificatePrivateKey(cert,
                WinApi.CRYPT_ACQUIRE_CACHE_FLAG | WinApi.CRYPT_ACQUIRE_ALLOW_NCRYPT_KEY_FLAG
                        | WinApi.CRYPT_ACQUIRE_SILENT_FLAG,
                NULL, longVar(), intVar(), intVar()) != 0;
    }

    private static byte[] parseThumbprint(String text) {
        var out = new byte[20];
        var n = 0;
        var i = 0;
        while (i < text.length()) {
            var c = text.charAt(i);
            if (c == ' ' || c == ':') {
                i++;
                continue;
            }
            if (i + 1 >= text.length() || n >= out.length) {
                return null;
            }
            var hi = hexDigit(text.charAt(i));
            var lo = hexDigit(text.charAt(i + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[n++] = (byte) (hi * 16 + lo);
            i += 2;
        }
        return n == out.length ? out : null;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        var lower = (char) (c | 32);
        return lower >= 'a' && lower <= 'f' ? lower - 'a' + 10 : -1;
    }

    private static String hex8(int v) {
        var chars = new char[8];
        for (var i = 7; i >= 0; i--) {
            chars[i] = "0123456789ABCDEF".charAt(v & 0xF);
            v >>>= 4;
        }
        return new String(chars);
    }

    /** Overwrites a wide-string password's bytes in place - mirrors reSign's SecureZeroMemory on
     *  the password buffer, so the PFX password does not sit in memory any longer than needed. */
    private static void wipe(Address wideString, int chars) {
        for (var i = 0; i <= chars; i++) { // includes the NUL terminator
            wideString.add(i * 2).putChar((char) 0);
        }
    }
}
