#!/usr/bin/perl
# Patches TeaVM's generated fiber.c for macOS, in place: its TEAVM_UNIX event-queue wait uses POSIX real-time
# timers (timer_create/timer_settime, SIGRTMIN, sigwaitinfo), which macOS does not implement. Under __APPLE__
# the same two functions are done with a pthread condition variable and a timed wait instead; Linux is untouched.
# Dies if TeaVM's text has changed, rather than half-patching.  Usage: perl darwin-fiber.pl <dir>/fiber.c
use strict;
my $f = shift or die "usage: darwin-fiber.pl fiber.c\n";
local $/;
open my $in, '<', $f or die "$f: $!\n";
my $s = <$in>;
close $in;
$s =~ s/\r\n/\n/g;  # TeaVM writes the host's line endings (CRLF on Windows)

my $n = 0;
$n += $s =~ s/(    static timer_t teavm_queueTimer;\n)/#ifndef __APPLE__\n$1#endif\n/;
$n += $s =~ s/(            struct sigaction sigact;\n.*?timer_create\(CLOCK_REALTIME, &sev, &teavm_queueTimer\);\n)/#ifndef __APPLE__\n$1#endif\n/s;
my $apple = <<'C';
    #elif defined(__APPLE__)
        #include <pthread.h>
        #include <errno.h>
        #include <sys/time.h>
        static pthread_mutex_t teavm_queueMutex = PTHREAD_MUTEX_INITIALIZER;
        static pthread_cond_t teavm_queueCond = PTHREAD_COND_INITIALIZER;
        static int teavm_queueInterrupted = 0;

        void teavm_waitFor(int64_t timeout) {
            struct timeval now;
            gettimeofday(&now, NULL);
            int64_t ns = (int64_t) now.tv_usec * 1000 + (timeout % 1000) * 1000000;
            struct timespec until = { .tv_sec = now.tv_sec + timeout / 1000 + ns / 1000000000, .tv_nsec = ns % 1000000000 };
            pthread_mutex_lock(&teavm_queueMutex);
            while (!teavm_queueInterrupted) {
                if (pthread_cond_timedwait(&teavm_queueCond, &teavm_queueMutex, &until) == ETIMEDOUT) break;
            }
            teavm_queueInterrupted = 0;
            pthread_mutex_unlock(&teavm_queueMutex);
        }

        void teavm_interrupt() {
            pthread_mutex_lock(&teavm_queueMutex);
            teavm_queueInterrupted = 1;
            pthread_cond_signal(&teavm_queueCond);
            pthread_mutex_unlock(&teavm_queueMutex);
        }
C
$n += $s =~ s/(    #else\n)(        void teavm_waitFor\(int64_t timeout\) \{\n            struct itimerspec)/$apple$1$2/;
$n == 3 or die "fiber.c: expected 3 patch sites, matched $n - TeaVM's fiber.c changed, check by hand\n";

open my $out, '>', $f or die "$f: $!\n";
print $out $s;
close $out;
print "patched $f for macOS (pthread condvar instead of POSIX timers)\n";
