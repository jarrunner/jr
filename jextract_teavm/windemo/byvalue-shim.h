/* A struct passed or returned BY VALUE cannot cross a TeaVM @Import (a Structure is always a void* in TeaVM's C).
   A one-line macro turns it into pointers, and `macro:` binds the macro like a function. */
#include <windows.h>
#include <stdlib.h>
#define JX_WindowFromPoint(p) WindowFromPoint(*(const POINT *)(p))
#define JX_div(num, den, out) ((void)(*(div_t *)(out) = div((num), (den))))
