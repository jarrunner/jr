/* One-line shims for Win32 functions that take a struct BY VALUE, which a TeaVM @Import cannot pass
   (see jextract-teavm README, "Structs by value"). Each macro takes a pointer instead and is bound in
   winapi.symbols with macro:. Keep this to the one or two functions that have no other route.
   ReadConsoleOutputCharacterW (PRP-31; W since PRP-34): reads what the JVM printed to the console when it failed to start. */
#pragma once
#include <windows.h>
#define JX_ReadConsoleOutputCharacterW(h, buf, n, coord, read) ReadConsoleOutputCharacterW((h), (buf), (n), *(const COORD *)(coord), (read))
