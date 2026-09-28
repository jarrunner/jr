/* What a TeaVM jr for macOS/Linux would call: files, dirs, env, processes, dynamic loading, time. */
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <dlfcn.h>
#include <spawn.h>
#include <time.h>
#include <limits.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <stdlib.h>
/* On macOS WEXITSTATUS/WIFEXITED take their argument's address (_W_INT), so they need a variable; these take any int.
   External linkage, not static: jextract skips internal-linkage functions. This header is force-included into TeaVM's
   single all.c, so each is defined exactly once. */
int jx_wexitstatus(int status) { return WEXITSTATUS(status); }
int jx_wifexited(int status) { return WIFEXITED(status); }
