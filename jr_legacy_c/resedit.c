// Resource editing and Authenticode signing of .exe files - rcedit's job done
// inside jr. See resedit.h for the options and prp/13-prp-exe_resource_editing_and_signing.md.
//
// Everything here is Windows' own machinery: BeginUpdateResource/UpdateResource/
// EndUpdateResource (kernel32) for resources, VerQueryValue (version.dll) to read
// an existing version block, and SignerSignEx2 from mssign32.dll - the same call
// signtool.exe makes - for signing. All OS components, so no new redistributable
// dependency (the PRP-06 constraint). mssign32 has no SDK header or import
// library, so it is loaded at run time and its documented structs are declared here.

#include <windows.h>
#include <wincrypt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>

#include "resedit.h"

#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "version.lib")

// Language for resources the target does not already have. An existing resource
// keeps its own language, so it is replaced rather than joined by a second copy.
#define RE_DEFAULT_LANG MAKELANGID(LANG_ENGLISH, SUBLANG_ENGLISH_US)
#define RE_MAX_ENTRIES 256
#define RE_NAME_LEN 64

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

static void reCopy(char* dest, size_t destSize, const char* src) {
    if (!destSize) return;
    strncpy(dest, src, destSize - 1);
    dest[destSize - 1] = '\0';
}

static void reAppend(char* buf, size_t size, const char* fmt, ...) {
    size_t len = strlen(buf);
    va_list ap;
    if (len + 1 >= size) return;
    va_start(ap, fmt);
    vsnprintf(buf + len, size - len, fmt, ap);
    va_end(ap);
}

static void reWiden(const char* s, WCHAR* out, int outCount) {
    if (!MultiByteToWideChar(CP_ACP, 0, s, -1, out, outCount)) out[0] = L'\0';
}

static int reIsNumber(const char* s) {
    if (!*s) return 0;
    while (*s) { if (*s < '0' || *s > '9') return 0; s++; }
    return 1;
}

// Reads a whole file. The caller owns *outData and must free() it - the one
// ownership transfer in this file, hence the _alloc suffix.
static int reReadFileAlloc(const char* path, BYTE** outData, DWORD* outSize) {
    HANDLE h = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    LARGE_INTEGER size;
    BYTE* buf = NULL;
    DWORD got = 0;

    if (h == INVALID_HANDLE_VALUE) return 0;
    if (!GetFileSizeEx(h, &size) || size.QuadPart <= 0 || size.QuadPart > 64 * 1024 * 1024) goto fail;
    buf = (BYTE*)malloc((size_t)size.QuadPart);
    if (!buf) goto fail;
    if (!ReadFile(h, buf, (DWORD)size.QuadPart, &got, NULL) || got != (DWORD)size.QuadPart) goto fail;

    CloseHandle(h);
    *outData = buf;
    *outSize = got;
    return 1;

fail:
    free(buf);
    CloseHandle(h);
    return 0;
}

// "1.2.3.4" (or fewer parts) -> VS_FIXEDFILEINFO's MS/LS pair
static int reParseVersion(const char* text, DWORD* ms, DWORD* ls) {
    unsigned long part[4] = {0, 0, 0, 0};
    const char* c = text;
    int n = 0;

    while (n < 4) {
        char* end;
        if (*c < '0' || *c > '9') return 0;
        part[n] = strtoul(c, &end, 10);
        if (part[n] > 65535) return 0;
        n++;
        c = end;
        if (*c == '.') c++;
        else break;
    }
    if (*c) return 0;

    *ms = (part[0] << 16) | part[1];
    *ls = (part[2] << 16) | part[3];
    return 1;
}

// ---------------------------------------------------------------------------
// Option parsing
// ---------------------------------------------------------------------------

int reParseOption(ReStamp* s, const char* opt, char* err, size_t errSize) {
    const char* eq = strchr(opt, '=');
    const char* value = eq ? eq + 1 : "";
    size_t keyLen = eq ? (size_t)(eq - opt) : strlen(opt);
    char key[160];
    size_t i;

    struct { const char* key; char* field; size_t size; } simple[] = {
        {"make", s->make, sizeof(s->make)},
        {"edit", s->edit, sizeof(s->edit)},
        {"list-resources", s->listResources, sizeof(s->listResources)},
        {"icon", s->icon, sizeof(s->icon)},
        {"file-version", s->fileVersion, sizeof(s->fileVersion)},
        {"product-version", s->productVersion, sizeof(s->productVersion)},
        {"manifest", s->manifest, sizeof(s->manifest)},
        {"execution-level", s->executionLevel, sizeof(s->executionLevel)},
        {"sign", s->signPfx, sizeof(s->signPfx)},
        {"sign.thumbprint", s->signThumbprint, sizeof(s->signThumbprint)},
        {"sign.timestamp", s->signTimestamp, sizeof(s->signTimestamp)},
    };

    if (keyLen >= sizeof(key)) return 0;
    memcpy(key, opt, keyLen);
    key[keyLen] = '\0';

    for (i = 0; i < sizeof(simple) / sizeof(simple[0]); i++) {
        if (_stricmp(key, simple[i].key) == 0) {
            if (!*value) {
                snprintf(err, errSize, "-Xjr:%s needs a value: -Xjr:%s=...", key, key);
                return -1;
            }
            reCopy(simple[i].field, simple[i].size, value);
            if (simple[i].field == s->executionLevel &&
                _stricmp(value, "asInvoker") != 0 && _stricmp(value, "highestAvailable") != 0 &&
                _stricmp(value, "requireAdministrator") != 0) {
                snprintf(err, errSize, "-Xjr:execution-level must be asInvoker, highestAvailable or requireAdministrator");
                return -1;
            }
            return 1;
        }
    }

    if (_stricmp(key, "version") == 0) {
        if (!*value) { snprintf(err, errSize, "-Xjr:version needs a value, e.g. -Xjr:version=1.2.3.4"); return -1; }
        reCopy(s->fileVersion, sizeof(s->fileVersion), value);
        reCopy(s->productVersion, sizeof(s->productVersion), value);
        return 1;
    }

    if (_strnicmp(key, "version.", 8) == 0 && key[8]) {
        ReVersionString* v;
        if (s->versionStringCount >= RE_MAX_ITEMS) { snprintf(err, errSize, "Too many -Xjr:version.* options"); return -1; }
        v = &s->versionStrings[s->versionStringCount++];
        reCopy(v->name, sizeof(v->name), key + 8);
        reCopy(v->value, sizeof(v->value), value);
        return 1;
    }

    if (_strnicmp(key, "string.", 7) == 0) {
        ReString* str;
        if (!reIsNumber(key + 7) || strtoul(key + 7, NULL, 10) > 65535) {
            snprintf(err, errSize, "-Xjr:string.<id> needs a numeric id from 0 to 65535, e.g. -Xjr:string.101=Hello");
            return -1;
        }
        if (s->stringCount >= RE_MAX_ITEMS) { snprintf(err, errSize, "Too many -Xjr:string.* options"); return -1; }
        str = &s->strings[s->stringCount++];
        str->id = (unsigned int)strtoul(key + 7, NULL, 10);
        reCopy(str->text, sizeof(str->text), value);
        return 1;
    }

    if (_strnicmp(key, "resource.", 9) == 0) {
        const char* type = key + 9;
        const char* dot = strchr(type, '.');
        ReRaw* raw;
        size_t typeLen = dot ? (size_t)(dot - type) : 0;
        if (!dot || typeLen == 0 || !dot[1] || !*value) {
            snprintf(err, errSize, "Use -Xjr:resource.<type>.<name>=<file>, e.g. -Xjr:resource.RCDATA.CONFIG=app.json");
            return -1;
        }
        if (typeLen >= sizeof(raw->type)) { snprintf(err, errSize, "Resource type name too long"); return -1; }
        if (s->rawCount >= RE_MAX_ITEMS) { snprintf(err, errSize, "Too many -Xjr:resource.* options"); return -1; }
        raw = &s->raws[s->rawCount++];
        memcpy(raw->type, type, typeLen);
        raw->type[typeLen] = '\0';
        reCopy(raw->name, sizeof(raw->name), dot + 1);
        reCopy(raw->file, sizeof(raw->file), value);
        return 1;
    }

    return 0;
}

static int reHasResourceEdits(const ReStamp* s) {
    return s->icon[0] || s->fileVersion[0] || s->productVersion[0] || s->versionStringCount ||
           s->manifest[0] || s->executionLevel[0] || s->stringCount || s->rawCount;
}

static int reHasSigning(const ReStamp* s) {
    return s->signPfx[0] || s->signThumbprint[0];
}

int reHasAction(const ReStamp* s) {
    return s->make[0] || s->edit[0] || s->listResources[0];
}

int reHasEdits(const ReStamp* s) {
    return reHasResourceEdits(s) || reHasSigning(s) || s->signTimestamp[0];
}

// ---------------------------------------------------------------------------
// The pending update: a list of (type, name, language) entries to write, or to
// delete when data is NULL. Existing resources are read from the target first
// (loaded as a data file), the module is released, and only then is the file
// opened for update - EndUpdateResource cannot rewrite a file that is mapped.
// ---------------------------------------------------------------------------

typedef struct {
    WORD typeId; WCHAR typeStr[RE_NAME_LEN];
    WORD nameId; WCHAR nameStr[RE_NAME_LEN];
    WORD lang;
    BYTE* data;   // owned; NULL = delete this resource
    DWORD size;
} ReEntry;

typedef struct {
    ReEntry e[RE_MAX_ENTRIES];
    int count;
} ReEntries;

static void reSetKey(LPCWSTR p, WORD* id, WCHAR* str) {
    if (IS_INTRESOURCE(p)) {
        *id = (WORD)(ULONG_PTR)p;
        str[0] = L'\0';
    } else {
        *id = 0;
        wcsncpy(str, p, RE_NAME_LEN - 1);
        str[RE_NAME_LEN - 1] = L'\0';
    }
}

static LPCWSTR reTypeOf(const ReEntry* e) { return e->typeStr[0] ? e->typeStr : MAKEINTRESOURCEW(e->typeId); }
static LPCWSTR reNameOf(const ReEntry* e) { return e->nameStr[0] ? e->nameStr : MAKEINTRESOURCEW(e->nameId); }

// Takes ownership of data, even on failure
static int reAdd(ReEntries* list, LPCWSTR type, LPCWSTR name, WORD lang, BYTE* data, DWORD size) {
    ReEntry* e;
    if (list->count >= RE_MAX_ENTRIES) { free(data); return 0; }
    e = &list->e[list->count++];
    reSetKey(type, &e->typeId, e->typeStr);
    reSetKey(name, &e->nameId, e->nameStr);
    e->lang = lang;
    e->data = data;
    e->size = size;
    return 1;
}

static void reFreeEntries(ReEntries* list) {
    int i;
    if (!list) return;
    for (i = 0; i < list->count; i++) free(list->e[i].data);
    free(list);
}

typedef struct { WORD langs[16]; int count; } ReLangs;

static BOOL CALLBACK reLangCb(HMODULE m, LPCWSTR type, LPCWSTR name, WORD lang, LONG_PTR param) {
    ReLangs* l = (ReLangs*)param;
    (void)m; (void)type; (void)name;
    if (l->count < 16) l->langs[l->count++] = lang;
    return TRUE;
}

static void reGetLangs(HMODULE m, LPCWSTR type, LPCWSTR name, ReLangs* out) {
    out->count = 0;
    if (m) EnumResourceLanguagesW(m, type, name, reLangCb, (LONG_PTR)out);
}

// Queue deletion of every language of (type, name), and report which language
// the replacement should use: the existing one if there was one.
static int reQueueReplace(HMODULE m, ReEntries* list, LPCWSTR type, LPCWSTR name, WORD* outLang) {
    ReLangs l;
    int i;
    reGetLangs(m, type, name, &l);
    for (i = 0; i < l.count; i++) {
        if (!reAdd(list, type, name, l.langs[i], NULL, 0)) return 0;
    }
    if (outLang) *outLang = l.count ? l.langs[0] : RE_DEFAULT_LANG;
    return 1;
}

// Existing resource bytes; valid until the module is freed
static const BYTE* reFind(HMODULE m, LPCWSTR type, LPCWSTR name, WORD lang, DWORD* size) {
    HRSRC r;
    HGLOBAL g;
    if (!m) return NULL;
    r = FindResourceExW(m, type, name, lang);
    if (!r) return NULL;
    g = LoadResource(m, r);
    if (!g) return NULL;
    *size = SizeofResource(m, r);
    return (const BYTE*)LockResource(g);
}

// ---------------------------------------------------------------------------
// (i) Icon: an .ico file is a directory plus images; in an exe the images become
// RT_ICON resources and the directory an RT_GROUP_ICON that points at them by id.
// The FIRST group is the one Explorer and the taskbar show, so that is the one
// replaced - under its own name, its old images deleted with it.
// ---------------------------------------------------------------------------

#pragma pack(push, 2)
typedef struct { BYTE width, height, colors, reserved; WORD planes, bitCount; DWORD bytes; WORD id; } ReGroupIconEntry;
#pragma pack(pop)

typedef struct { BYTE width, height, colors, reserved; WORD planes, bitCount; DWORD bytes; DWORD offset; } ReIcoFileEntry;

typedef struct { WORD id; WCHAR name[RE_NAME_LEN]; int found; } ReFirstName;

static BOOL CALLBACK reFirstNameCb(HMODULE m, LPCWSTR type, LPWSTR name, LONG_PTR param) {
    ReFirstName* f = (ReFirstName*)param;
    (void)m; (void)type;
    reSetKey(name, &f->id, f->name);
    f->found = 1;
    return FALSE;  // the first one is all we want
}

static BOOL CALLBACK reMaxIdCb(HMODULE m, LPCWSTR type, LPWSTR name, LONG_PTR param) {
    WORD* maxId = (WORD*)param;
    (void)m; (void)type;
    if (IS_INTRESOURCE(name) && (WORD)(ULONG_PTR)name > *maxId) *maxId = (WORD)(ULONG_PTR)name;
    return TRUE;
}

static int reQueueIcon(HMODULE m, ReEntries* list, const char* icoPath, char* report, size_t reportSize,
                       char* err, size_t errSize) {
    BYTE* ico = NULL;
    DWORD icoSize = 0;
    BYTE* group = NULL;
    DWORD groupSize;
    WORD count, i, maxId = 0, lang = RE_DEFAULT_LANG;
    ReFirstName first;
    LPCWSTR groupName = MAKEINTRESOURCEW(1);
    int ok = 0;

    memset(&first, 0, sizeof(first));

    if (!reReadFileAlloc(icoPath, &ico, &icoSize)) {
        snprintf(err, errSize, "Cannot read icon file: %s", icoPath);
        goto cleanup;
    }
    if (icoSize < 6) {
        snprintf(err, errSize, "Not a valid .ico file: %s", icoPath);
        goto cleanup;
    }
    memcpy(&count, ico + 4, sizeof(count));
    if (((WORD*)ico)[0] != 0 || ((WORD*)ico)[1] != 1 || count == 0 || 6 + (DWORD)count * 16 > icoSize) {
        snprintf(err, errSize, "Not a valid .ico file: %s", icoPath);
        goto cleanup;
    }

    // Retire the current main icon: its group and every image it points at
    if (m) EnumResourceNamesW(m, (LPCWSTR)RT_GROUP_ICON, reFirstNameCb, (LONG_PTR)&first);
    if (first.found) {
        ReLangs langs;
        int li;
        groupName = first.name[0] ? first.name : MAKEINTRESOURCEW(first.id);
        reGetLangs(m, (LPCWSTR)RT_GROUP_ICON, groupName, &langs);
        for (li = 0; li < langs.count; li++) {
            DWORD oldSize = 0;
            const BYTE* old = reFind(m, (LPCWSTR)RT_GROUP_ICON, groupName, langs.langs[li], &oldSize);
            if (old && oldSize >= 6) {
                WORD oldCount, k;
                memcpy(&oldCount, old + 4, sizeof(oldCount));
                for (k = 0; k < oldCount && 6 + (DWORD)(k + 1) * sizeof(ReGroupIconEntry) <= oldSize; k++) {
                    ReGroupIconEntry ge;
                    memcpy(&ge, old + 6 + k * sizeof(ReGroupIconEntry), sizeof(ge));
                    if (!reQueueReplace(m, list, (LPCWSTR)RT_ICON, MAKEINTRESOURCEW(ge.id), NULL)) goto full;
                }
            }
        }
        if (!reQueueReplace(m, list, (LPCWSTR)RT_GROUP_ICON, groupName, &lang)) goto full;
    }

    // New image ids above every id in the file, so nothing collides
    if (m) EnumResourceNamesW(m, (LPCWSTR)RT_ICON, reMaxIdCb, (LONG_PTR)&maxId);
    if ((DWORD)maxId + count > 65535) {
        snprintf(err, errSize, "No free icon resource ids left in the target");
        goto cleanup;
    }

    groupSize = 6 + count * (DWORD)sizeof(ReGroupIconEntry);
    group = (BYTE*)malloc(groupSize);
    if (!group) { snprintf(err, errSize, "Out of memory"); goto cleanup; }
    memcpy(group, ico, 6);  // reserved, type=1, count

    for (i = 0; i < count; i++) {
        ReIcoFileEntry fe;
        ReGroupIconEntry ge;
        BYTE* image;
        memcpy(&fe, ico + 6 + i * 16, sizeof(fe));
        if (fe.offset > icoSize || fe.bytes > icoSize - fe.offset || fe.bytes == 0) {
            snprintf(err, errSize, "Icon file is truncated or corrupt: %s", icoPath);
            goto cleanup;
        }
        image = (BYTE*)malloc(fe.bytes);
        if (!image) { snprintf(err, errSize, "Out of memory"); goto cleanup; }
        memcpy(image, ico + fe.offset, fe.bytes);
        if (!reAdd(list, (LPCWSTR)RT_ICON, MAKEINTRESOURCEW(maxId + 1 + i), lang, image, fe.bytes)) goto full;

        ge.width = fe.width; ge.height = fe.height; ge.colors = fe.colors; ge.reserved = 0;
        ge.planes = fe.planes; ge.bitCount = fe.bitCount; ge.bytes = fe.bytes; ge.id = (WORD)(maxId + 1 + i);
        memcpy(group + 6 + i * sizeof(ReGroupIconEntry), &ge, sizeof(ge));
    }

    ok = reAdd(list, (LPCWSTR)RT_GROUP_ICON, groupName, lang, group, groupSize);
    group = NULL;  // owned by the list now, success or not
    if (!ok) goto full;

    reAppend(report, reportSize, "Icon: %s (%u image%s)\n", icoPath, count, count == 1 ? "" : "s");
    goto cleanup;

full:
    ok = 0;
    snprintf(err, errSize, "Too many resource changes in one run");
cleanup:
    free(group);
    free(ico);
    return ok;
}

// ---------------------------------------------------------------------------
// (ii) Version information. The VS_VERSIONINFO block is rebuilt: the standard
// strings and fixed versions already in the target are carried over, then the
// requested ones are applied on top. (A non-standard string name already in the
// target is not carried over - VerQueryValue can only look names up, not list them.)
// ---------------------------------------------------------------------------

typedef struct { BYTE* p; size_t len, cap; int failed; } ReBuf;

static void reBufPut(ReBuf* b, const void* data, size_t n) {
    if (b->failed) return;
    if (b->len + n > b->cap) {
        size_t cap = b->cap ? b->cap * 2 : 1024;
        BYTE* p;
        while (cap < b->len + n) cap *= 2;
        p = (BYTE*)realloc(b->p, cap);
        if (!p) { b->failed = 1; return; }
        b->p = p;
        b->cap = cap;
    }
    if (data) memcpy(b->p + b->len, data, n);
    else memset(b->p + b->len, 0, n);
    b->len += n;
}

static void reBufAlign(ReBuf* b) {
    static const BYTE zero[3] = {0, 0, 0};
    if (b->len % 4) reBufPut(b, zero, 4 - b->len % 4);
}

// A version-block node: wLength, wValueLength, wType, key, padding, value.
// valueLen is in WCHARs for text (type 1) and bytes for binary (type 0).
static size_t reNodeBegin(ReBuf* b, const WCHAR* key, const void* value, WORD valueLen, size_t valueBytes, WORD type) {
    WORD header[3];
    size_t start;
    reBufAlign(b);
    start = b->len;
    header[0] = 0;
    header[1] = valueLen;
    header[2] = type;
    reBufPut(b, header, sizeof(header));
    reBufPut(b, key, (wcslen(key) + 1) * sizeof(WCHAR));
    reBufAlign(b);
    if (valueBytes) reBufPut(b, value, valueBytes);
    return start;
}

static void reNodeEnd(ReBuf* b, size_t start) {
    WORD len;
    if (b->failed) return;
    len = (WORD)(b->len - start);
    memcpy(b->p + start, &len, sizeof(len));
}

static const WCHAR* RE_STD_STRINGS[] = {
    L"Comments", L"CompanyName", L"FileDescription", L"FileVersion", L"InternalName", L"LegalCopyright",
    L"LegalTrademarks", L"OriginalFilename", L"PrivateBuild", L"ProductName", L"ProductVersion", L"SpecialBuild",
};

#define RE_MAX_VSTRINGS (12 + RE_MAX_ITEMS)
typedef struct { WCHAR name[RE_NAME_LEN]; WCHAR value[512]; } ReVString;
typedef struct { ReVString s[RE_MAX_VSTRINGS]; int count; } ReVStrings;

static void reVSet(ReVStrings* vs, const WCHAR* name, const WCHAR* value) {
    int i;
    for (i = 0; i < vs->count; i++) {
        if (_wcsicmp(vs->s[i].name, name) == 0) break;
    }
    if (i == vs->count) {
        if (vs->count >= RE_MAX_VSTRINGS) return;
        vs->count++;
        wcsncpy(vs->s[i].name, name, RE_NAME_LEN - 1);
        vs->s[i].name[RE_NAME_LEN - 1] = L'\0';
    }
    wcsncpy(vs->s[i].value, value, 511);
    vs->s[i].value[511] = L'\0';
}

static int reQueueVersion(HMODULE m, ReEntries* list, const ReStamp* s, char* report, size_t reportSize,
                          char* err, size_t errSize) {
    VS_FIXEDFILEINFO ffi;
    WORD translation[2] = {RE_DEFAULT_LANG, 1200};  // en-US, Unicode
    WORD lang = RE_DEFAULT_LANG;
    ReVStrings* vs = NULL;
    BYTE* existing = NULL;
    ReBuf b;
    WCHAR tableKey[16];
    size_t root, sfi, table, vfi, var, node;
    int i, ok = 0;

    memset(&b, 0, sizeof(b));
    memset(&ffi, 0, sizeof(ffi));
    ffi.dwSignature = 0xFEEF04BD;
    ffi.dwStrucVersion = 0x00010000;
    ffi.dwFileFlagsMask = VS_FFI_FILEFLAGSMASK;
    ffi.dwFileOS = VOS_NT_WINDOWS32;
    ffi.dwFileType = VFT_APP;

    vs = (ReVStrings*)calloc(1, sizeof(ReVStrings));
    if (!vs) { snprintf(err, errSize, "Out of memory"); goto cleanup; }

    if (!reQueueReplace(m, list, (LPCWSTR)RT_VERSION, MAKEINTRESOURCEW(VS_VERSION_INFO), &lang)) {
        snprintf(err, errSize, "Too many resource changes in one run");
        goto cleanup;
    }

    // Carry over what the target already has
    {
        DWORD size = 0;
        const BYTE* old = reFind(m, (LPCWSTR)RT_VERSION, MAKEINTRESOURCEW(VS_VERSION_INFO), lang, &size);
        if (old && size) {
            void* value;
            UINT valueLen;
            existing = (BYTE*)malloc(size);
            if (existing) {
                memcpy(existing, old, size);
                if (VerQueryValueW(existing, L"\\", &value, &valueLen) && valueLen >= sizeof(VS_FIXEDFILEINFO)) {
                    memcpy(&ffi, value, sizeof(ffi));
                }
                if (VerQueryValueW(existing, L"\\VarFileInfo\\Translation", &value, &valueLen) && valueLen >= 4) {
                    memcpy(translation, value, sizeof(translation));
                }
                for (i = 0; i < (int)(sizeof(RE_STD_STRINGS) / sizeof(RE_STD_STRINGS[0])); i++) {
                    WCHAR path[128];
                    _snwprintf(path, 127, L"\\StringFileInfo\\%04x%04x\\%s", translation[0], translation[1], RE_STD_STRINGS[i]);
                    path[127] = L'\0';
                    if (VerQueryValueW(existing, path, &value, &valueLen) && valueLen > 0) {
                        reVSet(vs, RE_STD_STRINGS[i], (const WCHAR*)value);
                    }
                }
            }
        }
    }

    // Apply the requested changes
    if (s->fileVersion[0]) {
        WCHAR w[64];
        if (!reParseVersion(s->fileVersion, &ffi.dwFileVersionMS, &ffi.dwFileVersionLS)) {
            snprintf(err, errSize, "Not a version number (a.b.c.d): %s", s->fileVersion);
            goto cleanup;
        }
        reWiden(s->fileVersion, w, 64);
        reVSet(vs, L"FileVersion", w);
    }
    if (s->productVersion[0]) {
        WCHAR w[64];
        if (!reParseVersion(s->productVersion, &ffi.dwProductVersionMS, &ffi.dwProductVersionLS)) {
            snprintf(err, errSize, "Not a version number (a.b.c.d): %s", s->productVersion);
            goto cleanup;
        }
        reWiden(s->productVersion, w, 64);
        reVSet(vs, L"ProductVersion", w);
    }
    for (i = 0; i < s->versionStringCount; i++) {
        WCHAR name[RE_NAME_LEN], value[512];
        reWiden(s->versionStrings[i].name, name, RE_NAME_LEN);
        reWiden(s->versionStrings[i].value, value, 512);
        reVSet(vs, name, value);
    }

    // Build the block
    _snwprintf(tableKey, 15, L"%04X%04X", translation[0], translation[1]);
    tableKey[15] = L'\0';

    root = reNodeBegin(&b, L"VS_VERSION_INFO", &ffi, (WORD)sizeof(ffi), sizeof(ffi), 0);
    sfi = reNodeBegin(&b, L"StringFileInfo", NULL, 0, 0, 1);
    table = reNodeBegin(&b, tableKey, NULL, 0, 0, 1);
    for (i = 0; i < vs->count; i++) {
        size_t len = wcslen(vs->s[i].value) + 1;
        node = reNodeBegin(&b, vs->s[i].name, vs->s[i].value, (WORD)len, len * sizeof(WCHAR), 1);
        reNodeEnd(&b, node);
    }
    reNodeEnd(&b, table);
    reNodeEnd(&b, sfi);
    vfi = reNodeBegin(&b, L"VarFileInfo", NULL, 0, 0, 1);
    var = reNodeBegin(&b, L"Translation", translation, (WORD)sizeof(translation), sizeof(translation), 0);
    reNodeEnd(&b, var);
    reNodeEnd(&b, vfi);
    reNodeEnd(&b, root);

    if (b.failed || b.len > 65535) { snprintf(err, errSize, "Version information too large"); goto cleanup; }

    ok = reAdd(list, (LPCWSTR)RT_VERSION, MAKEINTRESOURCEW(VS_VERSION_INFO), lang, b.p, (DWORD)b.len);
    b.p = NULL;  // owned by the list now
    if (!ok) { snprintf(err, errSize, "Too many resource changes in one run"); goto cleanup; }

    reAppend(report, reportSize, "Version information:");
    if (s->fileVersion[0]) reAppend(report, reportSize, " file %s", s->fileVersion);
    if (s->productVersion[0] && strcmp(s->productVersion, s->fileVersion) != 0) reAppend(report, reportSize, " product %s", s->productVersion);
    for (i = 0; i < s->versionStringCount; i++) {
        reAppend(report, reportSize, "%s %s=\"%s\"", (i || s->fileVersion[0] || s->productVersion[0]) ? "," : "",
                 s->versionStrings[i].name, s->versionStrings[i].value);
    }
    reAppend(report, reportSize, "\n");

cleanup:
    free(b.p);
    free(existing);
    free(vs);
    return ok;
}

// ---------------------------------------------------------------------------
// (ii) Manifest and requested execution level. The level is changed inside
// whichever manifest applies (a given file, else the target's own), and a
// trustInfo block is added if that manifest has none; with neither, a minimal
// manifest is generated.
// ---------------------------------------------------------------------------

static const char RE_TRUST_INFO[] =
    "  <trustInfo xmlns=\"urn:schemas-microsoft-com:asm.v3\">\r\n"
    "    <security>\r\n"
    "      <requestedPrivileges>\r\n"
    "        <requestedExecutionLevel level=\"%s\" uiAccess=\"false\"/>\r\n"
    "      </requestedPrivileges>\r\n"
    "    </security>\r\n"
    "  </trustInfo>\r\n";

static int reQueueManifest(HMODULE m, ReEntries* list, const ReStamp* s, char* report, size_t reportSize,
                           char* err, size_t errSize) {
    BYTE* base = NULL;     // NUL-terminated manifest text
    DWORD baseSize = 0;
    BYTE* out = NULL;
    size_t outCap;
    WORD lang = RE_DEFAULT_LANG;
    const char* source;
    int ok = 0;

    if (!reQueueReplace(m, list, (LPCWSTR)RT_MANIFEST, MAKEINTRESOURCEW(1), &lang)) {
        snprintf(err, errSize, "Too many resource changes in one run");
        goto cleanup;
    }

    if (s->manifest[0]) {
        BYTE* file = NULL;
        if (!reReadFileAlloc(s->manifest, &file, &baseSize)) {
            snprintf(err, errSize, "Cannot read manifest file: %s", s->manifest);
            goto cleanup;
        }
        base = (BYTE*)realloc(file, baseSize + 1);
        if (!base) { free(file); snprintf(err, errSize, "Out of memory"); goto cleanup; }
        source = s->manifest;
    } else {
        DWORD size = 0;
        const BYTE* old = reFind(m, (LPCWSTR)RT_MANIFEST, MAKEINTRESOURCEW(1), lang, &size);
        if (old && size) {
            base = (BYTE*)malloc(size + 1);
            if (!base) { snprintf(err, errSize, "Out of memory"); goto cleanup; }
            memcpy(base, old, size);
            baseSize = size;
            source = "the existing manifest";
        } else {
            static const char generated[] =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                "<assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\">\r\n"
                "</assembly>\r\n";
            baseSize = (DWORD)strlen(generated);
            base = (BYTE*)malloc(baseSize + 1);
            if (!base) { snprintf(err, errSize, "Out of memory"); goto cleanup; }
            memcpy(base, generated, baseSize);
            source = "a generated manifest";
        }
    }
    base[baseSize] = '\0';

    outCap = baseSize + sizeof(RE_TRUST_INFO) + 64;
    out = (BYTE*)malloc(outCap);
    if (!out) { snprintf(err, errSize, "Out of memory"); goto cleanup; }

    if (!s->executionLevel[0]) {
        memcpy(out, base, baseSize);
        outCap = baseSize;
    } else {
        char* text = (char*)base;
        char* rel = strstr(text, "requestedExecutionLevel");
        char* level = rel ? strstr(rel, "level=\"") : NULL;
        char* tagEnd = rel ? strchr(rel, '>') : NULL;
        if (level && (!tagEnd || level < tagEnd)) {
            // Replace the value inside level="..."
            char* valueStart = level + 7;
            char* valueEnd = strchr(valueStart, '"');
            if (!valueEnd) { snprintf(err, errSize, "Malformed requestedExecutionLevel in %s", source); goto cleanup; }
            outCap = (size_t)snprintf((char*)out, outCap, "%.*s%s%s",
                                      (int)(valueStart - text), text, s->executionLevel, valueEnd);
        } else {
            // Insert a trustInfo block before </assembly>
            char* close = strstr(text, "</assembly>");
            char block[sizeof(RE_TRUST_INFO) + 32];
            if (!close) { snprintf(err, errSize, "No </assembly> in %s", source); goto cleanup; }
            snprintf(block, sizeof(block), RE_TRUST_INFO, s->executionLevel);
            outCap = (size_t)snprintf((char*)out, outCap, "%.*s%s%s", (int)(close - text), text, block, close);
        }
    }

    ok = reAdd(list, (LPCWSTR)RT_MANIFEST, MAKEINTRESOURCEW(1), lang, out, (DWORD)outCap);
    out = NULL;  // owned by the list now
    if (!ok) { snprintf(err, errSize, "Too many resource changes in one run"); goto cleanup; }

    if (s->manifest[0]) reAppend(report, reportSize, "Manifest: %s\n", s->manifest);
    if (s->executionLevel[0]) reAppend(report, reportSize, "Execution level: %s\n", s->executionLevel);

cleanup:
    free(out);
    free(base);
    return ok;
}

// ---------------------------------------------------------------------------
// (ii) String table. Strings live in blocks of 16 (block = id/16 + 1), each entry
// a length-prefixed UTF-16 string, so setting one string means rewriting its
// block with the other fifteen carried over.
// ---------------------------------------------------------------------------

#define RE_STRING_MAX 1024

static int reQueueStrings(HMODULE m, ReEntries* list, const ReStamp* s, char* report, size_t reportSize,
                          char* err, size_t errSize) {
    WCHAR (*texts)[RE_STRING_MAX] = NULL;
    int done[RE_MAX_ITEMS] = {0};
    int i, j, ok = 0;

    texts = (WCHAR (*)[RE_STRING_MAX])malloc(16 * sizeof(*texts));
    if (!texts) { snprintf(err, errSize, "Out of memory"); return 0; }

    for (i = 0; i < s->stringCount; i++) {
        WORD block = (WORD)(s->strings[i].id / 16 + 1);
        WORD lang;
        DWORD oldSize = 0, outSize = 0;
        const BYTE* old;
        BYTE* out;
        BYTE* p;

        if (done[i]) continue;
        memset(texts, 0, 16 * sizeof(*texts));

        if (!reQueueReplace(m, list, (LPCWSTR)RT_STRING, MAKEINTRESOURCEW(block), &lang)) goto full;

        // Carry over the block's other strings
        old = reFind(m, (LPCWSTR)RT_STRING, MAKEINTRESOURCEW(block), lang, &oldSize);
        if (old) {
            const BYTE* q = old;
            for (j = 0; j < 16 && q + 2 <= old + oldSize; j++) {
                WORD len;
                memcpy(&len, q, 2);
                q += 2;
                if (q + len * 2 > old + oldSize) break;
                if (len >= RE_STRING_MAX) len = RE_STRING_MAX - 1;
                memcpy(texts[j], q, len * 2);
                texts[j][len] = L'\0';
                q += len * 2;
            }
        }

        // This block's requested strings (the last one given wins)
        for (j = i; j < s->stringCount; j++) {
            if (s->strings[j].id / 16 + 1 == block) {
                reWiden(s->strings[j].text, texts[s->strings[j].id % 16], RE_STRING_MAX);
                done[j] = 1;
            }
        }

        for (j = 0; j < 16; j++) outSize += 2 + (DWORD)wcslen(texts[j]) * 2;
        out = (BYTE*)malloc(outSize);
        if (!out) { snprintf(err, errSize, "Out of memory"); goto cleanup; }
        p = out;
        for (j = 0; j < 16; j++) {
            WORD len = (WORD)wcslen(texts[j]);
            memcpy(p, &len, 2);
            memcpy(p + 2, texts[j], len * 2);
            p += 2 + len * 2;
        }
        if (!reAdd(list, (LPCWSTR)RT_STRING, MAKEINTRESOURCEW(block), lang, out, outSize)) goto full;
    }

    for (i = 0; i < s->stringCount; i++) {
        reAppend(report, reportSize, "String %u: \"%s\"\n", s->strings[i].id, s->strings[i].text);
    }
    ok = 1;
    goto cleanup;

full:
    snprintf(err, errSize, "Too many resource changes in one run");
cleanup:
    free(texts);
    return ok;
}

// ---------------------------------------------------------------------------
// (ii) Any other resource, raw from a file
// ---------------------------------------------------------------------------

// Numeric -> MAKEINTRESOURCE; a few well-known type names -> their ids;
// anything else -> an upper-cased name (resource names are case-insensitive
// and the resource compiler stores them upper-cased).
static LPCWSTR reParseResId(const char* text, int isType, WCHAR* buf) {
    if (reIsNumber(text)) return MAKEINTRESOURCEW((WORD)strtoul(text, NULL, 10));
    if (isType) {
        if (_stricmp(text, "RCDATA") == 0) return (LPCWSTR)RT_RCDATA;
        if (_stricmp(text, "HTML") == 0) return (LPCWSTR)RT_HTML;
        if (_stricmp(text, "MANIFEST") == 0) return (LPCWSTR)RT_MANIFEST;
    }
    reWiden(text, buf, RE_NAME_LEN);
    CharUpperW(buf);
    return buf;
}

static int reQueueRaw(HMODULE m, ReEntries* list, const ReRaw* raw, char* report, size_t reportSize,
                      char* err, size_t errSize) {
    WCHAR typeBuf[RE_NAME_LEN], nameBuf[RE_NAME_LEN];
    LPCWSTR type = reParseResId(raw->type, 1, typeBuf);
    LPCWSTR name = reParseResId(raw->name, 0, nameBuf);
    BYTE* data = NULL;
    DWORD size = 0;
    WORD lang;

    if (!reReadFileAlloc(raw->file, &data, &size)) {
        snprintf(err, errSize, "Cannot read resource file: %s", raw->file);
        return 0;
    }
    if (!reQueueReplace(m, list, type, name, &lang)) {
        free(data);
        snprintf(err, errSize, "Too many resource changes in one run");
        return 0;
    }
    if (!reAdd(list, type, name, lang, data, size)) {  // frees data itself on failure
        snprintf(err, errSize, "Too many resource changes in one run");
        return 0;
    }
    reAppend(report, reportSize, "Resource %s/%s: %s (%lu bytes)\n", raw->type, raw->name, raw->file, (unsigned long)size);
    return 1;
}

// ---------------------------------------------------------------------------
// Apply every queued resource change in one update
// ---------------------------------------------------------------------------

static int reApplyResources(const WCHAR* target, const ReStamp* s, char* report, size_t reportSize,
                            char* err, size_t errSize) {
    ReEntries* list = NULL;
    HMODULE m = NULL;
    HANDLE h = NULL;
    int i, ok = 0;

    list = (ReEntries*)calloc(1, sizeof(ReEntries));
    if (!list) { snprintf(err, errSize, "Out of memory"); goto cleanup; }

    // A file with no resource section yet loads fine; FindResource just finds nothing
    m = LoadLibraryExW(target, NULL, LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE);

    if (s->icon[0] && !reQueueIcon(m, list, s->icon, report, reportSize, err, errSize)) goto cleanup;
    if ((s->fileVersion[0] || s->productVersion[0] || s->versionStringCount) &&
        !reQueueVersion(m, list, s, report, reportSize, err, errSize)) goto cleanup;
    if ((s->manifest[0] || s->executionLevel[0]) && !reQueueManifest(m, list, s, report, reportSize, err, errSize)) goto cleanup;
    if (s->stringCount && !reQueueStrings(m, list, s, report, reportSize, err, errSize)) goto cleanup;
    for (i = 0; i < s->rawCount; i++) {
        if (!reQueueRaw(m, list, &s->raws[i], report, reportSize, err, errSize)) goto cleanup;
    }

    if (m) { FreeLibrary(m); m = NULL; }

    h = BeginUpdateResourceW(target, FALSE);
    if (!h) {
        snprintf(err, errSize, "Cannot open the exe for resource update (error %lu) - is it running?", GetLastError());
        goto cleanup;
    }
    for (i = 0; i < list->count; i++) {
        ReEntry* e = &list->e[i];
        BOOL r = UpdateResourceW(h, reTypeOf(e), reNameOf(e), e->lang, e->data, e->size);
        // A delete may name something an earlier delete already removed (two icon
        // groups sharing an image), so only a failed write is an error
        if (!r && e->data) {
            snprintf(err, errSize, "Resource update failed (error %lu)", GetLastError());
            EndUpdateResourceW(h, TRUE);
            h = NULL;
            goto cleanup;
        }
    }
    if (!EndUpdateResourceW(h, FALSE)) {
        h = NULL;
        snprintf(err, errSize, "Writing the resources failed (error %lu)", GetLastError());
        goto cleanup;
    }
    h = NULL;
    ok = 1;

cleanup:
    if (m) FreeLibrary(m);
    reFreeEntries(list);
    return ok;
}

// ---------------------------------------------------------------------------
// An existing Authenticode signature becomes invalid the moment anything in the
// file changes, and UpdateResource does not handle the certificate table
// correctly, so it is removed first: the security directory entry is cleared
// and, when the certificate data sits at the end of the file (it always does in
// a normally signed exe), it is cut off.
// ---------------------------------------------------------------------------

static int reStripSignature(const WCHAR* path, int* stripped, char* err, size_t errSize) {
    HANDLE h = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
    IMAGE_DOS_HEADER dos;
    DWORD sig, got, dirOffset;
    WORD magic;
    IMAGE_DATA_DIRECTORY dir, zero = {0, 0};
    LARGE_INTEGER pos, fileSize;
    int ok = 0;

    *stripped = 0;
    if (h == INVALID_HANDLE_VALUE) {
        snprintf(err, errSize, "Cannot open for writing (error %lu) - is it running?", GetLastError());
        return 0;
    }

    if (!ReadFile(h, &dos, sizeof(dos), &got, NULL) || got != sizeof(dos) || dos.e_magic != IMAGE_DOS_SIGNATURE) goto notpe;
    pos.QuadPart = dos.e_lfanew;
    if (!SetFilePointerEx(h, pos, NULL, FILE_BEGIN) ||
        !ReadFile(h, &sig, sizeof(sig), &got, NULL) || got != sizeof(sig) || sig != IMAGE_NT_SIGNATURE) goto notpe;

    // The optional header follows the 20-byte file header; its magic says 32 or 64 bit
    pos.QuadPart = dos.e_lfanew + 4 + sizeof(IMAGE_FILE_HEADER);
    if (!SetFilePointerEx(h, pos, NULL, FILE_BEGIN) ||
        !ReadFile(h, &magic, sizeof(magic), &got, NULL) || got != sizeof(magic)) goto notpe;
    if (magic == IMAGE_NT_OPTIONAL_HDR64_MAGIC) {
        dirOffset = (DWORD)FIELD_OFFSET(IMAGE_OPTIONAL_HEADER64, DataDirectory);
    } else if (magic == IMAGE_NT_OPTIONAL_HDR32_MAGIC) {
        dirOffset = (DWORD)FIELD_OFFSET(IMAGE_OPTIONAL_HEADER32, DataDirectory);
    } else {
        goto notpe;
    }
    pos.QuadPart += dirOffset + IMAGE_DIRECTORY_ENTRY_SECURITY * sizeof(IMAGE_DATA_DIRECTORY);

    if (!SetFilePointerEx(h, pos, NULL, FILE_BEGIN) ||
        !ReadFile(h, &dir, sizeof(dir), &got, NULL) || got != sizeof(dir)) goto notpe;

    if (dir.VirtualAddress && dir.Size) {
        if (!SetFilePointerEx(h, pos, NULL, FILE_BEGIN) || !WriteFile(h, &zero, sizeof(zero), &got, NULL)) {
            snprintf(err, errSize, "Cannot remove the existing signature (error %lu)", GetLastError());
            goto cleanup;
        }
        // For this directory VirtualAddress is a file offset, not an RVA
        if (GetFileSizeEx(h, &fileSize) && (LONGLONG)dir.VirtualAddress + dir.Size >= fileSize.QuadPart) {
            pos.QuadPart = dir.VirtualAddress;
            if (SetFilePointerEx(h, pos, NULL, FILE_BEGIN)) SetEndOfFile(h);
        }
        *stripped = 1;
    }
    ok = 1;
    goto cleanup;

notpe:
    snprintf(err, errSize, "Not a Windows executable");
cleanup:
    CloseHandle(h);
    return ok;
}

// ---------------------------------------------------------------------------
// (iii) Authenticode signing through mssign32!SignerSignEx2, which is what
// signtool.exe itself calls. SHA-256 file digest; optional RFC 3161 timestamp.
// The structs below are the documented ones (MSDN "SignerSignEx2 function");
// no SDK header ships them.
// ---------------------------------------------------------------------------

typedef struct { DWORD cbSize; LPCWSTR pwszFileName; HANDLE hFile; } RE_SIGNER_FILE_INFO;
typedef struct { DWORD cbSize; DWORD* pdwIndex; DWORD dwSubjectChoice; RE_SIGNER_FILE_INFO* pSignerFileInfo; } RE_SIGNER_SUBJECT_INFO;
typedef struct { DWORD cbSize; PCCERT_CONTEXT pSigningCert; DWORD dwCertPolicy; HCERTSTORE hCertStore; } RE_SIGNER_CERT_STORE_INFO;
typedef struct { DWORD cbSize; DWORD dwCertChoice; RE_SIGNER_CERT_STORE_INFO* pCertStoreInfo; HWND hwnd; } RE_SIGNER_CERT;
typedef struct { DWORD cbSize; ALG_ID algidHash; DWORD dwAttrChoice; void* pAttrAuthcode;
                 PCRYPT_ATTRIBUTES psAuthenticated; PCRYPT_ATTRIBUTES psUnauthenticated; } RE_SIGNER_SIGNATURE_INFO;
typedef struct { DWORD cbSize; DWORD cbBlob; BYTE* pbBlob; } RE_SIGNER_CONTEXT;

typedef HRESULT (WINAPI *ReSignerSignEx2_t)(DWORD dwFlags, RE_SIGNER_SUBJECT_INFO* subject, RE_SIGNER_CERT* cert,
                                             RE_SIGNER_SIGNATURE_INFO* sigInfo, void* providerInfo,
                                             DWORD timestampFlags, PCSTR timestampAlgOid, PCWSTR timestampUrl,
                                             PCRYPT_ATTRIBUTES request, PVOID sipData, RE_SIGNER_CONTEXT** context,
                                             PVOID cryptoPolicy, PVOID reserved);
typedef HRESULT (WINAPI *ReSignerFreeSignerContext_t)(RE_SIGNER_CONTEXT* context);

#define RE_SIGNER_SUBJECT_FILE 1
#define RE_SIGNER_CERT_STORE 2
#define RE_SIGNER_CERT_POLICY_CHAIN_NO_ROOT 8
#define RE_SIGNER_NO_ATTR 0
#define RE_SIGNER_TIMESTAMP_RFC3161 2

static int reHasPrivateKey(PCCERT_CONTEXT cert) {
    HCRYPTPROV_OR_NCRYPT_KEY_HANDLE key = 0;
    DWORD spec = 0;
    BOOL mustFree = FALSE;
    // CACHE_FLAG: the key handle stays attached to the certificate, nothing to free
    return CryptAcquireCertificatePrivateKey(cert, CRYPT_ACQUIRE_CACHE_FLAG | CRYPT_ACQUIRE_ALLOW_NCRYPT_KEY_FLAG |
                                             CRYPT_ACQUIRE_SILENT_FLAG, NULL, &key, &spec, &mustFree);
}

static int reParseThumbprint(const char* text, BYTE* out, DWORD outSize) {
    DWORD n = 0;
    while (*text) {
        int hi, lo;
        if (*text == ' ' || *text == ':') { text++; continue; }
        if (!text[1] || n >= outSize) return 0;
        hi = (*text >= '0' && *text <= '9') ? *text - '0' : (*text | 32) >= 'a' && (*text | 32) <= 'f' ? (*text | 32) - 'a' + 10 : -1;
        lo = (text[1] >= '0' && text[1] <= '9') ? text[1] - '0' : (text[1] | 32) >= 'a' && (text[1] | 32) <= 'f' ? (text[1] | 32) - 'a' + 10 : -1;
        if (hi < 0 || lo < 0) return 0;
        out[n++] = (BYTE)(hi * 16 + lo);
        text += 2;
    }
    return n == outSize;
}

static int reSign(const WCHAR* target, const ReStamp* s, char* report, size_t reportSize, char* err, size_t errSize) {
    HMODULE lib = NULL;
    HCERTSTORE store = NULL;
    PCCERT_CONTEXT cert = NULL;
    BYTE* pfx = NULL;
    DWORD pfxSize = 0;
    RE_SIGNER_CONTEXT* context = NULL;
    ReSignerSignEx2_t signEx2;
    ReSignerFreeSignerContext_t freeContext;
    WCHAR timestampUrl[512];
    WCHAR subjectName[256];
    int ok = 0;

    lib = LoadLibraryW(L"mssign32.dll");
    if (!lib) { snprintf(err, errSize, "mssign32.dll not available - cannot sign"); goto cleanup; }
    signEx2 = (ReSignerSignEx2_t)(void*)GetProcAddress(lib, "SignerSignEx2");
    freeContext = (ReSignerFreeSignerContext_t)(void*)GetProcAddress(lib, "SignerFreeSignerContext");
    if (!signEx2 || !freeContext) { snprintf(err, errSize, "This Windows has no SignerSignEx2 - cannot sign"); goto cleanup; }

    if (s->signPfx[0]) {
        CRYPT_DATA_BLOB blob;
        WCHAR password[256];
        const char* env = getenv("JR_SIGN_PASSWORD");
        PCCERT_CONTEXT c = NULL;

        if (!reReadFileAlloc(s->signPfx, &pfx, &pfxSize)) {
            snprintf(err, errSize, "Cannot read certificate file: %s", s->signPfx);
            goto cleanup;
        }
        reWiden(env ? env : "", password, 256);
        blob.cbData = pfxSize;
        blob.pbData = pfx;
        // NO_PERSIST_KEY: the private key lives only in this process's memory,
        // so signing leaves nothing behind in the user's key store
        store = PFXImportCertStore(&blob, password, PKCS12_NO_PERSIST_KEY | PKCS12_ALWAYS_CNG_KSP);
        SecureZeroMemory(password, sizeof(password));
        if (!store) {
            snprintf(err, errSize, "Cannot open %s (error 0x%08lX) - wrong password? It is read from JR_SIGN_PASSWORD",
                     s->signPfx, GetLastError());
            goto cleanup;
        }
        while ((c = CertEnumCertificatesInStore(store, c)) != NULL) {
            if (reHasPrivateKey(c)) { cert = c; break; }  // stays owned; freed in cleanup
        }
        if (!cert) { snprintf(err, errSize, "%s holds no certificate with a private key", s->signPfx); goto cleanup; }
    } else {
        BYTE hash[20];
        CRYPT_HASH_BLOB hashBlob;
        DWORD locations[2] = {CERT_SYSTEM_STORE_CURRENT_USER, CERT_SYSTEM_STORE_LOCAL_MACHINE};
        int li;
        if (!reParseThumbprint(s->signThumbprint, hash, sizeof(hash))) {
            snprintf(err, errSize, "-Xjr:sign.thumbprint must be a 40-hex-digit SHA-1 thumbprint");
            goto cleanup;
        }
        hashBlob.cbData = sizeof(hash);
        hashBlob.pbData = hash;
        for (li = 0; li < 2 && !cert; li++) {
            if (store) { CertCloseStore(store, 0); store = NULL; }
            store = CertOpenStore(CERT_STORE_PROV_SYSTEM_W, 0, 0, locations[li] | CERT_STORE_READONLY_FLAG, L"MY");
            if (store) cert = CertFindCertificateInStore(store, X509_ASN_ENCODING | PKCS_7_ASN_ENCODING, 0,
                                                         CERT_FIND_HASH, &hashBlob, NULL);
        }
        if (!cert) { snprintf(err, errSize, "No certificate with thumbprint %s in the Personal store", s->signThumbprint); goto cleanup; }
        if (!reHasPrivateKey(cert)) { snprintf(err, errSize, "The certificate %s has no usable private key", s->signThumbprint); goto cleanup; }
    }

    {
        RE_SIGNER_FILE_INFO fileInfo = {sizeof(RE_SIGNER_FILE_INFO), target, NULL};
        DWORD index = 0;
        RE_SIGNER_SUBJECT_INFO subject = {sizeof(RE_SIGNER_SUBJECT_INFO), &index, RE_SIGNER_SUBJECT_FILE, &fileInfo};
        RE_SIGNER_CERT_STORE_INFO storeInfo = {sizeof(RE_SIGNER_CERT_STORE_INFO), cert,
                                               RE_SIGNER_CERT_POLICY_CHAIN_NO_ROOT, store};
        RE_SIGNER_CERT signerCert = {sizeof(RE_SIGNER_CERT), RE_SIGNER_CERT_STORE, &storeInfo, NULL};
        RE_SIGNER_SIGNATURE_INFO sigInfo = {sizeof(RE_SIGNER_SIGNATURE_INFO), CALG_SHA_256, RE_SIGNER_NO_ATTR, NULL, NULL, NULL};
        HRESULT hr;

        reWiden(s->signTimestamp, timestampUrl, 512);
        hr = signEx2(0, &subject, &signerCert, &sigInfo, NULL,
                     s->signTimestamp[0] ? RE_SIGNER_TIMESTAMP_RFC3161 : 0,
                     s->signTimestamp[0] ? szOID_NIST_sha256 : NULL,
                     s->signTimestamp[0] ? timestampUrl : NULL,
                     NULL, NULL, &context, NULL, NULL);
        if (FAILED(hr)) {
            snprintf(err, errSize, "Signing failed (HRESULT 0x%08lX)%s", (unsigned long)hr,
                     s->signTimestamp[0] ? " - check the timestamp URL and the network" : "");
            goto cleanup;
        }
    }

    if (!CertGetNameStringW(cert, CERT_NAME_SIMPLE_DISPLAY_TYPE, 0, NULL, subjectName, 256)) subjectName[0] = L'\0';
    reAppend(report, reportSize, "Signed (SHA-256) by: %ls\n", subjectName);
    if (s->signTimestamp[0]) reAppend(report, reportSize, "Timestamped by: %s\n", s->signTimestamp);
    ok = 1;

cleanup:
    if (context) freeContext(context);
    if (cert) CertFreeCertificateContext(cert);
    if (store) CertCloseStore(store, 0);
    if (pfx) { SecureZeroMemory(pfx, pfxSize); free(pfx); }
    if (lib) FreeLibrary(lib);
    return ok;
}

// ---------------------------------------------------------------------------
// -Xjr:list-resources
// ---------------------------------------------------------------------------

typedef struct { char* buf; size_t size; int count; } ReListCtx;

static void reDescribeId(LPCWSTR id, int isType, char* out, size_t outSize) {
    if (IS_INTRESOURCE(id)) {
        static const char* names[] = {NULL, "CURSOR", "BITMAP", "ICON", "MENU", "DIALOG", "STRING", "FONTDIR", "FONT",
                                      "ACCELERATOR", "RCDATA", "MESSAGETABLE", "GROUP_CURSOR", NULL, "GROUP_ICON", NULL,
                                      "VERSION", "DLGINCLUDE", NULL, "PLUGPLAY", "VXD", "ANICURSOR", "ANIICON", "HTML", "MANIFEST"};
        WORD n = (WORD)(ULONG_PTR)id;
        if (isType && n < sizeof(names) / sizeof(names[0]) && names[n]) snprintf(out, outSize, "%s", names[n]);
        else snprintf(out, outSize, "%u", n);
    } else {
        snprintf(out, outSize, "%ls", id);
    }
}

static BOOL CALLBACK reListLangCb(HMODULE m, LPCWSTR type, LPCWSTR name, WORD lang, LONG_PTR param) {
    ReListCtx* ctx = (ReListCtx*)param;
    char t[80], n[80];
    HRSRC r = FindResourceExW(m, type, name, lang);
    reDescribeId(type, 1, t, sizeof(t));
    reDescribeId(name, 0, n, sizeof(n));
    reAppend(ctx->buf, ctx->size, "  %-12s %-20s lang %-5u %8lu bytes\n", t, n, lang, r ? (unsigned long)SizeofResource(m, r) : 0UL);
    ctx->count++;
    return TRUE;
}

static BOOL CALLBACK reListNameCb(HMODULE m, LPCWSTR type, LPWSTR name, LONG_PTR param) {
    EnumResourceLanguagesW(m, type, name, reListLangCb, param);
    return TRUE;
}

static BOOL CALLBACK reListTypeCb(HMODULE m, LPWSTR type, LONG_PTR param) {
    EnumResourceNamesW(m, type, reListNameCb, param);
    return TRUE;
}

static int reList(const char* path, char* report, size_t reportSize) {
    WCHAR w[MAX_PATH];
    HMODULE m;
    ReListCtx ctx;

    reWiden(path, w, MAX_PATH);
    m = LoadLibraryExW(w, NULL, LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE);
    if (!m) {
        snprintf(report, reportSize, "Cannot open %s (error %lu)", path, GetLastError());
        return 0;
    }
    snprintf(report, reportSize, "Resources in %s:\n", path);
    ctx.buf = report;
    ctx.size = reportSize;
    ctx.count = 0;
    EnumResourceTypesW(m, reListTypeCb, (LONG_PTR)&ctx);
    FreeLibrary(m);
    if (!ctx.count) reAppend(report, reportSize, "  (none)\n");
    return 1;
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

int reRun(const ReStamp* s, char* report, size_t reportSize) {
    char target[MAX_PATH];
    char err[1024] = {0};
    WCHAR targetW[MAX_PATH];
    int created = 0, stripped = 0;

    report[0] = '\0';

    if (s->listResources[0]) return reList(s->listResources, report, reportSize);

    if (s->make[0] && s->edit[0]) {
        snprintf(report, reportSize, "Use either -Xjr:make= or -Xjr:edit=, not both");
        return 0;
    }

    if (s->make[0]) {
        char self[MAX_PATH], selfFull[MAX_PATH];
        if (!GetModuleFileNameA(NULL, self, sizeof(self)) || !GetFullPathNameA(self, sizeof(selfFull), selfFull, NULL) ||
            !GetFullPathNameA(s->make, sizeof(target), target, NULL)) {
            snprintf(report, reportSize, "Cannot resolve the output path: %s", s->make);
            return 0;
        }
        if (_stricmp(selfFull, target) == 0) {
            snprintf(report, reportSize, "-Xjr:make cannot overwrite the running exe itself; use -Xjr:edit on a copy");
            return 0;
        }
        if (!CopyFileA(self, target, FALSE)) {
            snprintf(report, reportSize, "Cannot create %s (error %lu)", target, GetLastError());
            return 0;
        }
        created = 1;
        reAppend(report, reportSize, "Created %s (a copy of %s)\n", target, selfFull);
    } else {
        if (!GetFullPathNameA(s->edit, sizeof(target), target, NULL) ||
            GetFileAttributesA(target) == INVALID_FILE_ATTRIBUTES) {
            snprintf(report, reportSize, "No such file: %s", s->edit);
            return 0;
        }
        reAppend(report, reportSize, "Editing %s\n", target);
    }
    reWiden(target, targetW, MAX_PATH);

    if (s->signTimestamp[0] && !reHasSigning(s)) {
        snprintf(err, sizeof(err), "-Xjr:sign.timestamp needs -Xjr:sign= or -Xjr:sign.thumbprint=");
        goto fail;
    }

    if (reHasEdits(s)) {
        if (!reStripSignature(targetW, &stripped, err, sizeof(err))) goto fail;
        if (stripped) reAppend(report, reportSize, "Removed the existing signature (it would no longer be valid)\n");
    }
    if (reHasResourceEdits(s) && !reApplyResources(targetW, s, report, reportSize, err, sizeof(err))) goto fail;
    if (reHasSigning(s) && !reSign(targetW, s, report, reportSize, err, sizeof(err))) goto fail;
    return 1;

fail:
    if (created) DeleteFileA(target);  // no half-made output left behind
    snprintf(report, reportSize, "%s%s", err, created ? "\n(the new exe was not kept)" : "");
    return 0;
}
