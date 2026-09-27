/* Test-only macros, for the refusals in negative.symbols. Not used by the demo program. */
#include <windows.h>
#define JX_STMT(x) do { (void)(x); } while (0)
#define JX_OBJECT 42
#define JX_STRUCT(x) ((POINT){ (x), (x) })
typedef POINT (*JX_BYVALUE_CB)(POINT, int);
typedef int (*JX_VARIADIC_CB)(int, ...);
#define JX_LVALUE(x) ((*(int *)&(x) >> 8) & 0xff)
