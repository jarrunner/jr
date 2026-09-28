/* macOS-only extension of jr-posix.h: _NSGetExecutablePath (ExeInfo's own-path lookup, since macOS
   has no /proc/self/exe). Kept as a separate header rather than an #ifdef in jr-posix.h so the
   Linux/generic generation never needs to know this header exists. */
#include "jr-posix.h"
#include <mach-o/dyld.h>
