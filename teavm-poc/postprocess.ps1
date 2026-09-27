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

# Force system Windows.h/time.h to be seen before ANY generated code uses a WinAPI or CRT
# symbol via @Import, angle-bracket form (searches ONLY -I/system dirs, never the local
# directory) rather than a command-line `-include <bare-name>` flag (which clang/gcc treat as
# quote-form - local-dir-first). This matters concretely: TeaVM itself generates its OWN
# time.h/time.c in this same directory for its date/time runtime support, so `-include time.h`
# silently shadows the real CRT header with TeaVM's unrelated one, and the compile fails with
# a bogus "conflicting types for 'time'" pointing at core.c - see 07-prp.02.step1-findings.md.
# definitions.h is the right place because core.h -> definitions.h is the first thing every
# single generated per-class header transitively includes.
#
# winhttp.h/bcrypt.h/commctrl.h are added the same way for the same reason (PRP-09, java
# auto-install): windows.h alone does NOT pull these in (they are separate SDK headers), so
# without them WinHttpOpen/BCryptOpenAlgorithmProvider/InitCommonControlsEx etc. are undeclared.
#
# mssign.h (PRP-20 phase 2, resource editing/signing) is the same story but for a header that does
# not exist anywhere: mssign32.dll has no SDK header at all, so SignerSignEx2/SignerFreeSignerContext
# and the SIGNER_* structs are declared in bindings/mssign.h, which jr.h already includes for
# jextract-teavm's own parsing - this copies that same file next to the generated all.c and includes
# it here too, or the real build has no declaration for those two functions ("implicit function
# declaration"). wincrypt.h needs no such treatment: windows.h already pulls it in transitively.
$oldTop = "#pragma once`n#include " + '"config.h"'
$newTop = "#pragma once`n#include <Windows.h>`n#include <time.h>`n#include <winhttp.h>`n#include <bcrypt.h>`n#include <commctrl.h>`n#include " + '"mssign.h"' + "`n#include " + '"config.h"'
if ($definitions -notmatch [regex]::Escape($oldTop)) {
    throw "definitions.h's opening lines did not match the expected TeaVM-generated content - TeaVM version may have changed this file, check manually."
}
$definitions = $definitions.Replace($oldTop, $newTop)

Copy-Item -Path (Join-Path $PSScriptRoot "bindings\mssign.h") -Destination (Join-Path $Dir "mssign.h") -Force

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
if ($uchar -notmatch [regex]::Escape($oldUchar)) {
    throw "uchar.h did not match the expected TeaVM-generated content - TeaVM version may have changed this file, check manually."
}
$uchar = $uchar.Replace($oldUchar, $newUchar)
Set-Content -Path $ucharPath -Value $uchar -NoNewline

Write-Host "Patched $definitionsPath and $ucharPath for clang/mingw msvcrt."
