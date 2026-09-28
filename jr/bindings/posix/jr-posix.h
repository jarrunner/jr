/* The POSIX surface jr's Linux/macOS port binds - files, dirs, env, processes, time, buffered I/O.
   Mirrors jr.h's role for the Windows side; posix.symbols lists exactly what jextract-teavm reads
   off this header. See posix/posix.h (PRP-12/19's demo) for the WEXITSTATUS/WIFEXITED wrapper
   rationale - repeated here rather than shared because this header also needs stdio.h/string.h/
   sys/utsname.h that the demo's posix.h does not. */
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <spawn.h>
#include <time.h>
#include <limits.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/utsname.h>

/* On macOS WEXITSTATUS/WIFEXITED take their argument's address (_W_INT), so they need a variable;
   these take any int. External linkage, not static: jextract skips internal-linkage functions.
   This header is force-included into TeaVM's single all.c, so each is defined exactly once. */
int jx_wexitstatus(int status) { return WEXITSTATUS(status); }
int jx_wifexited(int status) { return WIFEXITED(status); }

/* jextract-teavm binds functions/constants/structs, not raw extern globals - environ (POSIX,
   declared in unistd.h but not exposed to jextract's declaration walk the same way) needs this
   one-line wrapper to become bindable, so posix_spawn's child gets the real parent environment
   rather than an empty or hand-curated one. */
extern char **environ;
char **jx_environ(void) { return environ; }
