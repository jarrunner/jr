#ifndef JR_RESEDIT_H
#define JR_RESEDIT_H

// Resource editing and Authenticode signing of .exe files - rcedit's job, done
// inside jr so a launcher copy can be branded with no extra tool. See
// prp/13-prp-exe_resource_editing_and_signing.md.
//
// Driven by -Xjr: options (parsed by reParseOption, run by reRun):
//   -Xjr:make=<out.exe>                  copy this exe to out.exe, then apply the edits below
//   -Xjr:edit=<exe>                      apply them to an existing exe, in place
//   -Xjr:list-resources=<exe>            print an exe's resources
//   -Xjr:icon=<file.ico>                 replace the main icon
//   -Xjr:version=<a.b.c.d>               file and product version
//   -Xjr:file-version= / product-version=
//   -Xjr:version.<Name>=<text>           a version string, e.g. version.FileDescription=My App
//   -Xjr:manifest=<file>                 the application manifest
//   -Xjr:execution-level=<level>         asInvoker | highestAvailable | requireAdministrator
//   -Xjr:string.<id>=<text>              a string-table entry
//   -Xjr:resource.<type>.<name>=<file>   any other resource, raw (type: number, RCDATA, HTML, MANIFEST or a custom name)
//   -Xjr:sign=<file.pfx>                 sign (password from the JR_SIGN_PASSWORD env var, never the command line)
//   -Xjr:sign.thumbprint=<sha1>          sign with a certificate from the Windows store instead
//   -Xjr:sign.timestamp=<url>            RFC 3161 timestamp server
//
// Order is always: copy, strip any old signature, resources, sign - signing last,
// because any later change to the file would invalidate the signature.

#include <windows.h>

#define RE_MAX_ITEMS 32

typedef struct { char name[64]; char value[512]; } ReVersionString;
typedef struct { unsigned int id; char text[1024]; } ReString;
typedef struct { char type[64]; char name[64]; char file[MAX_PATH]; } ReRaw;

typedef struct {
    char make[MAX_PATH];
    char edit[MAX_PATH];
    char listResources[MAX_PATH];

    char icon[MAX_PATH];
    char fileVersion[64];
    char productVersion[64];
    ReVersionString versionStrings[RE_MAX_ITEMS];
    int versionStringCount;
    char manifest[MAX_PATH];
    char executionLevel[64];
    ReString strings[RE_MAX_ITEMS];
    int stringCount;
    ReRaw raws[RE_MAX_ITEMS];
    int rawCount;

    char signPfx[MAX_PATH];
    char signThumbprint[128];
    char signTimestamp[512];
} ReStamp;

// opt is one -Xjr: option with the "-Xjr:" prefix removed. Returns 1 if it was a
// resource/signing option (stored in s), 0 if it is not one of ours, -1 if it was
// ours but malformed (message written to err).
int reParseOption(ReStamp* s, const char* opt, char* err, size_t errSize);

// 1 if any make/edit/list action was requested
int reHasAction(const ReStamp* s);

// 1 if any edit (icon, version, ..., sign) was requested
int reHasEdits(const ReStamp* s);

// Carry out the requested action. Returns 1 on success. report receives a
// human-readable summary on success, or the error on failure.
int reRun(const ReStamp* s, char* report, size_t reportSize);

#endif
