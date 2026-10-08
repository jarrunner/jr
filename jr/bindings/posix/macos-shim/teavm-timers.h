/* POSIX real-time timers for macOS, which has none. TeaVM's C runtime (fiber.c, the sleep/wake
 * loop behind green threads) uses timer_create/timer_settime on SIGRTMIN and waits with
 * sigwaitinfo. build-macos.sh force-includes this file. Same reason for being C as uchar.h beside
 * it: a gap in TeaVM's runtime on macOS, not an OS API jr binds.
 *
 * Mapping: the one timer TeaVM creates becomes the process's ITIMER_REAL, its signal becomes
 * SIGALRM, and sigwaitinfo becomes sigwait. The behaviour is the same as on Linux: TeaVM blocks
 * the signal, arms the timer, and sleeps until it fires or teavm_interrupt() raises the signal
 * itself; a zero time disarms it. */
#ifndef JR_MACOS_TEAVM_TIMERS_H
#define JR_MACOS_TEAVM_TIMERS_H

#include <signal.h>
#include <sys/time.h>
#include <time.h>

#define SIGRTMIN SIGALRM

typedef int timer_t;

struct itimerspec {
    struct timespec it_interval;
    struct timespec it_value;
};

static inline int jr_timer_create(clockid_t clock, struct sigevent *sev, timer_t *timer) {
    (void) clock;
    (void) sev;
    *timer = 0;
    return 0;
}

static inline int jr_timer_settime(timer_t timer, int flags, const struct itimerspec *value, struct itimerspec *old) {
    (void) timer;
    (void) flags;
    (void) old;
    struct itimerval iv = {{0, 0}, {0, 0}};
    iv.it_value.tv_sec = value->it_value.tv_sec;
    iv.it_value.tv_usec = (suseconds_t) (value->it_value.tv_nsec / 1000);
    if (iv.it_value.tv_sec == 0 && iv.it_value.tv_usec == 0 && value->it_value.tv_nsec > 0) {
        iv.it_value.tv_usec = 1;              /* under a microsecond: fire, not disarm */
    }
    return setitimer(ITIMER_REAL, &iv, NULL);
}

static inline int jr_sigwaitinfo(const sigset_t *set, siginfo_t *info) {
    (void) info;
    int sig;
    return sigwait(set, &sig) == 0 ? sig : -1;
}

#define timer_create jr_timer_create
#define timer_settime jr_timer_settime
#define sigwaitinfo jr_sigwaitinfo

#endif
