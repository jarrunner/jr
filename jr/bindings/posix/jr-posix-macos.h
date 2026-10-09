#pragma once
/* macOS-only extension of jr-posix.h: _NSGetExecutablePath (ExeInfo's own-path lookup, since macOS
   has no /proc/self/exe). Kept as a separate header rather than an #ifdef in jr-posix.h so the
   Linux/generic generation never needs to know this header exists.
   Also _dyld_get_image_header + getsectiondata, which find the app config the maven plugin writes
   into this binary's __DATA,__jrc section (PRP-36). */
#include "jr-posix.h"
#include <mach-o/dyld.h>
#include <mach-o/getsect.h>
