#ifndef JR_JAVAINSTALL_H
#define JR_JAVAINSTALL_H

#include <windows.h>

// Locate or auto-install a JDK of the given major version, matching jbang's own
// cache layout (%USERPROFILE%\.jbang\cache\jdks\<majorVersion>) so the two tools
// can share downloads.
//
// If <cacheRoot>\<majorVersion>\bin\java.exe already exists, uses it directly with
// no network access at all; with atLeast set, any cached <N> >= majorVersion will do
// (the newest is taken). Otherwise asks the user (unless assumeYes), then
// downloads the matching Eclipse Temurin build from the Foojay Disco API (the same
// API jbang itself uses) for the machine's native architecture - on ARM64, Azul Zulu
// where Temurin has no ARM64 build - verifies its SHA256, extracts it with the tar.exe already
// bundled with Windows, and moves it into place.
//
// cacheRootOverride, when non-NULL/non-empty, replaces the default
// %USERPROFILE%\.jbang\cache\jdks root - used by JR_JDK_CACHE_DIR so tests can run
// against a scratch directory instead of the real jbang cache.
//
// reason, when non-NULL, opens the confirmation prompt instead of "Java was not
// found." - used when a Java was found but is the wrong version.
//
// On success writes the JDK home directory (e.g. ...\jdks\21) to outJdkHome and
// returns 1. On failure or user decline, returns 0 and outJdkHome is untouched -
// the caller should fall back to its existing "Java Not Found" error.
int autoInstallJava(int majorVersion, int atLeast, const char* reason,
                     BOOL hasConsole, BOOL guiMode, BOOL assumeYes,
                     const char* cacheRootOverride, char* outJdkHome, size_t outJdkHomeSize);

#endif
