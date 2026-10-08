/* <uchar.h> for macOS, which has none in its C SDK. TeaVM's C runtime (string.c) needs char16_t
 * and mbrtoc16/c16rtomb from it. build-macos.sh puts this directory on the include path, so
 * TeaVM's #include <uchar.h> finds this file.
 *
 * Why C and not jextract (jr's CLAUDE.md asks for that to be written down): these are not OS
 * APIs jr binds. They are gaps in TeaVM's own runtime on macOS, which the generated C calls
 * directly. The proper fix is upstream in TeaVM (PRP-15's list).
 *
 * The conversion is UTF-8 <-> UTF-16, whatever the locale (macOS locales are UTF-8). The return
 * values follow glibc, because TeaVM's loops are written against glibc: -1 error, -3 "the low half
 * of a surrogate pair, nothing consumed", 0 for NUL. Two deliberate differences: an incomplete
 * sequence returns -1, not glibc's -2, since TeaVM's loops never advance on -2 and would spin; and
 * mbrtoc16 with pc16 == NULL counts a surrogate pair as two chars (see there), which TeaVM's
 * length count needs. */
#ifndef JR_MACOS_UCHAR_H
#define JR_MACOS_UCHAR_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <wchar.h>

typedef uint_least16_t char16_t;
typedef uint_least32_t char32_t;

/* mbstate_t is a 128-byte union on macOS; the first 4 bytes hold a pending surrogate. */
static inline uint32_t jr_mbstate_get(const mbstate_t *ps) {
    uint32_t v;
    memcpy(&v, ps, sizeof v);
    return v;
}

static inline void jr_mbstate_set(mbstate_t *ps, uint32_t v) {
    memcpy(ps, &v, sizeof v);
}

static inline size_t mbrtoc16(char16_t *pc16, const char *s, size_t n, mbstate_t *ps) {
    static mbstate_t internal;
    if (ps == NULL) ps = &internal;
    uint32_t pending = jr_mbstate_get(ps);
    if (pending >= 0xDC00 && pc16 != NULL) {  /* the low surrogate left by the previous call */
        jr_mbstate_set(ps, 0);
        *pc16 = (char16_t) pending;
        return (size_t) -3;
    }
    if (s == NULL) return 0;
    if (n == 0) return (size_t) -1;
    const unsigned char *u = (const unsigned char *) s;
    uint32_t cp;
    size_t len;
    if (u[0] < 0x80)                { cp = u[0];        len = 1; }
    else if ((u[0] & 0xE0) == 0xC0) { cp = u[0] & 0x1F; len = 2; }
    else if ((u[0] & 0xF0) == 0xE0) { cp = u[0] & 0x0F; len = 3; }
    else if ((u[0] & 0xF8) == 0xF0) { cp = u[0] & 0x07; len = 4; }
    else return (size_t) -1;
    if (len > n) return (size_t) -1;
    for (size_t i = 1; i < len; i++) {
        if ((u[i] & 0xC0) != 0x80) return (size_t) -1;
        cp = (cp << 6) | (u[i] & 0x3F);
    }
    if ((len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) || (len == 4 && cp < 0x10000)
            || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
        return (size_t) -1;                   /* overlong, out of range, or a lone surrogate */
    }
    if (pc16 == NULL) {
        /* Counting only (TeaVM's teavm_c16Size). It counts results >= 0 and stops once every byte
         * is consumed, so the glibc way (-3 for the low half) would count a pair as one char, and
         * as none at the end of a string. Instead the pair is counted in two calls: the first
         * consumes nothing and returns 0, the second consumes the bytes. State 1 marks the gap. */
        if (cp >= 0x10000 && pending != 1) {
            jr_mbstate_set(ps, 1);
            return 0;
        }
        jr_mbstate_set(ps, 0);
        return cp == 0 ? 0 : len;
    }
    if (cp >= 0x10000) {
        cp -= 0x10000;
        *pc16 = (char16_t) (0xD800 + (cp >> 10));
        jr_mbstate_set(ps, 0xDC00 + (cp & 0x3FF));
    } else {
        *pc16 = (char16_t) cp;
    }
    return cp == 0 ? 0 : len;
}

static inline size_t c16rtomb(char *s, char16_t c16, mbstate_t *ps) {
    static mbstate_t internal;
    if (ps == NULL) ps = &internal;
    if (s == NULL) {                          /* reset */
        jr_mbstate_set(ps, 0);
        return 1;
    }
    uint32_t high = jr_mbstate_get(ps);
    uint32_t cp = c16;
    if (c16 >= 0xD800 && c16 <= 0xDBFF) {     /* first half of a pair: wait for the second */
        if (high != 0) return (size_t) -1;
        jr_mbstate_set(ps, c16);
        return 0;
    }
    if (c16 >= 0xDC00 && c16 <= 0xDFFF) {
        if (high == 0) return (size_t) -1;
        cp = 0x10000 + ((high - 0xD800) << 10) + (c16 - 0xDC00);
        jr_mbstate_set(ps, 0);
    } else if (high != 0) {
        return (size_t) -1;
    }
    unsigned char *o = (unsigned char *) s;
    if (cp < 0x80)    { o[0] = (unsigned char) cp; return 1; }
    if (cp < 0x800)   { o[0] = 0xC0 | (cp >> 6);  o[1] = 0x80 | (cp & 0x3F); return 2; }
    if (cp < 0x10000) { o[0] = 0xE0 | (cp >> 12); o[1] = 0x80 | ((cp >> 6) & 0x3F); o[2] = 0x80 | (cp & 0x3F); return 3; }
    o[0] = 0xF0 | (cp >> 18); o[1] = 0x80 | ((cp >> 12) & 0x3F); o[2] = 0x80 | ((cp >> 6) & 0x3F); o[3] = 0x80 | (cp & 0x3F);
    return 4;
}

#endif
