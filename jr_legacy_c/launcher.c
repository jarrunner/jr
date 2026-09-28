#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdarg.h>
#include <sys/stat.h>
#include <time.h>
#include <io.h>
#include <fcntl.h>

#include "javainstall.h"
#include "resedit.h"

#define MAX_PATH_LEN 32768
#define MAX_CMD_LEN 32768
#define MAX_CONFIG_LINE 4096

// Configuration structure
typedef struct {
    char vmArgs[MAX_CMD_LEN];      // VM arguments (before -jar)
    char javaArgs[MAX_CMD_LEN];    // Java arguments (-jar, -cp, main class, etc.)
    char appArgs[MAX_CMD_LEN];     // Application arguments (after jar/class)
    char logFile[MAX_PATH];        // Log file path
    char logLevel[32];             // Log level: info, warning, error, none
    int logOverwrite;              // Overwrite log file (1) or append (0)
    int enableAOT;                 // Enable AOT cache (1=yes, 0=no, -1=not specified)
    int useJvmDll;                 // Load the JVM in-process (1=yes, 0=no, -1=not specified)
    char javaHome[MAX_PATH];       // Explicit JDK home (skips PATH lookup and the version check); empty = not specified
    int javaVersion;                // Required major version (0=not specified: any Java will do, install the default)
    int javaVersionAtLeast;         // 1 if written "NN+" (NN or newer), 0 if "NN" (exactly NN) - same convention as jbang's //JAVA
    int javaAutoInstall;            // Auto-install a JDK if none is found (1=yes, 0=no, -1=not specified)
} LauncherConfig;

// Global log file handle
static FILE* g_logFile = NULL;
static int g_logEnabled = 0;

// Global timing variables
static LARGE_INTEGER g_perfFreq;
static LARGE_INTEGER g_startTime;

// Standard handles as they were before isGuiMode() juggles the console,
// so an in-process JVM can be pointed at the console we end up attached to
static HANDLE g_stdSaved[3];
static int g_stdWasConsole[3];

// Base52 encoding (alphanumeric, case-sensitive without confusing chars)
// Using: 0-9, A-Z (except I, O), a-z (except l, o)
static const char BASE52_CHARS[] = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

// Hide console window as early as possible to prevent flash in GUI mode
// This runs before main() via compiler-specific mechanisms
#ifdef _MSC_VER
    // MSVC: Use #pragma to run at startup
    #pragma section(".CRT$XCU", read)
    static void hideConsoleEarly(void) {
        HWND consoleWnd = GetConsoleWindow();
        if (consoleWnd) {
            ShowWindow(consoleWnd, SW_HIDE);
        }
    }
    __declspec(allocate(".CRT$XCU")) void (*hideConsoleEarlyPtr)(void) = hideConsoleEarly;
#else
    // GCC/MinGW: Use constructor attribute
    __attribute__((constructor)) void hideConsoleEarly() {
        HWND consoleWnd = GetConsoleWindow();
        if (consoleWnd) {
            ShowWindow(consoleWnd, SW_HIDE);
        }
    }
#endif

/**
 * Java Runner (jr) - Smart Java/JavaW Launcher
 *
 * Features:
 * - Auto-detects console vs GUI mode (java.exe vs javaw.exe)
 * - Config file support (.jrc) for renamed executables
 * - AOT cache support (JDK 25+) with auto-management
 * - Flexible configuration (VM args, Java args, App args)
 * - Optional debug logging
 * - Performance timing measurements
 */

// Initialize high-resolution timer
void initTimer() {
    QueryPerformanceFrequency(&g_perfFreq);
    QueryPerformanceCounter(&g_startTime);
}

// Get elapsed microseconds since start
long long getElapsedMicros() {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    return ((now.QuadPart - g_startTime.QuadPart) * 1000000LL) / g_perfFreq.QuadPart;
}

// Logging functions
void initLog(const char* logPath, int overwrite) {
    if (!logPath || !*logPath) {
        g_logEnabled = 0;
        return;
    }

    const char* mode = overwrite ? "w" : "a";
    g_logFile = fopen(logPath, mode);
    if (g_logFile) {
        g_logEnabled = 1;

        // Write header
        time_t now = time(NULL);
        char timebuf[64];
        struct tm* tm_info = localtime(&now);
        strftime(timebuf, sizeof(timebuf), "%Y-%m-%d %H:%M:%S", tm_info);

        fprintf(g_logFile, "\n========================================\n");
        fprintf(g_logFile, "Java Runner Log - %s\n", timebuf);
        fprintf(g_logFile, "========================================\n");
        fflush(g_logFile);
    }
}

void writeLog(const char* level, const char* format, ...) {
    if (!g_logEnabled || !g_logFile) return;

    va_list args;
    va_start(args, format);

    fprintf(g_logFile, "[%s] ", level);
    vfprintf(g_logFile, format, args);
    fprintf(g_logFile, "\n");
    fflush(g_logFile);

    va_end(args);
}

void closeLog() {
    if (g_logFile) {
        fprintf(g_logFile, "========================================\n\n");
        fclose(g_logFile);
        g_logFile = NULL;
        g_logEnabled = 0;
    }
}

// Trim whitespace from string (in-place)
void trim(char* str) {
    if (!str || !*str) return;

    // Trim leading spaces
    char* start = str;
    while (*start && (*start == ' ' || *start == '\t' || *start == '\r' || *start == '\n')) {
        start++;
    }

    if (start != str) {
        memmove(str, start, strlen(start) + 1);
    }

    // Trim trailing spaces
    char* end = str + strlen(str) - 1;
    while (end >= str && (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n')) {
        *end = '\0';
        end--;
    }
}

// Encode 64-bit number to base52 string
void encodeBase52(unsigned long long value, char* output, size_t maxLen) {
    if (maxLen < 2) return;

    if (value == 0) {
        output[0] = BASE52_CHARS[0];
        output[1] = '\0';
        return;
    }

    char temp[32];
    int pos = 0;

    while (value > 0 && pos < 31) {
        temp[pos++] = BASE52_CHARS[value % 52];
        value /= 52;
    }

    // Reverse the string
    int i;
    for (i = 0; i < pos && i < (int)maxLen - 1; i++) {
        output[i] = temp[pos - 1 - i];
    }
    output[i] = '\0';
}

// Get file size and last modified time
int getFileInfo(const char* path, unsigned long long* size, unsigned long long* modTime) {
    struct _stat64 st;
    if (_stat64(path, &st) != 0) {
        return 0;
    }
    *size = (unsigned long long)st.st_size;
    *modTime = (unsigned long long)st.st_mtime;
    return 1;
}

// Bounded copy: like strcpy but can never overrun destSize, and logs (rather than
// silently truncating) if the source didn't fit - PRP-11's memory-safety pass replaced
// every strcpy-into-a-fixed-stack-buffer call site with this, since a MAX_PATH buffer is
// only safe as long as every caller's input is ALSO within MAX_PATH, an invariant that
// held by convention but was never checked at the point of copying.
static void safeCopy(char* dest, size_t destSize, const char* src) {
    size_t len = strlen(src);
    if (len >= destSize) {
        writeLog("WARNING", "safeCopy: truncating %zu-byte value to fit %zu-byte buffer", len, destSize);
        len = destSize - 1;
    }
    memcpy(dest, src, len);
    dest[len] = '\0';
}

// Build AOT cache filename: <jarname>.<size_base52>.<modtime_base52>.aot
void buildAOTCacheName(const char* jarPath, char* aotPath, size_t aotPathSize) {
    unsigned long long size, modTime;
    if (!getFileInfo(jarPath, &size, &modTime)) {
        aotPath[0] = '\0';
        return;
    }

    // Extract directory and filename without extension
    char dirPath[MAX_PATH];
    char baseName[MAX_PATH];

    const char* lastSlash = strrchr(jarPath, '\\');
    if (!lastSlash) lastSlash = strrchr(jarPath, '/');

    if (lastSlash) {
        size_t dirLen = lastSlash - jarPath;
        if (dirLen >= sizeof(dirPath)) {
            dirLen = sizeof(dirPath) - 1;
        }
        strncpy(dirPath, jarPath, dirLen);
        dirPath[dirLen] = '\0';
        safeCopy(baseName, sizeof(baseName), lastSlash + 1);
    } else {
        dirPath[0] = '\0';
        safeCopy(baseName, sizeof(baseName), jarPath);
    }

    // Remove .jar extension
    char* dotPos = strrchr(baseName, '.');
    if (dotPos) *dotPos = '\0';

    // Encode size and modTime to base52
    char sizeStr[32], modTimeStr[32];
    encodeBase52(size, sizeStr, sizeof(sizeStr));
    encodeBase52(modTime, modTimeStr, sizeof(modTimeStr));

    // Build final path
    if (dirPath[0]) {
        snprintf(aotPath, aotPathSize, "%s\\%s.%s.%s.aot",
                 dirPath, baseName, sizeStr, modTimeStr);
    } else {
        snprintf(aotPath, aotPathSize, "%s.%s.%s.aot",
                 baseName, sizeStr, modTimeStr);
    }
}

// Delete outdated AOT cache files for the given JAR
void cleanupOldAOTFiles(const char* jarPath, const char* currentAOTPath) {
    char dirPath[MAX_PATH];
    char baseName[MAX_PATH];
    char pattern[MAX_PATH];

    // Extract directory and base filename
    const char* lastSlash = strrchr(jarPath, '\\');
    if (!lastSlash) lastSlash = strrchr(jarPath, '/');

    if (lastSlash) {
        size_t dirLen = lastSlash - jarPath;
        if (dirLen >= sizeof(dirPath)) {
            dirLen = sizeof(dirPath) - 1;
        }
        strncpy(dirPath, jarPath, dirLen);
        dirPath[dirLen] = '\0';
        safeCopy(baseName, sizeof(baseName), lastSlash + 1);
    } else {
        GetCurrentDirectoryA(sizeof(dirPath), dirPath);
        safeCopy(baseName, sizeof(baseName), jarPath);
    }

    // Remove .jar extension
    char* dotPos = strrchr(baseName, '.');
    if (dotPos) *dotPos = '\0';

    // Build search pattern: <baseName>.*.aot
    snprintf(pattern, sizeof(pattern), "%s\\%s.*.aot", dirPath, baseName);

    // Find all matching AOT files
    WIN32_FIND_DATAA findData;
    HANDLE hFind = FindFirstFileA(pattern, &findData);

    if (hFind != INVALID_HANDLE_VALUE) {
        // Extract filename from currentAOTPath for comparison
        const char* currentFileName = strrchr(currentAOTPath, '\\');
        if (!currentFileName) currentFileName = strrchr(currentAOTPath, '/');
        if (currentFileName) {
            currentFileName++;
        } else {
            currentFileName = currentAOTPath;
        }

        do {
            // Delete if it's not the current AOT file (compare filenames only)
            if (_stricmp(findData.cFileName, currentFileName) != 0) {
                char fullPath[MAX_PATH];
                snprintf(fullPath, sizeof(fullPath), "%s\\%s", dirPath, findData.cFileName);
                DeleteFileA(fullPath);
                writeLog("INFO", "Cleaned up old AOT file: %s", fullPath);
            }
        } while (FindNextFileA(hFind, &findData));
        FindClose(hFind);
    }
}

// Function to find java executable in PATH
int findJavaInPath(const char* exeName, char* outPath, size_t outPathSize) {
    char* pathEnv = getenv("PATH");
    if (!pathEnv) {
        return 0;
    }

    // Make a copy since strtok modifies the string
    char* pathCopy = _strdup(pathEnv);
    if (!pathCopy) {
        return 0;
    }

    char* token = strtok(pathCopy, ";");
    while (token) {
        char testPath[MAX_PATH];
        snprintf(testPath, sizeof(testPath), "%s\\%s", token, exeName);

        // Check if file exists
        DWORD attrib = GetFileAttributesA(testPath);
        if (attrib != INVALID_FILE_ATTRIBUTES && !(attrib & FILE_ATTRIBUTE_DIRECTORY)) {
            strncpy(outPath, testPath, outPathSize - 1);
            outPath[outPathSize - 1] = '\0';
            free(pathCopy);
            return 1;
        }

        token = strtok(NULL, ";");
    }

    free(pathCopy);
    return 0;
}

// Remember the standard handles, and whether each one is a console (as opposed
// to a file or pipe the user redirected). Must run before FreeConsole().
void saveStdHandles() {
    static const DWORD ids[3] = {STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE};
    int i;
    for (i = 0; i < 3; i++) {
        DWORD mode;
        HANDLE h = GetStdHandle(ids[i]);
        g_stdSaved[i] = h;
        g_stdWasConsole[i] = (h && h != INVALID_HANDLE_VALUE && GetConsoleMode(h, &mode));
    }
}

// Point CRT descriptor fd and the process standard handle stdId at h.
// Takes ownership of h.
void bindStdStream(int fd, DWORD stdId, HANDLE h) {
    int tmp = _open_osfhandle((intptr_t)h, (fd == 0) ? _O_RDONLY : _O_WRONLY);
    if (tmp < 0) {
        CloseHandle(h);
        return;
    }
    if (_dup2(tmp, fd) == 0) {
        _close(tmp);  // closes h; fd now holds its own duplicate
        SetStdHandle(stdId, (HANDLE)_get_osfhandle(fd));
    } else {
        _close(tmp);
    }
}

// AttachConsole() installs the new console's handles as the process standard
// handles, which throws away any redirection the user asked for - that is why
// `jr app.jar > out.txt` used to produce an empty file. File and pipe handles
// survive FreeConsole() untouched, so putting the saved ones back restores the
// redirection for both launch modes.
void restoreRedirectedStdHandles() {
    static const DWORD ids[3] = {STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE};
    int i;
    for (i = 0; i < 3; i++) {
        if (!g_stdWasConsole[i] && g_stdSaved[i] && g_stdSaved[i] != INVALID_HANDLE_VALUE) {
            SetStdHandle(ids[i], g_stdSaved[i]);
        }
    }
}

// The FreeConsole/AttachConsole dance in isGuiMode() also invalidates the
// handles the CRT cached for fd 0/1/2 at startup. That is harmless when we spawn
// java.exe (the child gets handles we pass explicitly), but an in-process JVM
// resolves System.out through _get_osfhandle(1), so those descriptors have to be
// repointed at the console we actually ended up attached to. Only streams that
// were consoles are touched; redirected ones are already correct.
void rebindConsoleStdStreams(BOOL hasConsole) {
    static const DWORD ids[3] = {STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE};
    int i;
    for (i = 0; i < 3; i++) {
        HANDLE h;

        if (!g_stdWasConsole[i]) continue;

        if (hasConsole) {
            h = CreateFileA(i == 0 ? "CONIN$" : "CONOUT$",
                            GENERIC_READ | GENERIC_WRITE,
                            FILE_SHARE_READ | FILE_SHARE_WRITE,
                            NULL, OPEN_EXISTING, 0, NULL);
        } else {
            // GUI mode: there is no console at all, send it to the bit bucket
            h = CreateFileA("NUL", GENERIC_READ | GENERIC_WRITE,
                            FILE_SHARE_READ | FILE_SHARE_WRITE,
                            NULL, OPEN_EXISTING, 0, NULL);
        }

        if (h != INVALID_HANDLE_VALUE) {
            bindStdStream(i, ids[i], h);
        }
    }
}

// Function to detect if we're in GUI mode (double-clicked from Explorer)
// Returns: TRUE if GUI mode (should use javaw.exe), FALSE if console mode
BOOL isGuiMode() {
    // With CONSOLE subsystem, Windows already created a console for us
    // We need to detect if we were launched from a terminal (console mode)
    // or double-clicked from Explorer (GUI mode)

    // Record the standard handles while they are still valid
    saveStdHandles();

    // First, hide the console window to prevent flashing in GUI mode
    HWND consoleWnd = GetConsoleWindow();
    if (consoleWnd) {
        ShowWindow(consoleWnd, SW_HIDE);
    }

    // Free our current console so we can try to attach to parent
    FreeConsole();

    // Now try to attach to parent console
    BOOL couldAttach = AttachConsole(ATTACH_PARENT_PROCESS);

    if (couldAttach) {
        // We could attach to parent console - we're in console mode!
        // Keep attached so Java can inherit it
        // Make console visible again (it's the parent's console now)
        consoleWnd = GetConsoleWindow();
        if (consoleWnd) {
            ShowWindow(consoleWnd, SW_SHOW);
        }
        return FALSE;  // Console mode
    }

    // Couldn't attach to parent console - we were double-clicked from Explorer
    // Console stays hidden (already hidden above)
    return TRUE;  // GUI mode
}

// Function to show message appropriately (console or GUI)
void showMessage(BOOL hasConsole, const char* title, const char* message, UINT type) {
    if (hasConsole) {
        // Console mode - use printf
        if (type == MB_ICONERROR) {
            printf("\n[ERROR] %s\n", title);
        } else if (type == MB_ICONINFORMATION) {
            printf("\n[INFO] %s\n", title);
        } else {
            printf("\n%s\n", title);
        }
        printf("%s\n\n", message);
    } else {
        // GUI mode - use MessageBox
        MessageBoxA(NULL, message, title, type);
    }
    writeLog(type == MB_ICONERROR ? "ERROR" : "INFO", "%s: %s", title, message);
}

// Parse the jvm= config value / --jvm-* flags
// Returns 1 for in-process (jvm.dll), 0 for java.exe, -1 if unrecognised
int parseJvmMode(const char* value) {
    if (_stricmp(value, "dll") == 0 || _stricmp(value, "jvmdll") == 0 ||
        _stricmp(value, "jvm.dll") == 0 || _stricmp(value, "inprocess") == 0 ||
        _stricmp(value, "in-process") == 0) {
        return 1;
    }
    if (_stricmp(value, "exe") == 0 || _stricmp(value, "javaexe") == 0 ||
        _stricmp(value, "java.exe") == 0 || _stricmp(value, "process") == 0 ||
        _stricmp(value, "external") == 0) {
        return 0;
    }
    return -1;
}

// Parse config file (.jrc format)
// Returns 1 on success, 0 on failure
void initConfig(LauncherConfig* config) {
    memset(config, 0, sizeof(LauncherConfig));
    config->enableAOT = -1;  // Not specified (use default or cmdline)
    config->useJvmDll = -1;  // Not specified (use default or cmdline)
    config->logOverwrite = 0; // Append by default
    config->javaVersion = 0;      // Not specified (use built-in default)
    config->javaAutoInstall = -1; // Not specified (use built-in default: enabled)
    strcpy(config->logLevel, "info");
}

// Apply one setting. The .jrc file and the -Xjr:key=value command-line options
// both come through here, so every .jrc key can also be given on the command line
// and there is only one list of keys. Returns 0 for an unknown key.
int applyConfigKey(LauncherConfig* config, const char* key, const char* value) {
    if (_stricmp(key, "vm.args") == 0) {
        safeCopy(config->vmArgs, sizeof(config->vmArgs), value);
    } else if (_stricmp(key, "java.args") == 0) {
        safeCopy(config->javaArgs, sizeof(config->javaArgs), value);
    } else if (_stricmp(key, "app.args") == 0) {
        safeCopy(config->appArgs, sizeof(config->appArgs), value);
    } else if (_stricmp(key, "log.file") == 0) {
        safeCopy(config->logFile, sizeof(config->logFile), value);
    } else if (_stricmp(key, "log.level") == 0) {
        safeCopy(config->logLevel, sizeof(config->logLevel), value);
    } else if (_stricmp(key, "log.overwrite") == 0) {
        config->logOverwrite = (_stricmp(value, "true") == 0 || strcmp(value, "1") == 0);
    } else if (_stricmp(key, "aot") == 0) {
        if (_stricmp(value, "true") == 0 || strcmp(value, "1") == 0) {
            config->enableAOT = 1;
        } else if (_stricmp(value, "false") == 0 || strcmp(value, "0") == 0) {
            config->enableAOT = 0;
        } else {
            writeLog("WARNING", "Unrecognised aot value '%s' (expected true or false)", value);
        }
    } else if (_stricmp(key, "jvm") == 0 || _stricmp(key, "jvm.mode") == 0) {
        int mode = parseJvmMode(value);
        if (mode != -1) {
            config->useJvmDll = mode;
        } else {
            writeLog("WARNING", "Unrecognised jvm mode '%s' (expected dll or exe)", value);
        }
    } else if (_stricmp(key, "java.home") == 0) {
        safeCopy(config->javaHome, sizeof(config->javaHome), value);
    } else if (_stricmp(key, "java.version") == 0) {
        config->javaVersion = atoi(value);
        config->javaVersionAtLeast = (strchr(value, '+') != NULL);
    } else if (_stricmp(key, "java.autoinstall") == 0) {
        config->javaAutoInstall = (_stricmp(value, "true") == 0 || strcmp(value, "1") == 0);
    } else {
        return 0;
    }
    return 1;
}

int parseConfigFile(const char* configPath, LauncherConfig* config) {
    FILE* f = fopen(configPath, "r");
    if (!f) return 0;

    char line[MAX_CONFIG_LINE];
    while (fgets(line, sizeof(line), f)) {
        trim(line);

        // Skip empty lines and comments
        if (!*line || *line == '#') continue;

        // Look for key=value
        char* eq = strchr(line, '=');
        if (!eq) continue;

        *eq = '\0';
        char* key = line;
        char* value = eq + 1;

        trim(key);
        trim(value);

        // Unknown keys are ignored here (a .jrc may be shared with a newer jr);
        // on the command line they are an error, see parseJrOptions
        applyConfigKey(config, key, value);
    }

    fclose(f);
    return 1;
}

// Create a sample config file
int createConfigFile(const char* configPath, const char* jarPath) {
    FILE* f = fopen(configPath, "w");
    if (!f) return 0;

    fprintf(f, "# Java Runner Configuration (.jrc format)\n");
    fprintf(f, "# Lines starting with # are comments\n");
    fprintf(f, "# Format follows WinRun4J/jpackage conventions\n");
    fprintf(f, "# Any key can also be overridden for one run on the command line, before\n");
    fprintf(f, "# the app's own arguments: myapp.exe -Xjr:jvm=dll -Xjr:aot=false [app args]\n\n");

    fprintf(f, "# VM arguments (passed before -jar, launcher auto-injects AOT flags here)\n");
    fprintf(f, "#vm.args=-Xmx512m -Xms128m -Dapp.mode=production\n\n");

    fprintf(f, "# Java arguments (everything after VM args: -jar, -cp, class name, etc.)\n");
    if (jarPath && *jarPath) {
        fprintf(f, "java.args=-jar %s\n\n", jarPath);
    } else {
        fprintf(f, "#java.args=-jar yourapp.jar\n");
        fprintf(f, "# Or for classpath: java.args=-cp lib/*:app.jar com.example.Main\n\n");
    }

    fprintf(f, "# Application arguments (passed to your main method)\n");
    fprintf(f, "#app.args=--config myconfig.xml --verbose\n\n");

    fprintf(f, "# AOT cache control (optional, default: true)\n");
    fprintf(f, "#aot=true\n\n");

    fprintf(f, "# How the JVM is started (optional, default: exe)\n");
    fprintf(f, "#   exe - spawn java.exe/javaw.exe as a child process\n");
    fprintf(f, "#   dll - load jvm.dll into this process, so the app runs under\n");
    fprintf(f, "#         this executable's own name and can be killed on its own\n");
    fprintf(f, "#jvm=dll\n\n");

    fprintf(f, "# Debug logging (optional, only used when specified)\n");
    fprintf(f, "#log.file=launcher.log\n");
    fprintf(f, "#log.level=info\n");
    fprintf(f, "#log.overwrite=false\n\n");

    fprintf(f, "# Required Java version: 21 = exactly 21, 21+ = 21 or newer (same convention as jbang).\n");
    fprintf(f, "# If the Java in PATH doesn't match (or there is none), jr offers to download a matching\n");
    fprintf(f, "# Eclipse Temurin JDK into %%USERPROFILE%%\\.jbang\\cache\\jdks\\<version> (same cache jbang itself uses).\n");
    fprintf(f, "#java.version=21+\n");
    fprintf(f, "#java.autoinstall=true\n\n");

    fprintf(f, "# Use this JDK and nothing else (no PATH lookup, no version check, no install)\n");
    fprintf(f, "#java.home=C:\\Java\\jdk-25\n");

    fclose(f);
    return 1;
}

// Get the executable's own filename (without .exe extension)
void getExeBaseName(char* baseName, size_t size) {
    char exePath[MAX_PATH];
    GetModuleFileNameA(NULL, exePath, sizeof(exePath));

    // Get just the filename
    const char* lastSlash = strrchr(exePath, '\\');
    if (!lastSlash) lastSlash = strrchr(exePath, '/');
    const char* fileName = lastSlash ? lastSlash + 1 : exePath;

    // Copy and remove .exe extension
    strncpy(baseName, fileName, size - 1);
    baseName[size - 1] = '\0';

    char* dotPos = strrchr(baseName, '.');
    if (dotPos && _stricmp(dotPos, ".exe") == 0) {
        *dotPos = '\0';
    }
}

// Get the executable's full path (without .exe extension)
// This is used for finding the .jrc config file in the same directory as the .exe
void getExeFullPathWithoutExt(char* fullPath, size_t size) {
    GetModuleFileNameA(NULL, fullPath, size);
    fullPath[size - 1] = '\0';

    // Remove .exe extension
    char* dotPos = strrchr(fullPath, '.');
    if (dotPos && _stricmp(dotPos, ".exe") == 0) {
        *dotPos = '\0';
    }
}

// Extract JAR file path from command line arguments
void extractJarPath(const char* args, char* jarPath, size_t jarPathSize) {
    if (!args || !*args) {
        jarPath[0] = '\0';
        return;
    }

    const char* jarStart = strstr(args, "-jar ");
    if (!jarStart) {
        jarPath[0] = '\0';
        return;
    }

    jarStart += 5; // Skip "-jar "
    while (*jarStart == ' ') jarStart++; // Skip spaces

    // Parse JAR file path (handle quoted and unquoted paths)
    if (*jarStart == '"') {
        jarStart++;
        const char* jarEnd = strchr(jarStart, '"');
        if (jarEnd) {
            size_t len = jarEnd - jarStart;
            if (len < jarPathSize) {
                strncpy(jarPath, jarStart, len);
                jarPath[len] = '\0';
                return;
            }
        }
    } else {
        const char* jarEnd = strchr(jarStart, ' ');
        if (!jarEnd) jarEnd = jarStart + strlen(jarStart);
        size_t len = jarEnd - jarStart;
        if (len < jarPathSize) {
            strncpy(jarPath, jarStart, len);
            jarPath[len] = '\0';
            return;
        }
    }

    jarPath[0] = '\0';
}

// ---------------------------------------------------------------------------
// In-process JVM mode (jvm.dll instead of java.exe)
//
// java.exe is a ~30KB stub whose main() loads jli.dll and calls JLI_Launch(),
// which in turn loads bin\server\jvm.dll. We do exactly the same thing from
// this launcher, so the JVM runs inside *this* process: Task Manager shows
// myapp.exe rather than yet another java.exe, and `taskkill /IM myapp.exe`
// kills one application instead of every Java process on the machine.
//
// Going through jli.dll rather than JNI_CreateJavaVM directly is what keeps
// every java.exe feature intact - -jar manifest handling (Main-Class,
// Class-Path), -cp wildcard expansion, --module, @argfiles, JDK_JAVA_OPTIONS,
// JAVA_TOOL_OPTIONS - none of it has to be reimplemented here.
// ---------------------------------------------------------------------------

// StdArg as declared in the JDK's jli_util.h. Only the first field is read, and
// the layout assumption is validated at runtime before we act on it.
typedef struct {
    char* arg;
    unsigned char has_wildcard;
} JLI_StdArg;

// The JDK declares these JNIEXPORT/JNICALL, i.e. __stdcall. On x64 there is
// only one calling convention so this is cosmetic, but keep it faithful.
typedef int (__stdcall *JLI_Launch_t)(int argc, char** argv,
                                     int jargc, const char** jargv,
                                     int appclassc, const char** appclassv,
                                     const char* fullversion, const char* dotversion,
                                     const char* pname, const char* lname,
                                     unsigned char javaargs, unsigned char cpwildcard,
                                     unsigned char javaw, int ergo);
typedef void (__stdcall *JLI_CmdToArgs_t)(char* cmdline);
typedef int (__stdcall *JLI_GetStdArgc_t)(void);
typedef JLI_StdArg* (__stdcall *JLI_GetStdArgs_t)(void);

// Version strings handed to JLI_Launch. Informational only (java.exe passes its
// own build stamp here); the real version comes from the JVM that gets loaded.
#define JLI_FULL_VERSION "jr"
#define JLI_DOT_VERSION  "jr"

int fileExists(const char* path) {
    DWORD attrib = GetFileAttributesA(path);
    return (attrib != INVALID_FILE_ATTRIBUTES && !(attrib & FILE_ATTRIBUTE_DIRECTORY));
}

// Strip the filename, leaving the containing directory
void getDirName(const char* path, char* outDir, size_t outDirSize) {
    const char* lastSlash = strrchr(path, '\\');
    if (!lastSlash) lastSlash = strrchr(path, '/');

    if (lastSlash) {
        size_t len = lastSlash - path;
        if (len >= outDirSize) len = outDirSize - 1;
        strncpy(outDir, path, len);
        outDir[len] = '\0';
    } else {
        outDir[0] = '\0';
    }
}

// Resolve symlinks/junctions, e.g. the Oracle javapath shim that puts a
// java.exe on PATH with no jli.dll next to it
int resolveRealPath(const char* path, char* outPath, size_t outPathSize) {
    char buffer[MAX_PATH];
    const char* p;
    DWORD len;

    HANDLE h = CreateFileA(path, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (h == INVALID_HANDLE_VALUE) return 0;

    len = GetFinalPathNameByHandleA(h, buffer, (DWORD)sizeof(buffer), FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    CloseHandle(h);

    if (len == 0 || len >= sizeof(buffer)) return 0;

    p = buffer;
    if (strncmp(p, "\\\\?\\", 4) == 0) p += 4;  // drop the extended-length prefix

    strncpy(outPath, p, outPathSize - 1);
    outPath[outPathSize - 1] = '\0';
    return 1;
}

// Locate jli.dll, given the java.exe/javaw.exe we already resolved
int findJliDll(const char* javaExePath, char* outPath, size_t outPathSize) {
    char dir[MAX_PATH];
    char candidate[MAX_PATH];
    char realPath[MAX_PATH];
    const char* javaHomeEnv;

    // Normally right next to java.exe (<jdk>\bin\jli.dll)
    getDirName(javaExePath, dir, sizeof(dir));
    if (dir[0]) {
        snprintf(candidate, sizeof(candidate), "%s\\jli.dll", dir);
        if (fileExists(candidate)) {
            strncpy(outPath, candidate, outPathSize - 1);
            outPath[outPathSize - 1] = '\0';
            return 1;
        }
    }

    // java.exe on PATH may be a symlink into the real JDK
    if (resolveRealPath(javaExePath, realPath, sizeof(realPath))) {
        getDirName(realPath, dir, sizeof(dir));
        if (dir[0]) {
            snprintf(candidate, sizeof(candidate), "%s\\jli.dll", dir);
            if (fileExists(candidate)) {
                strncpy(outPath, candidate, outPathSize - 1);
                outPath[outPathSize - 1] = '\0';
                return 1;
            }
        }
    }

    // Last resort: JAVA_HOME (bin for JDK 9+, jre\bin for the JDK 8 layout)
    javaHomeEnv = getenv("JAVA_HOME");
    if (javaHomeEnv && *javaHomeEnv) {
        snprintf(candidate, sizeof(candidate), "%s\\bin\\jli.dll", javaHomeEnv);
        if (fileExists(candidate)) {
            strncpy(outPath, candidate, outPathSize - 1);
            outPath[outPathSize - 1] = '\0';
            return 1;
        }
        snprintf(candidate, sizeof(candidate), "%s\\jre\\bin\\jli.dll", javaHomeEnv);
        if (fileExists(candidate)) {
            strncpy(outPath, candidate, outPathSize - 1);
            outPath[outPathSize - 1] = '\0';
            return 1;
        }
    }

    return 0;
}

// Major version from a JDK home's "release" file: JAVA_VERSION="25.0.1" -> 25,
// JAVA_VERSION="1.8.0_402" -> 8. Returns 0 if there is no readable release file.
static int readReleaseMajor(const char* jdkHome) {
    char releasePath[MAX_PATH];
    char line[512];
    int major = 0;
    FILE* f;

    snprintf(releasePath, sizeof(releasePath), "%s\\release", jdkHome);
    f = fopen(releasePath, "r");
    if (!f) return 0;

    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "JAVA_VERSION=", 13) == 0) {
            const char* v = line + 13;
            if (*v == '"') v++;
            major = atoi(v);
            if (major == 1) {  // legacy 1.x numbering
                const char* dot = strchr(v, '.');
                major = dot ? atoi(dot + 1) : 0;
            }
            break;
        }
    }

    fclose(f);
    return major;
}

// Major version of a java.exe/javaw.exe, read from <home>\release rather than by
// spawning "java -version" (which would cost a whole JVM start on every launch).
// Follows a symlink, e.g. the Oracle javapath shim, when the direct parent has no
// release file. Returns 0 if it cannot be determined.
static int detectJavaMajor(const char* javaExePath) {
    char binDir[MAX_PATH];
    char home[MAX_PATH];
    char realPath[MAX_PATH];
    int major = 0;

    getDirName(javaExePath, binDir, sizeof(binDir));
    getDirName(binDir, home, sizeof(home));
    if (home[0]) major = readReleaseMajor(home);

    if (major == 0 && resolveRealPath(javaExePath, realPath, sizeof(realPath))) {
        getDirName(realPath, binDir, sizeof(binDir));
        getDirName(binDir, home, sizeof(home));
        if (home[0]) major = readReleaseMajor(home);
    }

    return major;
}

// Run the JVM inside this process.
// cmdLine is the very same string that would otherwise go to CreateProcess, so
// argument and quoting behaviour is identical in both modes; expectedArgv0 is
// the unquoted token at its head, used to sanity-check the StdArg layout.
// Returns 1 if the JVM ran (exitCode receives the application's exit code), or 0
// if the launch could not be set up - the caller then falls back to java.exe.
// The exit code is reported separately because any int, -1 included, is a valid
// application exit code and must not be mistaken for a setup failure.
int launchInProcess(const char* jliPath, char* cmdLine, const char* expectedArgv0, BOOL guiMode, int* exitCode) {
    HMODULE jli;
    JLI_Launch_t jliLaunch;
    JLI_CmdToArgs_t jliCmdToArgs;
    JLI_GetStdArgc_t jliGetStdArgc;
    JLI_GetStdArgs_t jliGetStdArgs;
    JLI_StdArg* stdArgs;
    char** margv;
    int margc, i, ret;

    // LOAD_WITH_ALTERED_SEARCH_PATH makes <jdk>\bin the first search directory,
    // so jli.dll finds the CRT and helper DLLs the JDK ships beside it
    jli = LoadLibraryExA(jliPath, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!jli) {
        writeLog("WARNING", "Could not load %s (error %lu)", jliPath, GetLastError());
        return 0;
    }

    jliLaunch = (JLI_Launch_t)GetProcAddress(jli, "JLI_Launch");
    jliCmdToArgs = (JLI_CmdToArgs_t)GetProcAddress(jli, "JLI_CmdToArgs");
    jliGetStdArgc = (JLI_GetStdArgc_t)GetProcAddress(jli, "JLI_GetStdArgc");
    jliGetStdArgs = (JLI_GetStdArgs_t)GetProcAddress(jli, "JLI_GetStdArgs");

    if (!jliLaunch || !jliCmdToArgs || !jliGetStdArgc || !jliGetStdArgs) {
        writeLog("WARNING", "%s does not export the expected JLI entry points", jliPath);
        return 0;
    }

    // Tokenise exactly the way java.exe does, including @argfile handling
    jliCmdToArgs(cmdLine);
    margc = jliGetStdArgc();
    stdArgs = jliGetStdArgs();

    if (margc < 1 || !stdArgs) {
        writeLog("WARNING", "JLI_CmdToArgs produced no arguments");
        return 0;
    }

    margv = (char**)malloc((margc + 1) * sizeof(char*));
    if (!margv) {
        writeLog("ERROR", "Out of memory building argument vector");
        return 0;
    }

    for (i = 0; i < margc; i++) {
        if (!stdArgs[i].arg) {
            writeLog("WARNING", "JLI argument %d is null - unexpected StdArg layout", i);
            free(margv);
            return 0;
        }
        margv[i] = stdArgs[i].arg;
    }
    margv[margc] = NULL;  // JLI_Launch expects a null-terminated vector

    // Guard against a StdArg layout change in some future JDK: argv[0] has to
    // be the token we ourselves put at the head of the command line
    if (strcmp(margv[0], expectedArgv0) != 0) {
        writeLog("WARNING", "Unexpected JLI argv[0] '%s' (wanted '%s') - not using in-process JVM",
                 margv[0], expectedArgv0);
        free(margv);
        return 0;
    }

    writeLog("INFO", "Invoking in-process JVM via %s (%d args)", jliPath, margc);

    // Mirrors the JDK's own main.c: no JAVA_ARGS, classpath wildcards enabled,
    // javaw semantics (message boxes instead of console output) when GUI mode
    ret = jliLaunch(margc, margv,
                    0, NULL,
                    0, NULL,
                    JLI_FULL_VERSION,
                    JLI_DOT_VERSION,
                    "java",
                    "java",
                    0,                      // javaargs
                    1,                      // cpwildcard
                    guiMode ? 1 : 0,        // javaw
                    0);                     // ergo (unused)

    free(margv);
    *exitCode = ret;
    return 1;
}

// ---------------------------------------------------------------------------
// jr's own command-line options, in the style of java's -X options:
//
//   -Xjr:<key>=<value>            any .jrc key, overriding the .jrc (-Xjr:jvm=dll)
//   -Xjr:yes                      don't ask before auto-installing Java
//   -Xjr:help                     show help
//   -Xjr:create-config[=<jar>]    write a sample <exe>.jrc
//
// Only a LEADING run of -Xjr: tokens belongs to jr. Parsing stops at the first
// token that is not one, and everything from there on goes to the app exactly
// as typed. So no app argument can ever be taken for a jr option, whatever it
// says, and jr never removes anything from the app's arguments. The .jrc file
// stays the preferred place for settings; these are for one-off overrides.
// ---------------------------------------------------------------------------

typedef struct {
    int assumeYes;
    int help;
    int createConfig;
    char createConfigJar[MAX_PATH];
    ReStamp stamp;                 // -Xjr:make/edit/icon/version/sign... (resedit.c)
    char error[512];               // non-empty = a bad option, message for the user
} JrOptions;

// Skip argv[0] (quoted or not) in a raw Windows command line
static const char* skipExeName(const char* cmdLine) {
    const char* p = cmdLine;
    if (*p == '"') {
        p = strchr(p + 1, '"');
        p = p ? p + 1 : cmdLine + strlen(cmdLine);
    } else {
        while (*p && *p != ' ' && *p != '\t') p++;
    }
    while (*p == ' ' || *p == '\t') p++;
    return p;
}

// Copy one whitespace-delimited token into out with its quotes removed (a quote
// only toggles whether a space ends the token, as on a Windows command line), so
// both "-Xjr:java.home=C:\Program Files\x" and -Xjr:java.home="C:\Program Files\x"
// work. Returns the position just after the token.
static const char* readToken(const char* p, char* out, size_t outSize) {
    size_t n = 0;
    int inQuotes = 0;
    while (*p && (inQuotes || (*p != ' ' && *p != '\t'))) {
        if (*p == '"') inQuotes = !inQuotes;
        else if (n + 1 < outSize) out[n++] = *p;
        p++;
    }
    out[n] = '\0';
    return p;
}

// Consume the leading -Xjr: options. Returns where the app's arguments start.
static const char* parseJrOptions(const char* args, LauncherConfig* config, JrOptions* opts) {
    char token[MAX_CONFIG_LINE];
    const char* p = args;

    for (;;) {
        const char* tokenStart;
        char* opt;
        char* eq;

        while (*p == ' ' || *p == '\t') p++;
        tokenStart = p;
        if (!*p) return p;

        p = readToken(p, token, sizeof(token));
        if (strncmp(token, "-Xjr:", 5) != 0) return tokenStart;

        opt = token + 5;
        eq = strchr(opt, '=');

        {
            int r = reParseOption(&opts->stamp, opt, opts->error, sizeof(opts->error));
            if (r < 0) return tokenStart;
            if (r > 0) continue;
        }

        if (_stricmp(opt, "yes") == 0) {
            opts->assumeYes = 1;
        } else if (_stricmp(opt, "help") == 0) {
            opts->help = 1;
        } else if (_strnicmp(opt, "create-config", 13) == 0 && (opt[13] == '\0' || opt[13] == '=')) {
            opts->createConfig = 1;
            if (opt[13] == '=') safeCopy(opts->createConfigJar, sizeof(opts->createConfigJar), opt + 14);
        } else if (eq) {
            *eq = '\0';
            if (!applyConfigKey(config, opt, eq + 1)) {
                snprintf(opts->error, sizeof(opts->error),
                         "Unknown jr option: -Xjr:%s=...\n\nThe keys are the same as in the .jrc file "
                         "(vm.args, java.args, app.args, aot, jvm, java.home, java.version, "
                         "java.autoinstall, log.file, log.level, log.overwrite).", opt);
                return tokenStart;
            }
        } else {
            snprintf(opts->error, sizeof(opts->error),
                     "Unknown jr option: -Xjr:%s\n\nOptions: -Xjr:<key>=<value>, -Xjr:yes, "
                     "-Xjr:help, -Xjr:create-config[=<jar>]", opt);
            return tokenStart;
        }
    }
}

// jr's flags used to be --jvm-dll, --java-home=... and so on. They are gone,
// because they were matched anywhere on the command line and collided with the
// app's own arguments. Without a java.args the first argument must be the jar, so
// an old flag there is unambiguous and gets pointed at its replacement rather
// than being handed to java as a jar name.
static const char* oldFlagReplacement(const char* appArgs) {
    static const char* map[][2] = {
        {"--jvm-dll", "-Xjr:jvm=dll"}, {"--jvm-exe", "-Xjr:jvm=exe"},
        {"--enable-aot", "-Xjr:aot=true"}, {"--disable-aot", "-Xjr:aot=false"},
        {"--java-home", "-Xjr:java.home=PATH"}, {"--yes", "-Xjr:yes"},
        {"--create-config", "-Xjr:create-config[=<jar>]"},
    };
    size_t i;
    for (i = 0; i < sizeof(map) / sizeof(map[0]); i++) {
        size_t n = strlen(map[i][0]);
        if (strncmp(appArgs, map[i][0], n) == 0 &&
            (appArgs[n] == '\0' || appArgs[n] == ' ' || appArgs[n] == '=')) {
            return map[i][1];
        }
    }
    return NULL;
}

static void showHelp(BOOL hasConsole, int useJvmDll, const char* javaExeName,
                     const char* configPath, int configFound, const char* exeBaseName) {
    char javaPath[MAX_PATH] = {0};
    char info[4096];

    if (!findJavaInPath(javaExeName, javaPath, sizeof(javaPath))) {
        safeCopy(javaPath, sizeof(javaPath), "(none in PATH)");
    }

    snprintf(info, sizeof(info),
             "Java Runner (jr) - Smart Java Launcher\n\n"
             "Execution Context: %s\n"
             "Launch Mode: %s\n"
             "Java Executable: %s\n"
             "Java Location: %s\n"
             "Config File: %s (%s)\n\n"
             "Usage:\n"
             "  %s.exe [-Xjr:options] <jar-file> [args...]\n"
             "  %s.exe [-Xjr:options] [args...]     (with java.args in the .jrc)\n\n"
             "jr options must come first; everything after them goes to the app untouched.\n"
             "  -Xjr:<key>=<value>          any .jrc key, overriding the .jrc\n"
             "                              e.g. -Xjr:jvm=dll  -Xjr:aot=false  -Xjr:java.home=PATH\n"
             "  -Xjr:yes                    don't ask before auto-installing Java\n"
             "  -Xjr:create-config[=<jar>]  write a sample %s.jrc\n"
             "  -Xjr:help                   this help\n\n"
             "Making a branded launcher (no Java involved):\n"
             "  -Xjr:make=<out.exe> | -Xjr:edit=<exe>    copy this exe, or edit one in place, then:\n"
             "  -Xjr:icon=<file.ico>        -Xjr:version=<a.b.c.d>   -Xjr:version.<Name>=<text>\n"
             "  -Xjr:manifest=<file>        -Xjr:execution-level=asInvoker|highestAvailable|requireAdministrator\n"
             "  -Xjr:string.<id>=<text>     -Xjr:resource.<type>.<name>=<file>\n"
             "  -Xjr:sign=<file.pfx> (password in JR_SIGN_PASSWORD) | -Xjr:sign.thumbprint=<sha1>\n"
             "  -Xjr:sign.timestamp=<url>   -Xjr:list-resources=<exe>\n\n"
             "If Java is not found, or the .jrc's java.version (NN = exactly NN,\n"
             "NN+ = NN or newer) doesn't match the one in PATH, jr offers to download\n"
             "a matching Eclipse Temurin JDK into %%USERPROFILE%%\\.jbang\\cache\\jdks\\<version>\n"
             "(same cache jbang itself uses). Disable via .jrc: java.autoinstall=false\n\n"
             "Examples:\n"
             "  %s.exe myapp.jar\n"
             "  %s.exe -Xjr:jvm=dll myapp.jar\n"
             "  %s.exe -Xjr:create-config=myapp.jar\n"
             "  %s.exe -Xjr:java.home=C:\\Java\\jdk21 myapp.jar --verbose",
             hasConsole ? "Console (terminal/cmd)" : "GUI (double-clicked)",
             useJvmDll ? "in-process (jvm.dll)" : "child process (java.exe)",
             javaExeName,
             javaPath,
             configPath, configFound ? "found" : "not found",
             exeBaseName, exeBaseName, exeBaseName,
             exeBaseName, exeBaseName, exeBaseName, exeBaseName);
    showMessage(hasConsole, "Java Runner - Help", info, MB_ICONINFORMATION);
}

int main(int argc, char** argv) {
    char javaPath[MAX_PATH] = {0};
    char exeBaseName[MAX_PATH] = {0};
    char configPath[MAX_PATH] = {0};
    LauncherConfig config;
    int useConfig = 0;

    // Initialize high-resolution timer
    initTimer();
    long long startTimeMicros = getElapsedMicros();

    // Get executable base name (without .exe) - for display purposes
    getExeBaseName(exeBaseName, sizeof(exeBaseName));

    // Build config file path - use full path so it works from any directory
    getExeFullPathWithoutExt(configPath, sizeof(configPath));
    strncat(configPath, ".jrc", sizeof(configPath) - strlen(configPath) - 1);

    // Settings, lowest priority first: defaults < .jrc < env vars < -Xjr: options.
    // useConfig only records whether a .jrc exists; every setting is read from
    // config, whichever of those it came from.
    initConfig(&config);
    useConfig = parseConfigFile(configPath, &config);

    // Env hook for automation (not in --help), sits between the .jrc and the command line
    const char* autoInstallEnv = getenv("JR_JAVA_AUTOINSTALL");
    if (autoInstallEnv) {
        config.javaAutoInstall = !(strcmp(autoInstallEnv, "0") == 0 || _stricmp(autoInstallEnv, "false") == 0);
    }

    LPSTR fullCmdLine = GetCommandLineA();
    JrOptions opts;
    memset(&opts, 0, sizeof(opts));
    // The app's arguments, verbatim, after jr's leading -Xjr: options
    const char* appArgs = parseJrOptions(skipExeName(fullCmdLine), &config, &opts);

    // Initialize logging if configured
    if (config.logFile[0]) {
        initLog(config.logFile, config.logOverwrite);
        writeLog("INFO", "Launcher started: %s.exe", exeBaseName);
        writeLog("INFO", "Config file: %s (%s)", configPath, useConfig ? "found" : "not found");
        writeLog("INFO", "vm.args=%s", config.vmArgs);
        writeLog("INFO", "java.args=%s", config.javaArgs);
        writeLog("INFO", "app.args=%s", config.appArgs);
        writeLog("INFO", "App arguments from command line: %s", appArgs);
    }

    // Default stays java.exe so existing setups behave exactly as before
    int useJvmDll = (config.useJvmDll == 1);

    // Detect if we're in GUI mode (double-clicked) or console mode (terminal)
    BOOL guiMode = isGuiMode();
    BOOL hasConsole = !guiMode;
    const char* javaExeName = hasConsole ? "java.exe" : "javaw.exe";

    // Tell the app which way this went. It cannot reliably work this out for itself:
    // the GUI path below calls FreeConsole and repoints the std streams, so from inside
    // the JVM "no console" and "console I was detached from" look alike. Tools that hand
    // a path back to a shell need to know the difference -- with no shell listening they
    // have to open a terminal instead. Inherited by both launch paths (the in-process JVM
    // shares this environment block; CreateProcess is called with a NULL environment).
    SetEnvironmentVariableA("JR_LAUNCH_MODE", hasConsole ? "console" : "gui");

    // Defaults; the AOT block below overwrites these when a cache is actually in play.
    SetEnvironmentVariableA("JR_AOT_STATE", "off");
    SetEnvironmentVariableA("JR_AOT_CACHE", "");

    // Put back any redirection the console switch above discarded
    restoreRedirectedStdHandles();

    // An in-process JVM inherits our descriptors rather than being handed fresh
    // ones, so the console ones have to be repaired too
    if (useJvmDll) {
        rebindConsoleStdStreams(hasConsole);
    }

    writeLog("INFO", "Execution mode: %s", hasConsole ? "Console" : "GUI");
    writeLog("INFO", "Launch mode: %s", useJvmDll ? "in-process (jvm.dll)" : "child process (java.exe)");
    writeLog("INFO", "Java executable: %s", javaExeName);

    if (opts.error[0]) {
        writeLog("ERROR", "%s", opts.error);
        showMessage(hasConsole, "Invalid jr Option", opts.error, MB_ICONERROR);
        closeLog();
        return 1;
    }

    // Resource editing / signing (resedit.c): a tool action, runs no Java at all
    if (reHasAction(&opts.stamp)) {
        char* report = (char*)malloc(65536);
        int ok = report ? reRun(&opts.stamp, report, 65536) : 0;
        if (!report) {
            showMessage(hasConsole, "Error", "Out of memory", MB_ICONERROR);
        } else if (hasConsole) {
            fprintf(ok ? stdout : stderr, "%s%s", report, report[0] && report[strlen(report) - 1] == '\n' ? "" : "\n");
        } else {
            showMessage(hasConsole, ok ? "jr" : "jr - Error", report, ok ? MB_ICONINFORMATION : MB_ICONERROR);
        }
        free(report);
        closeLog();
        return ok ? 0 : 1;
    }
    if (reHasEdits(&opts.stamp)) {
        showMessage(hasConsole, "Invalid jr Option",
                    "Resource and signing options (-Xjr:icon, -Xjr:version..., -Xjr:sign...) need a target:\n"
                    "-Xjr:make=<new.exe> (a copy of this exe) or -Xjr:edit=<existing.exe>.", MB_ICONERROR);
        closeLog();
        return 1;
    }

    // Nothing to run (or help asked for): show help before any Java lookup, so a
    // bare `jr` on a machine without Java never triggers the install prompt
    if (opts.help || (!opts.createConfig && !config.javaArgs[0] && !*appArgs)) {
        showHelp(hasConsole, useJvmDll, javaExeName, configPath, useConfig, exeBaseName);
        closeLog();
        return opts.help ? 0 : 1;
    }

    if (!config.javaArgs[0] && !opts.createConfig) {
        const char* replacement = oldFlagReplacement(appArgs);
        if (replacement) {
            char msg[512];
            snprintf(msg, sizeof(msg),
                     "jr's --flags have been replaced by -Xjr: options, which must come before the jar.\n\n"
                     "Use %s instead. See -Xjr:help.", replacement);
            showMessage(hasConsole, "Invalid jr Option", msg, MB_ICONERROR);
            closeLog();
            return 1;
        }
    }

    if (opts.createConfig) {
        if (createConfigFile(configPath, opts.createConfigJar[0] ? opts.createConfigJar : NULL)) {
            char msg[1024];
            snprintf(msg, sizeof(msg), "Created config file: %s\n\nEdit this file to customize launcher behavior.", configPath);
            showMessage(hasConsole, "Config Created", msg, MB_ICONINFORMATION);
            closeLog();
            return 0;
        } else {
            char msg[1024];
            snprintf(msg, sizeof(msg), "Failed to create config file: %s", configPath);
            showMessage(hasConsole, "Error", msg, MB_ICONERROR);
            closeLog();
            return 1;
        }
    }

    int enableAOT = (config.enableAOT != 0); // Default: enabled

    writeLog("INFO", "AOT enabled: %s", enableAOT ? "true" : "false");

    // Auto-install settings. See javainstall.c and prp/09-prp-java_auto_install.md. The env vars are
    // deliberately test/automation hooks, not documented in --help: JR_TEST_FORCE_NO_JAVA
    // pretends Java isn't there so the install path can be exercised on a machine that
    // already has one; JR_JDK_CACHE_DIR redirects the install location away from the
    // real %USERPROFILE%\.jbang\cache\jdks so testing never touches it.
    // requiredVersion 0 = the .jrc names no version, so any Java found is accepted
    // and the default is only what gets installed when none is found at all.
    int requiredVersion = config.javaVersion > 0 ? config.javaVersion : 0;
    int requireAtLeast = requiredVersion > 0 && config.javaVersionAtLeast;
    int javaVersion = requiredVersion > 0 ? requiredVersion : 25; // Default: latest LTS, and the first with the AOT cache options jr uses

    int autoInstallEnabled = (config.javaAutoInstall != 0); // Default: enabled

    BOOL assumeYes = opts.assumeYes || (getenv("JR_ASSUME_YES") != NULL);
    const char* jdkCacheOverride = getenv("JR_JDK_CACHE_DIR");

    if (config.javaHome[0]) {
        // An explicit Java home (java.home in the .jrc or -Xjr:java.home=) is taken as is
        snprintf(javaPath, sizeof(javaPath), "%s\\bin\\%s", config.javaHome, javaExeName);
        writeLog("INFO", "Using custom Java home: %s", config.javaHome);

        // Verify the path exists
        DWORD attrib = GetFileAttributesA(javaPath);
        if (attrib == INVALID_FILE_ATTRIBUTES || (attrib & FILE_ATTRIBUTE_DIRECTORY)) {
            char error[1024];
            snprintf(error, sizeof(error),
                     "Java not found at specified location:\n%s\n\nPlease check java.home (.jrc) or -Xjr:java.home=.",
                     javaPath);
            showMessage(hasConsole, "Java Not Found", error, MB_ICONERROR);
            closeLog();
            return 1;
        }

    } else {
        // Try to find Java in PATH
        int found = findJavaInPath(javaExeName, javaPath, sizeof(javaPath));

        // Test-only override: pretend nothing was found, so the auto-install path
        // can be exercised on a machine that already has a real JDK on PATH.
        if (found && getenv("JR_TEST_FORCE_NO_JAVA")) {
            writeLog("WARNING", "JR_TEST_FORCE_NO_JAVA set - ignoring Java found in PATH (test mode)");
            found = 0;
        }

        // A Java in PATH that doesn't satisfy java.version counts as not found, so
        // the cache lookup / auto-install below gets its chance at the right one.
        char mismatch[MAX_PATH + 256] = {0};
        if (found && requiredVersion > 0) {
            int foundMajor = detectJavaMajor(javaPath);
            if (foundMajor == 0) {
                writeLog("WARNING", "Could not determine the version of %s; using it anyway", javaPath);
            } else if (requireAtLeast ? foundMajor < requiredVersion : foundMajor != requiredVersion) {
                snprintf(mismatch, sizeof(mismatch),
                         "This application needs Java %d%s, but the Java in PATH is version %d:\n%s",
                         requiredVersion, requireAtLeast ? " or newer" : "", foundMajor, javaPath);
                writeLog("INFO", "Java %d in PATH does not satisfy java.version=%d%s",
                         foundMajor, requiredVersion, requireAtLeast ? "+" : "");
                found = 0;
            } else {
                writeLog("INFO", "Java %d in PATH satisfies java.version=%d%s",
                         foundMajor, requiredVersion, requireAtLeast ? "+" : "");
            }
        }

        if (!found && autoInstallEnabled) {
            char installedHome[MAX_PATH];
            writeLog("INFO", "Attempting auto-install (version %d%s)", javaVersion, requireAtLeast ? "+" : "");
            if (autoInstallJava(javaVersion, requireAtLeast, mismatch[0] ? mismatch : NULL,
                                 hasConsole, guiMode, assumeYes, jdkCacheOverride,
                                 installedHome, sizeof(installedHome))) {
                snprintf(javaPath, sizeof(javaPath), "%s\\bin\\%s", installedHome, javaExeName);
                found = 1;
                writeLog("INFO", "Using auto-installed Java: %s", javaPath);
            } else {
                writeLog("WARNING", "Auto-install did not complete (declined or failed)");
            }
        }

        if (!found && mismatch[0]) {
            char error[1024];
            snprintf(error, sizeof(error),
                     "%s\n\nInstall Java %d%s, or set java.home in the .jrc (or -Xjr:java.home=C:\\path\\to\\jdk).",
                     mismatch, requiredVersion, requireAtLeast ? " or newer" : "");
            showMessage(hasConsole, "Wrong Java Version", error, MB_ICONERROR);
            closeLog();
            return 1;
        }

        if (!found) {
            char error[1024];
            snprintf(error, sizeof(error),
                     "Java not found in PATH.\n\n"
                     "Please ensure Java is installed and added to PATH,\n"
                     "or set java.home in the .jrc (or -Xjr:java.home=C:\\path\\to\\jdk).\n\n"
                     "Looking for: %s",
                     javaExeName);
            showMessage(hasConsole, "Java Not Found", error, MB_ICONERROR);
            closeLog();
            return 1;
        }
        writeLog("INFO", "Using Java: %s", javaPath);
    }

    // -XX:AOTCache / -XX:AOTCacheOutput exist from JDK 25 on. An older JVM refuses
    // to start at all on an option it does not know, so leave them out for it
    // (a version that cannot be read is given the benefit of the doubt).
    if (enableAOT) {
        int major = detectJavaMajor(javaPath);
        if (major > 0 && major < 25) {
            enableAOT = 0;
            writeLog("INFO", "AOT cache skipped: Java %d predates the JDK 25 AOT options", major);
        }
    }

    // Build final command line
    char finalCmdLine[MAX_CMD_LEN];
    char timingProps[512];
    char aotArg[MAX_PATH + 50] = {0};
    char jarFilePath[MAX_PATH] = {0};

    // Measure time before JVM invocation
    long long beforeJVMInvokeMicros = getElapsedMicros();

    // Build timing system properties
    snprintf(timingProps, sizeof(timingProps),
             "-Djarrunner.start.micros=%lld -Djarrunner.beforejvm.micros=%lld",
             startTimeMicros, beforeJVMInvokeMicros);

    if (config.javaArgs[0]) {
        // Config mode: build command from config
        writeLog("INFO", "Using config-based mode");

        // Extract JAR path for AOT (if using -jar)
        extractJarPath(config.javaArgs, jarFilePath, sizeof(jarFilePath));

        // Build AOT cache path if enabled
        if (enableAOT && jarFilePath[0]) {
            char aotCachePath[MAX_PATH];
            buildAOTCacheName(jarFilePath, aotCachePath, sizeof(aotCachePath));

            if (aotCachePath[0]) {
                // Clean up old AOT files
                cleanupOldAOTFiles(jarFilePath, aotCachePath);

                // Check if AOT cache exists
                DWORD attrib = GetFileAttributesA(aotCachePath);
                BOOL aotExists = (attrib != INVALID_FILE_ATTRIBUTES && !(attrib & FILE_ATTRIBUTE_DIRECTORY));

                if (aotExists) {
                    snprintf(aotArg, sizeof(aotArg), "-XX:AOTCache=\"%s\"", aotCachePath);
                    writeLog("INFO", "Using existing AOT cache: %s", aotCachePath);
                } else {
                    snprintf(aotArg, sizeof(aotArg), "-XX:AOTCacheOutput=\"%s\"", aotCachePath);
                    writeLog("INFO", "Creating new AOT cache: %s", aotCachePath);
                }

                // Tell the app which of the two this run is. The JVM assembles the cache at
                // EXIT, so a "creating" run is the only chance to influence what lands in it --
                // an app that knows can exercise itself first and produce a cache worth having,
                // rather than one trained on whatever the user happened to type first.
                // JR_AOT_CACHE names the file, so the app can also regenerate it deliberately.
                SetEnvironmentVariableA("JR_AOT_CACHE", aotCachePath);
                SetEnvironmentVariableA("JR_AOT_STATE", aotExists ? "using" : "creating");
            }
        }

        // The app's own command-line arguments, exactly as typed
        char cmdLineArgs[MAX_CMD_LEN] = {0};
        safeCopy(cmdLineArgs, sizeof(cmdLineArgs), appArgs);

        // Build final command: java [timing] [vm.args] [aot] [java.args] [app.args] [cmdline-args]
        int pos = snprintf(finalCmdLine, sizeof(finalCmdLine), "\"%s\" %s", javaPath, timingProps);

        if (config.vmArgs[0]) {
            pos += snprintf(finalCmdLine + pos, sizeof(finalCmdLine) - pos, " %s", config.vmArgs);
        }

        if (aotArg[0]) {
            pos += snprintf(finalCmdLine + pos, sizeof(finalCmdLine) - pos, " %s", aotArg);
        }

        pos += snprintf(finalCmdLine + pos, sizeof(finalCmdLine) - pos, " %s", config.javaArgs);

        if (config.appArgs[0]) {
            pos += snprintf(finalCmdLine + pos, sizeof(finalCmdLine) - pos, " %s", config.appArgs);
        }

        if (cmdLineArgs[0]) {
            snprintf(finalCmdLine + pos, sizeof(finalCmdLine) - pos, " %s", cmdLineArgs);
        }

    } else {
        // Traditional mode: JAR as first argument
        writeLog("INFO", "Using traditional mode (no java.args)");

        // Everything after jr's own -Xjr: options: <jar> [app args...], exactly as typed.
        // Help for an empty one was already shown above.
        char tempArgs[MAX_CMD_LEN] = {0};
        safeCopy(tempArgs, sizeof(tempArgs), appArgs);
        trim(tempArgs);

        // The jar is the first token - never searched for inside the app's arguments
        readToken(tempArgs, jarFilePath, sizeof(jarFilePath));

        // Build AOT cache path if enabled
        if (enableAOT && jarFilePath[0]) {
            char aotCachePath[MAX_PATH];
            buildAOTCacheName(jarFilePath, aotCachePath, sizeof(aotCachePath));

            if (aotCachePath[0]) {
                // Clean up old AOT files
                cleanupOldAOTFiles(jarFilePath, aotCachePath);

                // Check if AOT cache exists
                DWORD attrib = GetFileAttributesA(aotCachePath);
                BOOL aotExists = (attrib != INVALID_FILE_ATTRIBUTES && !(attrib & FILE_ATTRIBUTE_DIRECTORY));

                if (aotExists) {
                    snprintf(aotArg, sizeof(aotArg), "-XX:AOTCache=\"%s\"", aotCachePath);
                    writeLog("INFO", "Using existing AOT cache: %s", aotCachePath);
                } else {
                    snprintf(aotArg, sizeof(aotArg), "-XX:AOTCacheOutput=\"%s\"", aotCachePath);
                    writeLog("INFO", "Creating new AOT cache: %s", aotCachePath);
                }

                // Tell the app which of the two this run is. The JVM assembles the cache at
                // EXIT, so a "creating" run is the only chance to influence what lands in it --
                // an app that knows can exercise itself first and produce a cache worth having,
                // rather than one trained on whatever the user happened to type first.
                // JR_AOT_CACHE names the file, so the app can also regenerate it deliberately.
                SetEnvironmentVariableA("JR_AOT_CACHE", aotCachePath);
                SetEnvironmentVariableA("JR_AOT_STATE", aotExists ? "using" : "creating");
            }
        }

        // Build final command: java [timing] [aot] -jar <remaining args>
        if (aotArg[0]) {
            snprintf(finalCmdLine, sizeof(finalCmdLine), "\"%s\" %s %s -jar %s",
                     javaPath, timingProps, aotArg, tempArgs);
        } else {
            snprintf(finalCmdLine, sizeof(finalCmdLine), "\"%s\" %s -jar %s",
                     javaPath, timingProps, tempArgs);
        }
    }

    writeLog("INFO", "Final command: %s", finalCmdLine);

    // In-process mode: run the JVM inside this executable so the process keeps
    // its own name. finalCmdLine is reused verbatim, so argument handling,
    // quoting, AOT flags and config precedence are identical to java.exe mode.
    if (useJvmDll) {
        char jliPath[MAX_PATH] = {0};

        if (findJliDll(javaPath, jliPath, sizeof(jliPath))) {
            // jvmCmdLine is consumed destructively by the JLI tokeniser
            char jvmCmdLine[MAX_CMD_LEN];
            int exitCode = 0;

            strncpy(jvmCmdLine, finalCmdLine, sizeof(jvmCmdLine) - 1);
            jvmCmdLine[sizeof(jvmCmdLine) - 1] = '\0';

            if (launchInProcess(jliPath, jvmCmdLine, javaPath, guiMode, &exitCode)) {
                writeLog("INFO", "In-process JVM exited with code: %d", exitCode);
                closeLog();
                return exitCode;
            }
        } else {
            writeLog("WARNING", "jli.dll not found next to %s", javaPath);
        }

        // Anything that stops the in-process launch falls back to java.exe
        // rather than failing outright
        writeLog("WARNING", "Falling back to child process mode (java.exe)");
    }

    // Setup startup info
    STARTUPINFOA si = {sizeof(si)};
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);

    if (hasConsole) {
        // In console mode, explicitly pass the console handles
        si.dwFlags = STARTF_USESTDHANDLES;
        si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
        si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
        si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    }

    // Inherit handles so console I/O works
    if (CreateProcessA(NULL, finalCmdLine, NULL, NULL, TRUE,
                      0, NULL, NULL, &si, &pi)) {

        writeLog("INFO", "Java process started successfully (PID: %lu)", pi.dwProcessId);

        if (hasConsole) {
            // Console mode: Wait for Java process to complete
            WaitForSingleObject(pi.hProcess, INFINITE);

            DWORD exitCode = 0;
            GetExitCodeProcess(pi.hProcess, &exitCode);

            writeLog("INFO", "Java process exited with code: %lu", exitCode);

            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);

            closeLog();
            return exitCode;
        } else {
            // GUI mode: Launch and exit immediately
            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);

            writeLog("INFO", "Launched in GUI mode, launcher exiting");
            closeLog();
            return 0;
        }
    }

    // If we get here, CreateProcess failed
    char error[2048];
    DWORD lastError = GetLastError();
    snprintf(error, sizeof(error),
             "Failed to launch Java process.\n\n"
             "Java: %s\n"
             "Command: %s\n"
             "Error code: %lu\n\n"
             "Make sure Java is properly installed.",
             javaPath, finalCmdLine, lastError);
    showMessage(hasConsole, "Launch Error", error, MB_ICONERROR);

    closeLog();
    return 1;
}
