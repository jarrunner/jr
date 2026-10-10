#pragma once
/* The POSIX surface jr's Linux/macOS port binds - files, dirs, env, processes, time, buffered I/O.
   Mirrors jr-winapi.h's role for the Windows side; posix.symbols lists exactly what jextract-teavm reads
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

/* jvm=dll: the JVM inside this process through the JDK's libjli (JliLauncher, PRP-42). This is C because it is
   about which function is called main, which no binding can express. On macOS JLI_Launch keeps the first thread
   for the Cocoa run loop and calls the executable's main() again on a new thread (java_md_macosx.c, apple_main:
   dlsym(RTLD_DEFAULT, "main")). In jr, main() is TeaVM's, and running it a second time would start the TeaVM
   runtime again on a second OS thread, which it does not support (one global shadow stack, no locks). So TeaVM's
   main is renamed below, and this main sends the second entry straight back into JLI_Launch with the arguments
   jr prepared, without touching TeaVM. On Linux JLI_Launch does not call main again; the hand-off is the same. */
#include <dlfcn.h>
typedef int (*jx_jli_launch_fn)(int, char **, int, const char **, int, const char **, const char *, const char *,
        const char *, const char *, unsigned char, unsigned char, unsigned char, int);
static jx_jli_launch_fn jx_jli_fn;
static int jx_jli_argc;
static char **jx_jli_argv;
static int jx_jli_call(void) {
    return jx_jli_fn(jx_jli_argc, jx_jli_argv, 0, NULL, 0, NULL, "jr", "jr", "java", "java", 0, 1, 0, 0);
}
/* Calls JLI_Launch (fn, from dlsym) with argv (NULL-terminated), keeping both for the second entry into main. */
int jx_jli_start(void *fn, int argc, char **argv) {
    jx_jli_fn = (jx_jli_launch_fn) fn;
    jx_jli_argc = argc;
    jx_jli_argv = argv;
    return jx_jli_call();
}
int jr_teavm_main(int argc, char **argv);
int main(int argc, char **argv) {
    return jx_jli_fn != NULL ? jx_jli_call() : jr_teavm_main(argc, argv);
}
#define main jr_teavm_main
