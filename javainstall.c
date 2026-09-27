// Java auto-install: "Java not found" falls back to downloading a matching
// Eclipse Temurin JDK instead of just erroring out. See prp/09-prp-java_auto_install.md.
//
// Cache layout deliberately matches jbang's own (%USERPROFILE%\.jbang\cache\jdks\<major>),
// which is nothing more than a flattened JDK home - jbang has no separate registry file,
// it just scans that folder - so jr and jbang can share downloads with zero bookkeeping.
//
// Network/crypto libs are pulled in via #pragma comment(lib,...) below rather than by
// editing build-win.bat's link line - they are OS-provided DLLs (winhttp.dll, bcrypt.dll,
// comctl32.dll), not redistributable-requiring static libs, so this does not reopen the
// PRP-06 size/redistributable work. build-win.bat only needs this file added to the
// compile command.

#include <windows.h>
#include <winhttp.h>
#include <bcrypt.h>
#include <commctrl.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "javainstall.h"

#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "comctl32.lib")

#define JI_DEFAULT_VERSION 25

// ---------------------------------------------------------------------------
// Small helpers: file/dir existence, recursive mkdir/rmdir, single-subdir scan
// ---------------------------------------------------------------------------

static int jiFileExists(const char* path) {
    DWORD attr = GetFileAttributesA(path);
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

// Creates every missing path segment. Ignores CreateDirectoryA failures for
// segments that already exist (including the drive-letter prefix).
static void jiMkdirRecursive(const char* path) {
    char tmp[MAX_PATH];
    char* p;
    size_t len;

    strncpy(tmp, path, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = '\0';
    len = strlen(tmp);
    if (len > 0 && (tmp[len - 1] == '\\' || tmp[len - 1] == '/')) {
        tmp[len - 1] = '\0';
    }

    for (p = tmp + 3; *p; p++) {  // +3 skips "C:\" - always an absolute drive path here
        if (*p == '\\' || *p == '/') {
            *p = '\0';
            CreateDirectoryA(tmp, NULL);
            *p = '\\';
        }
    }
    CreateDirectoryA(tmp, NULL);
}

static void jiRemoveDirTree(const char* path) {
    WIN32_FIND_DATAA fd;
    char pattern[MAX_PATH];
    char child[MAX_PATH];
    HANDLE h;
    DWORD attr = GetFileAttributesA(path);

    if (attr == INVALID_FILE_ATTRIBUTES) return;
    if (!(attr & FILE_ATTRIBUTE_DIRECTORY)) {
        DeleteFileA(path);
        return;
    }

    snprintf(pattern, sizeof(pattern), "%s\\*", path);
    h = FindFirstFileA(pattern, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            if (strcmp(fd.cFileName, ".") == 0 || strcmp(fd.cFileName, "..") == 0) continue;
            snprintf(child, sizeof(child), "%s\\%s", path, fd.cFileName);
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
                jiRemoveDirTree(child);
            } else {
                SetFileAttributesA(child, FILE_ATTRIBUTE_NORMAL);
                DeleteFileA(child);
            }
        } while (FindNextFileA(h, &fd));
        FindClose(h);
    }
    RemoveDirectoryA(path);
}

// Adoptium zips contain exactly one top-level folder (e.g. "jdk-21.0.12.1+1").
// Finds it so its contents can be promoted up to the jbang-style flattened home.
static int jiFindSingleSubdir(const char* parentDir, char* outName, size_t outSize) {
    WIN32_FIND_DATAA fd;
    char pattern[MAX_PATH];
    HANDLE h;
    int found = 0;

    snprintf(pattern, sizeof(pattern), "%s\\*", parentDir);
    h = FindFirstFileA(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) return 0;

    do {
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) &&
            strcmp(fd.cFileName, ".") != 0 && strcmp(fd.cFileName, "..") != 0) {
            strncpy(outName, fd.cFileName, outSize - 1);
            outName[outSize - 1] = '\0';
            found = 1;
            break;
        }
    } while (FindNextFileA(h, &fd));

    FindClose(h);
    return found;
}

static int jiMoveDirectory(const char* src, const char* dst) {
    jiRemoveDirTree(dst);  // clear any empty leftover from a previous partial attempt
    return MoveFileExA(src, dst, MOVEFILE_COPY_ALLOWED) != 0;
}

// ---------------------------------------------------------------------------
// Tiny targeted JSON field extraction - not a general parser. The two Foojay
// responses used here have a known, simple shape, so a marker-string scan is
// enough (matches this codebase's existing style - parseConfigFile() in
// launcher.c is the same kind of hand-rolled, specific-not-generic parsing).
// ---------------------------------------------------------------------------

static int jiJsonExtractString(const char* json, const char* keyMarker, char* out, size_t outSize) {
    const char* p = strstr(json, keyMarker);
    const char* end;
    size_t len;

    if (!p) { out[0] = '\0'; return 0; }
    p += strlen(keyMarker);
    end = p;
    while (*end && *end != '"') end++;

    len = (size_t)(end - p);
    if (len >= outSize) len = outSize - 1;
    memcpy(out, p, len);
    out[len] = '\0';
    return len > 0;
}

// ---------------------------------------------------------------------------
// WinHTTP - shared GET setup, then two consumers: to-memory (small JSON calls)
// and to-file (the actual JDK zip, with progress reporting).
// ---------------------------------------------------------------------------

static int jiHttpOpenGet(const char* url, HINTERNET* outSession, HINTERNET* outConnect,
                          HINTERNET* outRequest, DWORD* outContentLength) {
    WCHAR wUrl[2048];
    WCHAR hostName[256];
    WCHAR urlPath[2048];
    URL_COMPONENTS uc;
    BOOL isHttps;
    INTERNET_PORT port;
    HINTERNET hSession = NULL, hConnect = NULL, hRequest = NULL;
    DWORD contentLen = 0;
    DWORD contentLenSize = sizeof(contentLen);

    memset(&uc, 0, sizeof(uc));
    memset(hostName, 0, sizeof(hostName));
    memset(urlPath, 0, sizeof(urlPath));
    MultiByteToWideChar(CP_UTF8, 0, url, -1, wUrl, 2048);

    uc.dwStructSize = sizeof(uc);
    uc.lpszHostName = hostName;
    uc.dwHostNameLength = 256;
    uc.lpszUrlPath = urlPath;
    uc.dwUrlPathLength = 2048;

    if (!WinHttpCrackUrl(wUrl, 0, 0, &uc)) return 0;
    isHttps = (uc.nScheme == INTERNET_SCHEME_HTTPS);
    port = uc.nPort;

    hSession = WinHttpOpen(L"jr-launcher/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                            WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!hSession) return 0;

    hConnect = WinHttpConnect(hSession, hostName, port, 0);
    if (!hConnect) { WinHttpCloseHandle(hSession); return 0; }

    hRequest = WinHttpOpenRequest(hConnect, L"GET", urlPath, NULL, WINHTTP_NO_REFERER,
                                   WINHTTP_DEFAULT_ACCEPT_TYPES, isHttps ? WINHTTP_FLAG_SECURE : 0);
    if (!hRequest) { WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession); return 0; }

    if (!WinHttpSendRequest(hRequest, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
        !WinHttpReceiveResponse(hRequest, NULL)) {
        WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession);
        return 0;
    }

    WinHttpQueryHeaders(hRequest, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &contentLen, &contentLenSize, WINHTTP_NO_HEADER_INDEX);

    *outSession = hSession;
    *outConnect = hConnect;
    *outRequest = hRequest;
    if (outContentLength) *outContentLength = contentLen;
    return 1;
}

static void jiHttpCloseAll(HINTERNET hSession, HINTERNET hConnect, HINTERNET hRequest) {
    if (hRequest) WinHttpCloseHandle(hRequest);
    if (hConnect) WinHttpCloseHandle(hConnect);
    if (hSession) WinHttpCloseHandle(hSession);
}

static int jiHttpGetToBuffer(const char* url, char* buffer, size_t bufSize) {
    HINTERNET hSession, hConnect, hRequest;
    DWORD contentLen;
    size_t total = 0;
    int ok = 0;

    if (!jiHttpOpenGet(url, &hSession, &hConnect, &hRequest, &contentLen)) return 0;

    for (;;) {
        DWORD bytesAvailable = 0;
        DWORD bytesRead = 0;
        DWORD toRead;

        if (!WinHttpQueryDataAvailable(hRequest, &bytesAvailable)) break;
        if (bytesAvailable == 0) { ok = 1; break; }

        toRead = bytesAvailable;
        if (total + toRead >= bufSize) toRead = (DWORD)(bufSize - total - 1);
        if (toRead == 0) { ok = 1; break; }

        if (!WinHttpReadData(hRequest, buffer + total, toRead, &bytesRead)) break;
        if (bytesRead == 0) { ok = 1; break; }
        total += bytesRead;
    }
    buffer[total < bufSize ? total : bufSize - 1] = '\0';

    jiHttpCloseAll(hSession, hConnect, hRequest);
    return ok;
}

// ---------------------------------------------------------------------------
// Progress bar: text bar in console mode, a small native progress-bar window
// in GUI mode. Driven off the real Content-Length, not a guess.
// ---------------------------------------------------------------------------

typedef struct {
    BOOL guiMode;
    BOOL hasConsole;
    long long lastShownPercent;
    HWND hwndWindow;
    HWND hwndBar;
} JiProgress;

static LRESULT CALLBACK jiProgressWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == WM_CLOSE) return 0;  // no cancel support this pass - ignore user close attempts
    return DefWindowProcA(hwnd, msg, wp, lp);
}

static void jiProgressInit(JiProgress* ps, BOOL guiMode, BOOL hasConsole, const char* label) {
    memset(ps, 0, sizeof(*ps));
    ps->guiMode = guiMode;
    ps->hasConsole = hasConsole;
    ps->lastShownPercent = -1;

    if (guiMode) {
        WNDCLASSA wc;
        INITCOMMONCONTROLSEX icc;
        HINSTANCE hInst = GetModuleHandleA(NULL);

        icc.dwSize = sizeof(icc);
        icc.dwICC = ICC_PROGRESS_CLASS;
        InitCommonControlsEx(&icc);

        memset(&wc, 0, sizeof(wc));
        wc.lpfnWndProc = jiProgressWndProc;
        wc.hInstance = hInst;
        wc.lpszClassName = "JrJavaInstallProgress";
        wc.hCursor = LoadCursorA(NULL, (LPCSTR)IDC_ARROW);
        wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
        RegisterClassA(&wc);  // harmless if already registered from a prior call

        ps->hwndWindow = CreateWindowExA(WS_EX_TOPMOST, "JrJavaInstallProgress",
            "Java Runner - Installing Java", WS_OVERLAPPED | WS_CAPTION,
            CW_USEDEFAULT, CW_USEDEFAULT, 420, 120, NULL, NULL, hInst, NULL);

        if (ps->hwndWindow) {
            CreateWindowExA(0, "STATIC", label, WS_CHILD | WS_VISIBLE,
                10, 10, 390, 20, ps->hwndWindow, NULL, hInst, NULL);
            ps->hwndBar = CreateWindowExA(0, PROGRESS_CLASSA, NULL, WS_CHILD | WS_VISIBLE,
                10, 40, 390, 24, ps->hwndWindow, NULL, hInst, NULL);
            SendMessageA(ps->hwndBar, PBM_SETRANGE, 0, MAKELPARAM(0, 100));
            ShowWindow(ps->hwndWindow, SW_SHOW);
            UpdateWindow(ps->hwndWindow);
        }
    } else if (hasConsole) {
        printf("%s\n", label);
    }
}

static void jiPumpMessages(void) {
    MSG msg;
    while (PeekMessageA(&msg, NULL, 0, 0, PM_REMOVE)) {
        TranslateMessage(&msg);
        DispatchMessageA(&msg);
    }
}

static void jiProgressUpdate(JiProgress* ps, long long downloaded, long long total) {
    int percent;
    if (total <= 0) {
        if (ps->guiMode) jiPumpMessages();
        return;
    }
    percent = (int)((downloaded * 100) / total);

    if (percent == ps->lastShownPercent) {
        if (ps->guiMode) jiPumpMessages();
        return;
    }
    ps->lastShownPercent = percent;

    if (ps->guiMode) {
        if (ps->hwndBar) SendMessageA(ps->hwndBar, PBM_SETPOS, (WPARAM)percent, 0);
        jiPumpMessages();
    } else if (ps->hasConsole) {
        int barWidth = 30;
        int filled = (percent * barWidth) / 100;
        int i;
        printf("\r[");
        for (i = 0; i < barWidth; i++) putchar(i < filled ? '#' : '-');
        printf("] %3d%% (%lld MB / %lld MB)", percent,
               downloaded / (1024 * 1024), total / (1024 * 1024));
        fflush(stdout);
    }
}

static void jiProgressFinish(JiProgress* ps) {
    if (ps->guiMode) {
        if (ps->hwndWindow) DestroyWindow(ps->hwndWindow);
    } else if (ps->hasConsole) {
        printf("\n");
    }
}

static int jiHttpDownloadToFile(const char* url, const char* outPath, JiProgress* ps) {
    HINTERNET hSession, hConnect, hRequest;
    DWORD contentLen = 0;
    long long totalBytes;
    long long totalRead = 0;
    FILE* out;
    int ok = 0;
    char buf[65536];

    if (!jiHttpOpenGet(url, &hSession, &hConnect, &hRequest, &contentLen)) return 0;
    totalBytes = contentLen;

    out = fopen(outPath, "wb");
    if (!out) { jiHttpCloseAll(hSession, hConnect, hRequest); return 0; }

    for (;;) {
        DWORD bytesAvailable = 0;
        DWORD bytesRead = 0;
        DWORD toRead;

        if (!WinHttpQueryDataAvailable(hRequest, &bytesAvailable)) break;
        if (bytesAvailable == 0) { ok = 1; break; }

        toRead = bytesAvailable < sizeof(buf) ? bytesAvailable : (DWORD)sizeof(buf);
        if (!WinHttpReadData(hRequest, buf, toRead, &bytesRead)) break;
        if (bytesRead == 0) { ok = 1; break; }

        fwrite(buf, 1, bytesRead, out);
        totalRead += bytesRead;
        if (ps) jiProgressUpdate(ps, totalRead, totalBytes);
    }

    fclose(out);
    jiHttpCloseAll(hSession, hConnect, hRequest);
    return ok;
}

// ---------------------------------------------------------------------------
// SHA-256 via BCrypt (CNG) - verifies the download against Foojay's reported
// checksum before anything gets extracted.
// ---------------------------------------------------------------------------

static int jiSha256File(const char* path, char* outHex, size_t outHexSize) {
    BCRYPT_ALG_HANDLE hAlg = NULL;
    BCRYPT_HASH_HANDLE hHash = NULL;
    DWORD hashObjLen = 0, cbData = 0, hashLen = 0;
    PBYTE hashObj = NULL;
    PBYTE hashVal = NULL;
    FILE* f = NULL;
    int ok = 0;

    if (BCryptOpenAlgorithmProvider(&hAlg, BCRYPT_SHA256_ALGORITHM, NULL, 0) < 0) return 0;
    if (BCryptGetProperty(hAlg, BCRYPT_OBJECT_LENGTH, (PBYTE)&hashObjLen, sizeof(DWORD), &cbData, 0) < 0) goto cleanup;
    hashObj = (PBYTE)malloc(hashObjLen);
    if (!hashObj) goto cleanup;
    if (BCryptGetProperty(hAlg, BCRYPT_HASH_LENGTH, (PBYTE)&hashLen, sizeof(DWORD), &cbData, 0) < 0) goto cleanup;
    hashVal = (PBYTE)malloc(hashLen);
    if (!hashVal) goto cleanup;
    if (hashLen * 2 + 1 > outHexSize) goto cleanup;

    if (BCryptCreateHash(hAlg, &hHash, hashObj, hashObjLen, NULL, 0, 0) < 0) goto cleanup;

    f = fopen(path, "rb");
    if (!f) goto cleanup;
    {
        unsigned char buf[65536];
        size_t n;
        while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
            BCryptHashData(hHash, buf, (ULONG)n, 0);
        }
    }
    fclose(f);
    f = NULL;

    if (BCryptFinishHash(hHash, hashVal, hashLen, 0) < 0) goto cleanup;

    {
        static const char hex[] = "0123456789abcdef";
        DWORD i;
        for (i = 0; i < hashLen; i++) {
            outHex[i * 2] = hex[(hashVal[i] >> 4) & 0xF];
            outHex[i * 2 + 1] = hex[hashVal[i] & 0xF];
        }
        outHex[hashLen * 2] = '\0';
    }
    ok = 1;

cleanup:
    if (f) fclose(f);
    if (hHash) BCryptDestroyHash(hHash);
    if (hAlg) BCryptCloseAlgorithmProvider(hAlg, 0);
    if (hashObj) free(hashObj);
    if (hashVal) free(hashVal);
    return ok;
}

// ---------------------------------------------------------------------------
// Extraction via the tar.exe already bundled with Windows (10 1803+), which
// handles .zip through libarchive - no archive-parsing code of our own needed.
// ---------------------------------------------------------------------------

static int jiExtractZip(const char* zipPath, const char* destDir) {
    char sysDir[MAX_PATH];
    char tarPath[MAX_PATH];
    char cmd[MAX_PATH * 2 + 64];
    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    DWORD exitCode = 1;

    GetSystemDirectoryA(sysDir, sizeof(sysDir));
    snprintf(tarPath, sizeof(tarPath), "%s\\tar.exe", sysDir);
    snprintf(cmd, sizeof(cmd), "\"%s\" -xf \"%s\" -C \"%s\"", tarPath, zipPath, destDir);

    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);

    if (!CreateProcessA(NULL, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        return 0;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return exitCode == 0;
}

// ---------------------------------------------------------------------------
// Confirmation prompt - console Y/n or a GUI MessageBox. Skipped entirely when
// the caller already resolved assumeYes (--yes flag / JR_ASSUME_YES env var).
// ---------------------------------------------------------------------------

static int jiConfirmYesNo(BOOL hasConsole, const char* message) {
    if (hasConsole) {
        char line[16];
        printf("\n%s\n[Y/n] ", message);
        fflush(stdout);
        if (!fgets(line, sizeof(line), stdin)) return 0;
        return line[0] == '\0' || line[0] == '\n' || line[0] == 'y' || line[0] == 'Y';
    } else {
        return MessageBoxA(NULL, message, "Java Not Found - Auto Install", MB_YESNO | MB_ICONQUESTION) == IDYES;
    }
}

// ---------------------------------------------------------------------------
// Orchestration
// ---------------------------------------------------------------------------

static int jiGetDefaultCacheRoot(char* outPath, size_t outSize) {
    const char* userProfile = getenv("USERPROFILE");
    if (!userProfile) return 0;
    snprintf(outPath, outSize, "%s\\.jbang\\cache\\jdks", userProfile);
    return 1;
}

// For "NN+": the newest <cacheRoot>\<N> with N >= minVersion and a bin\java.exe.
// Only all-digit folder names count, which is how jbang names its own.
static int jiFindCachedAtLeast(const char* cacheRoot, int minVersion, char* outDir, size_t outDirSize) {
    char pattern[MAX_PATH];
    char candidate[MAX_PATH];
    WIN32_FIND_DATAA fd;
    HANDLE h;
    int best = 0;

    snprintf(pattern, sizeof(pattern), "%s\\*", cacheRoot);
    h = FindFirstFileA(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) return 0;

    do {
        const char* p = fd.cFileName;
        int n;
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) || !*p) continue;
        while (*p >= '0' && *p <= '9') p++;
        if (*p) continue;
        n = atoi(fd.cFileName);
        if (n < minVersion || n <= best) continue;
        snprintf(candidate, sizeof(candidate), "%s\\%s\\bin\\java.exe", cacheRoot, fd.cFileName);
        if (jiFileExists(candidate)) best = n;
    } while (FindNextFileA(h, &fd));
    FindClose(h);

    if (best == 0) return 0;
    snprintf(outDir, outDirSize, "%s\\%d", cacheRoot, best);
    return 1;
}

int autoInstallJava(int majorVersion, int atLeast, const char* reason,
                     BOOL hasConsole, BOOL guiMode, BOOL assumeYes,
                     const char* cacheRootOverride, char* outJdkHome, size_t outJdkHomeSize) {
    char cacheRoot[MAX_PATH];
    char targetDir[MAX_PATH];
    char javaExeCheck[MAX_PATH];
    char sysTempPath[MAX_PATH];
    char tempDir[MAX_PATH];
    char zipPath[MAX_PATH];
    char extractDir[MAX_PATH];
    char apiUrl[1024];
    char apiResponse[16384];
    char pkgId[128];
    char idUrl[512];
    char idResponse[8192];
    char downloadUrl[2048];
    char expectedChecksum[128];
    char actualChecksum[128];
    char filename[256];
    char subdirName[MAX_PATH];
    char extractedJdkPath[MAX_PATH];

    if (majorVersion <= 0) majorVersion = JI_DEFAULT_VERSION;

    if (cacheRootOverride && cacheRootOverride[0]) {
        strncpy(cacheRoot, cacheRootOverride, sizeof(cacheRoot) - 1);
        cacheRoot[sizeof(cacheRoot) - 1] = '\0';
    } else if (!jiGetDefaultCacheRoot(cacheRoot, sizeof(cacheRoot))) {
        return 0;
    }

    snprintf(targetDir, sizeof(targetDir), "%s\\%d", cacheRoot, majorVersion);
    snprintf(javaExeCheck, sizeof(javaExeCheck), "%s\\bin\\java.exe", targetDir);

    if (jiFileExists(javaExeCheck)) {
        // Already installed (by jbang, or a previous jr auto-install) - no network at all.
        strncpy(outJdkHome, targetDir, outJdkHomeSize - 1);
        outJdkHome[outJdkHomeSize - 1] = '\0';
        return 1;
    }

    if (atLeast && jiFindCachedAtLeast(cacheRoot, majorVersion, outJdkHome, outJdkHomeSize)) {
        return 1;
    }

    if (!assumeYes) {
        char prompt[1024];
        snprintf(prompt, sizeof(prompt),
                 "%s\n\nDownload and install Eclipse Temurin JDK %d (~200 MB) to:\n%s\n\nProceed?",
                 reason ? reason : "Java was not found.", majorVersion, targetDir);
        if (!jiConfirmYesNo(hasConsole, prompt)) {
            return 0;
        }
    }

    // Step 1: resolve the package id for this major version (Windows x64 Temurin GA build)
    snprintf(apiUrl, sizeof(apiUrl),
             "https://api.foojay.io/disco/v3.0/packages?distro=temurin&javafx_bundled=false&libc_type=c_std_lib"
             "&directly_downloadable=true&archive_type=zip&operating_system=windows&package_type=jdk"
             "&release_status=ga&architecture=x64&latest=available&version=%d", majorVersion);
    if (!jiHttpGetToBuffer(apiUrl, apiResponse, sizeof(apiResponse))) return 0;
    if (!jiJsonExtractString(apiResponse, "\"id\":\"", pkgId, sizeof(pkgId))) return 0;

    // Step 2: resolve the direct download URL + expected checksum for that package
    snprintf(idUrl, sizeof(idUrl), "https://api.foojay.io/disco/v3.0/ids/%s", pkgId);
    if (!jiHttpGetToBuffer(idUrl, idResponse, sizeof(idResponse))) return 0;
    if (!jiJsonExtractString(idResponse, "\"direct_download_uri\":\"", downloadUrl, sizeof(downloadUrl)) ||
        !jiJsonExtractString(idResponse, "\"checksum\":\"", expectedChecksum, sizeof(expectedChecksum)) ||
        !jiJsonExtractString(idResponse, "\"filename\":\"", filename, sizeof(filename))) {
        return 0;
    }

    // Step 3: download with progress, into a scratch temp dir
    GetTempPathA(sizeof(sysTempPath), sysTempPath);
    snprintf(tempDir, sizeof(tempDir), "%sjr-jdk-install", sysTempPath);
    jiMkdirRecursive(tempDir);
    snprintf(zipPath, sizeof(zipPath), "%s\\%s", tempDir, filename);

    {
        JiProgress ps;
        char label[160];
        snprintf(label, sizeof(label), "Downloading Eclipse Temurin JDK %d...", majorVersion);
        jiProgressInit(&ps, guiMode, hasConsole, label);
        {
            int dlOk = jiHttpDownloadToFile(downloadUrl, zipPath, &ps);
            jiProgressFinish(&ps);
            if (!dlOk) { jiRemoveDirTree(tempDir); return 0; }
        }
    }

    // Step 4: verify integrity before extracting anything
    if (!jiSha256File(zipPath, actualChecksum, sizeof(actualChecksum)) ||
        _stricmp(actualChecksum, expectedChecksum) != 0) {
        jiRemoveDirTree(tempDir);
        return 0;
    }

    // Step 5: extract, then find the single top-level folder the zip contains
    snprintf(extractDir, sizeof(extractDir), "%s\\extract", tempDir);
    jiRemoveDirTree(extractDir);
    jiMkdirRecursive(extractDir);
    if (!jiExtractZip(zipPath, extractDir)) { jiRemoveDirTree(tempDir); return 0; }

    if (!jiFindSingleSubdir(extractDir, subdirName, sizeof(subdirName))) {
        jiRemoveDirTree(tempDir);
        return 0;
    }

    // Step 6: promote it into the jbang-compatible flattened cache slot
    jiMkdirRecursive(cacheRoot);
    snprintf(extractedJdkPath, sizeof(extractedJdkPath), "%s\\%s", extractDir, subdirName);
    if (!jiMoveDirectory(extractedJdkPath, targetDir)) {
        jiRemoveDirTree(tempDir);
        return 0;
    }

    jiRemoveDirTree(tempDir);

    strncpy(outJdkHome, targetDir, outJdkHomeSize - 1);
    outJdkHome[outJdkHomeSize - 1] = '\0';
    return 1;
}
