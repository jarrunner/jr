<#
Patches a freshly generated TeaVM C-backend output tree (see BuildDriver.java) so it
compiles under llvm-mingw's clang targeting x86_64-w64-mingw32 with the msvcrt CRT
(chosen to avoid a vcredist dependency, same reasoning as jr's own PRP-06).

Without these two patches, definitions.h's platform detection (#ifdef _MSC_VER /
#ifdef __GNUC__) misclassifies clang-mingw as TEAVM_UNIX, because mingw compilers
define __GNUC__ for source compatibility even though the real target is Windows -
see 07-prp.02.step1-findings.md for the full diagnosis.

Usage: powershell -File postprocess.ps1 <generatedCDir>
#>
param(
    [Parameter(Mandatory=$true)][string]$Dir
)

$definitionsPath = Join-Path $Dir "definitions.h"
$definitions = (Get-Content -Raw $definitionsPath) -replace "`r`n", "`n"

# Headers for jr's own @Import calls are NOT patched in here any more (PRP-37, 2026-10-09). The
# generated WinApi carries @Include("jr-winapi.h"), so TeaVM writes that #include at the top of every
# C file that calls Win32, and build-win.ps1 passes -I bindings/windows. That replaced a forced
# include block in definitions.h (Windows.h, time.h, stdio.h, string.h, winhttp.h, bcrypt.h,
# commctrl.h, mssign.h, jr-shims.h) with a byte-identical exe. What is left below are fixes to TeaVM's
# own generated runtime, which move into the jarrunner/teavm fork as source fixes.

$oldPlatformBlock = @'
#ifdef _MSC_VER
    #define alignas(n) __declspec(align(n))
    #define restrict __restrict
    #pragma comment (lib,"uuid.lib")
    #pragma warning(disable:4116)
    #pragma warning(disable:4102)

    #ifdef WINAPI_FAMILY
        #if WINAPI_FAMILY == WINAPI_FAMILY_APP || WINAPI_FAMILY == 2 || WINAPI_FAMILY == 3 || WINAPI_FAMILY == 5
            #undef TEAVM_WINDOWS_UWP
            #define TEAVM_WINDOWS_UWP 1
        #endif
    #endif

    #undef TEAVM_WINDOWS
    #define TEAVM_WINDOWS 1
#endif

#ifdef __GNUC__
    #undef TEAVM_UNIX
    #define TEAVM_UNIX 1
    #include <stdalign.h>
#endif
'@
$newPlatformBlock = @'
#if defined(_MSC_VER) || defined(__MINGW32__)
    #define alignas(n) __declspec(align(n))
    #define restrict __restrict
    #pragma comment (lib,"uuid.lib")
    #pragma warning(disable:4116)
    #pragma warning(disable:4102)

    #ifdef WINAPI_FAMILY
        #if WINAPI_FAMILY == WINAPI_FAMILY_APP || WINAPI_FAMILY == 2 || WINAPI_FAMILY == 3 || WINAPI_FAMILY == 5
            #undef TEAVM_WINDOWS_UWP
            #define TEAVM_WINDOWS_UWP 1
        #endif
    #endif

    #undef TEAVM_WINDOWS
    #define TEAVM_WINDOWS 1

    #if defined(__MINGW32__) && !defined(_MSC_VER)
        /* clang/mingw has no real MSVC __assume intrinsic; map it onto clang's own */
        #define __assume(x) __builtin_assume(x)
    #endif
#elif defined(__GNUC__)
    #undef TEAVM_UNIX
    #define TEAVM_UNIX 1
    #include <stdalign.h>
#endif
'@
# The here-strings take this file's own line endings, which follow the checkout (CRLF on a Windows CI
# runner, LF here): normalise them like the generated files above, or nothing matches.
$oldPlatformBlock = $oldPlatformBlock -replace "`r`n", "`n"
$newPlatformBlock = $newPlatformBlock -replace "`r`n", "`n"
if ($definitions -notmatch [regex]::Escape($oldPlatformBlock)) {
    throw "definitions.h did not match the expected TeaVM-generated platform-detection block - TeaVM version may have changed this file, check manually."
}
$definitions = $definitions.Replace($oldPlatformBlock, $newPlatformBlock)
Set-Content -Path $definitionsPath -Value $definitions -NoNewline

$ucharPath = Join-Path $Dir "uchar.h"
$uchar = (Get-Content -Raw $ucharPath) -replace "`r`n", "`n"
$oldUchar = @'
#pragma once

#if TEAVM_PSP

#include <wchar.h>

typedef uint16_t char16_t;
typedef int char32_t;

static inline size_t c16rtomb(char * s, char16_t c16, mbstate_t * ps) {
    if (s) *s = (char) c16; // Rough conversion
    return 1;
}

static inline size_t mbrtoc16(char16_t * pc16, const char * s, size_t n, mbstate_t * ps) {
    if (pc16 && n > 0) *pc16 = (char16_t) *s; // Rough conversion
    return 1;
}

#else

#include <uchar.h>

#endif
'@
$newUchar = @'
#pragma once

#include <uchar.h>

/* mingw's own uchar.h only declares mbrtoc16/c16rtomb under #ifdef _UCRT - the
   msvcrt target never had these C11 conversion functions. Reuse TeaVM's own
   TEAVM_PSP rough-conversion fallback for this case too. */
#if TEAVM_PSP || (TEAVM_WINDOWS && !defined(_UCRT))

#include <wchar.h>

static inline size_t c16rtomb(char * s, char16_t c16, mbstate_t * ps) {
    if (s) *s = (char) c16; // Rough conversion
    return 1;
}

static inline size_t mbrtoc16(char16_t * pc16, const char * s, size_t n, mbstate_t * ps) {
    if (pc16 && n > 0) *pc16 = (char16_t) *s; // Rough conversion
    return 1;
}

#endif
'@
$oldUchar = $oldUchar -replace "`r`n", "`n"
$newUchar = $newUchar -replace "`r`n", "`n"
if ($uchar -notmatch [regex]::Escape($oldUchar)) {
    throw "uchar.h did not match the expected TeaVM-generated content - TeaVM version may have changed this file, check manually."
}
$uchar = $uchar.Replace($oldUchar, $newUchar)
Set-Content -Path $ucharPath -Value $uchar -NoNewline

# core.h (PRP-31): TeaVM emits every Address as void* except the result of Address.add(), which is char*
# (the cast it needs for the arithmetic, never undone). C converts void* to any pointer type implicitly but
# not char*, so passing ptr.add(n) to a typed @Import parameter (CreateFontIndirectW's const LOGFONTW*) is
# "incompatible pointer types", an error from clang 16 on. Cast the sum back to void*, like every other Address.
$corePath = Join-Path $Dir "core.h"
$core = (Get-Content -Raw $corePath) -replace "`r`n", "`n"
$oldAdd = '#define TEAVM_ADDRESS_ADD(address, offset) ((char *) (address) + (offset))'
$newAdd = '#define TEAVM_ADDRESS_ADD(address, offset) ((void *) ((char *) (address) + (offset)))'
if (-not $core.Contains($oldAdd)) {
    throw "core.h did not contain the expected TEAVM_ADDRESS_ADD - TeaVM version may have changed this file, check manually."
}
Set-Content -Path $corePath -Value $core.Replace($oldAdd, $newAdd) -NoNewline

Write-Host "Patched $definitionsPath, $ucharPath and $corePath for clang/mingw msvcrt."
