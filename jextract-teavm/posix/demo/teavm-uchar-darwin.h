#pragma once
/* Replaces TeaVM's generated uchar.h on macOS: Apple's libc has no <uchar.h> (C11 char16_t, c16rtomb,
   mbrtoc16), which TeaVM's C backend includes on every non-PSP platform. UTF-8 <-> UTF-16, surrogates
   included; the shift state lives in a static per direction, which is enough for TeaVM's single-threaded
   console/string use. */
#include <stddef.h>
#include <stdint.h>
#include <wchar.h>

typedef uint_least16_t char16_t;
typedef uint_least32_t char32_t;

static inline size_t c16rtomb(char *s, char16_t c16, mbstate_t *ps) {
    static uint32_t high = 0;
    uint32_t cp;
    (void) ps;
    if (c16 >= 0xD800 && c16 < 0xDC00) { high = c16; return 0; }
    if (c16 >= 0xDC00 && c16 < 0xE000 && high) { cp = 0x10000 + ((high - 0xD800) << 10) + (c16 - 0xDC00); high = 0; }
    else cp = c16;
    if (!s) return 1;
    if (cp < 0x80) { s[0] = (char) cp; return 1; }
    if (cp < 0x800) { s[0] = (char) (0xC0 | cp >> 6); s[1] = (char) (0x80 | (cp & 0x3F)); return 2; }
    if (cp < 0x10000) { s[0] = (char) (0xE0 | cp >> 12); s[1] = (char) (0x80 | (cp >> 6 & 0x3F)); s[2] = (char) (0x80 | (cp & 0x3F)); return 3; }
    s[0] = (char) (0xF0 | cp >> 18); s[1] = (char) (0x80 | (cp >> 12 & 0x3F));
    s[2] = (char) (0x80 | (cp >> 6 & 0x3F)); s[3] = (char) (0x80 | (cp & 0x3F));
    return 4;
}

static inline size_t mbrtoc16(char16_t *pc16, const char *s, size_t n, mbstate_t *ps) {
    static char16_t pendingLow = 0;
    const unsigned char *u = (const unsigned char *) s;
    uint32_t cp;
    size_t len;
    (void) ps;
    if (pendingLow) { if (pc16) *pc16 = pendingLow; pendingLow = 0; return (size_t) -3; }
    if (!s || n == 0) return 0;
    if (u[0] < 0x80) { cp = u[0]; len = 1; }
    else if ((u[0] & 0xE0) == 0xC0) { cp = u[0] & 0x1F; len = 2; }
    else if ((u[0] & 0xF0) == 0xE0) { cp = u[0] & 0x0F; len = 3; }
    else if ((u[0] & 0xF8) == 0xF0) { cp = u[0] & 0x07; len = 4; }
    else return (size_t) -1;
    if (n < len) return (size_t) -2;
    for (size_t i = 1; i < len; i++) {
        if ((u[i] & 0xC0) != 0x80) return (size_t) -1;
        cp = cp << 6 | (u[i] & 0x3F);
    }
    if (cp >= 0x10000) {
        cp -= 0x10000;
        pendingLow = (char16_t) (0xDC00 | (cp & 0x3FF));
        if (pc16) *pc16 = (char16_t) (0xD800 | cp >> 10);
    } else if (pc16) *pc16 = (char16_t) cp;
    return cp == 0 ? 0 : len;
}
