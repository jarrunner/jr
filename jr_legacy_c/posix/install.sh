#!/bin/bash
# install.sh - put one jar on the PATH under its own name, the way jrmac expects.
#
#   ./install.sh <name> <jar> [--main CLASS] [--no-aot] [--bin DIR] [--force]
#
# Two things have to be right and both are easy to get subtly wrong by hand: the tool must
# be a SYMLINK to jrmac rather than a copy of it, and the .jrc must be named after the
# symlink. So this does both, and says what it did.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JRMAC="$HERE/jrmac"

NAME=""; JAR=""; MAIN=""; AOT="true"; BIN=""; FORCE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --main) MAIN="$2"; shift 2 ;;
        --no-aot) AOT="false"; shift ;;
        --bin) BIN="$2"; shift 2 ;;
        --force) FORCE="yes"; shift ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        -*) echo "install.sh: unknown flag $1" >&2; exit 2 ;;
        *) if [ -z "$NAME" ]; then NAME="$1"; elif [ -z "$JAR" ]; then JAR="$1";
           else echo "install.sh: unexpected argument $1" >&2; exit 2; fi; shift ;;
    esac
done

if [ -z "$NAME" ] || [ -z "$JAR" ]; then
    echo "usage: ./install.sh <name> <jar> [--main CLASS] [--no-aot] [--bin DIR] [--force]" >&2
    exit 2
fi
if [ ! -f "$JRMAC" ]; then
    echo "install.sh: jrmac not found beside this script at $JRMAC" >&2
    exit 1
fi
if [ ! -f "$JAR" ]; then
    echo "install.sh: no such jar: $JAR" >&2
    exit 1
fi

# Default to the layout these tools already use: ~/my-tools/{bin,jars,jrmac}. Named
# rather than guessed from $HERE, because on a target machine jrmac is a deployed copy of
# this directory and not the git checkout it came from.
[ -n "$BIN" ] || BIN="$HOME/my-tools/bin"
mkdir -p "$BIN"
BIN="$(cd "$BIN" && pwd)"
JARABS="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

TOOL="$BIN/$NAME"
if [ -e "$TOOL" ] || [ -L "$TOOL" ]; then
    if [ -z "$FORCE" ]; then
        WHAT="a symlink"; [ -L "$TOOL" ] || WHAT="a regular file - probably a stale copy of jrmac"
        echo "install.sh: $TOOL already exists ($WHAT). Re-run with --force to replace it." >&2
        exit 1
    fi
    [ -L "$TOOL" ] || echo "replacing a regular file with a symlink: $TOOL"
    rm -f "$TOOL"
fi

# Relative when bin and jrmac are siblings, which is the normal layout and survives the
# whole tree being moved or renamed; absolute otherwise, because computing a relative path
# between arbitrary directories needs realpath --relative-to, which stock macOS lacks.
PARENT="$(dirname "$BIN")"
case "$JRMAC" in
    "$PARENT"/*) ln -s "../${JRMAC#$PARENT/}" "$TOOL" ;;
    *) ln -s "$JRMAC" "$TOOL" ;;
esac

{
    case "$JARABS" in
        "$PARENT"/*) echo "jar=../${JARABS#$PARENT/}" ;;
        *) echo "jar=$JARABS" ;;
    esac
    [ -z "$MAIN" ] || echo "main=$MAIN"
    echo "aot=$AOT"
} > "$TOOL.jrc"

echo "installed $NAME"
# readlink comes back empty where the filesystem cannot hold a symlink at all - Git Bash on
# Windows copies instead, silently - and a bare arrow pointing at nothing reads as a bug in
# this script rather than as what it is.
LINK="$(readlink "$TOOL" 2>/dev/null)" || LINK=""
echo "  $TOOL -> ${LINK:-(not a symlink: this filesystem would not make one)}"
echo "  $TOOL.jrc:"
sed 's/^/    /' "$TOOL.jrc"
case ":$PATH:" in
    *":$BIN:"*) ;;
    *) echo "  note: $BIN is not on PATH" ;;
esac
[ "$AOT" = "false" ] || echo "  first run trains ${JARABS%.jar}.aot; every run after reuses it"
