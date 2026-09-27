/* mssign32.dll has no SDK header - these are the documented structs and prototypes from MSDN's
 * "SignerSignEx2 function" page, the same call signtool.exe itself makes. resedit.c (the C
 * launcher) hand-declares the same shapes locally for the same reason; this header exists so
 * jextract-teavm can parse them too, and verify their offsets against this exact target rather
 * than anyone typing them by hand a second time - see prp/20-prp-teavm_port_catches_up_with_the_c_
 * launcher.md item 6. llvm-mingw ships libmssign32.a, so these bind as plain @Import functions. */
#ifndef JR_MSSIGN_H
#define JR_MSSIGN_H

#include <windows.h>
#include <wincrypt.h>

typedef struct {
    DWORD cbSize;
    LPCWSTR pwszFileName;
    HANDLE hFile;
} SIGNER_FILE_INFO;

typedef struct {
    DWORD cbSize;
    DWORD *pdwIndex;
    DWORD dwSubjectChoice;
    SIGNER_FILE_INFO *pSignerFileInfo;
} SIGNER_SUBJECT_INFO;

typedef struct {
    DWORD cbSize;
    PCCERT_CONTEXT pSigningCert;
    DWORD dwCertPolicy;
    HCERTSTORE hCertStore;
} SIGNER_CERT_STORE_INFO;

typedef struct {
    DWORD cbSize;
    DWORD dwCertChoice;
    SIGNER_CERT_STORE_INFO *pCertStoreInfo;
    HWND hwnd;
} SIGNER_CERT;

typedef struct {
    DWORD cbSize;
    ALG_ID algidHash;
    DWORD dwAttrChoice;
    void *pAttrAuthcode;
    PCRYPT_ATTRIBUTES psAuthenticated;
    PCRYPT_ATTRIBUTES psUnauthenticated;
} SIGNER_SIGNATURE_INFO;

typedef struct {
    DWORD cbSize;
    DWORD cbBlob;
    BYTE *pbBlob;
} SIGNER_CONTEXT;

HRESULT WINAPI SignerSignEx2(DWORD dwFlags, SIGNER_SUBJECT_INFO *pSubjectInfo, SIGNER_CERT *pSignerCert,
    SIGNER_SIGNATURE_INFO *pSignatureInfo, void *pProviderInfo, DWORD dwTimestampFlags,
    PCSTR pszTimestampAlgorithmOid, PCWSTR pwszTimestampURL, PCRYPT_ATTRIBUTES psRequest,
    void *pSipData, SIGNER_CONTEXT **ppSignerContext, void *pCryptoPolicy, void *pReserved);

HRESULT WINAPI SignerFreeSignerContext(SIGNER_CONTEXT *pSignerContext);

#endif
