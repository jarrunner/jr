#pragma once
/* Headers the TeaVM port of jr compiles against. The generated WinApi names this file in @Include (gen-bindings.cmd, --include), so TeaVM writes #include "jr-winapi.h" into every C file that calls Win32, and the build finds it with -I bindings/windows (PRP-37; until then postprocess.ps1 forced these into definitions.h). Not "jr.h": on a case-insensitive filesystem that finds TeaVM's own Jr.h, generated for the class jarrunner.jr.Jr. */
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <io.h>
#include <windows.h>
#include <winhttp.h>
#include <bcrypt.h>
#include <commctrl.h>
#include <wincrypt.h>
#include "mssign.h"
#include "jr-shims.h"
