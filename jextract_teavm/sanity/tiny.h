#include <stdint.h>
#define GREETING "hello"
#define FLAGS (0x10 | 0x20)
typedef struct Point { int32_t x; int64_t y; char name[16]; } Point;
typedef void* HANDLE_T;
int add(int a, long long b);
HANDLE_T open_thing(const char* name, Point* p);
