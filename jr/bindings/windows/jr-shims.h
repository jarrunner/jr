/* One-line shims for Win32 functions that take a struct BY VALUE, which a TeaVM @Import cannot pass
   (see jextract_teavm README, "Structs by value"). Each macro takes a pointer instead and is bound in
   winapi.symbols with macro:. Keep this to the one or two functions that have no other route.
   ReadConsoleOutputCharacterA (PRP-31): reads what the JVM printed to the console when it failed to start. */
#pragma once
#include <windows.h>
#define JX_ReadConsoleOutputCharacterA(h, buf, n, coord, read) ReadConsoleOutputCharacterA((h), (buf), (n), *(const COORD *)(coord), (read))
