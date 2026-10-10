#!/bin/bash
# Shared by the tests in this folder (PRP-42): the few things Linux and macOS spell differently, and a local HTTPS
# server for the update tests. Sourced, not run.
case "$(uname -s)" in
    Darwin)
        KEY=macos-$( [ "$(uname -m)" = arm64 ] && echo arm64 || echo x86_64 )
        sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
        mode() { stat -f %Lp "$1"; }
        inode() { stat -f %i "$1"; }
        OPENSSL=$(ls /opt/homebrew/opt/openssl@3/bin/openssl /usr/local/opt/openssl@3/bin/openssl 2>/dev/null | head -1)
        ;;
    *)
        KEY=linux-$( [ "$(uname -m)" = aarch64 ] && echo arm64 || echo x86_64 )
        sha() { sha256sum "$1" | cut -d' ' -f1; }
        mode() { stat -c %a "$1"; }
        inode() { stat -c %i "$1"; }
        ;;
esac
OPENSSL=${OPENSSL:-openssl}
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
# Runs a command with a time limit (macOS has no timeout(1)): limit SECONDS cmd args...
limit() { perl -e 'alarm shift; exec @ARGV' "$@"; }
# Serves the current folder over HTTPS on port $PORT with a self-signed certificate that curl trusts through
# CURL_CA_BUNDLE; stopped when the script exits.
serve_https() {
    "$OPENSSL" req -x509 -newkey rsa:2048 -nodes -keyout k.pem -out c.pem -days 1 -subj /CN=localhost \
        -addext subjectAltName=DNS:localhost 2>/dev/null || { echo "openssl could not make a certificate"; exit 1; }
    export CURL_CA_BUNDLE="$PWD/c.pem"
    "$OPENSSL" s_server -accept "$PORT" -cert c.pem -key k.pem -WWW -quiet >/dev/null 2>&1 & SRV=$!
    trap 'kill $SRV 2>/dev/null' EXIT
    sleep 1
}
finish() { echo "passed $pass, failed $fail"; [ "$fail" = 0 ]; }
