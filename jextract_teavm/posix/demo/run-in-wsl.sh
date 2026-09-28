#!/bin/bash
# Runs the Linux x64 demo builds on real Linux (WSL) and reports each exit code.
# Invoke as: wsl -d Ubuntu -e bash <this file, as a /mnt/c path>   (-e: no wsl shell re-joining the arguments)
cd "$(dirname "$0")" || exit 1
sh -c 'exit 3'; echo "shell sanity (expect 3): $?"
DEMO_EXIT_TEST=1 ./build-linux_x64/posixdemo; echo "exit(42) passthrough (expect 42): $?"
./build-linux_x64/posixdemo; echo "linux_x64 bindings (expect 0 = no failed checks): $?"
./build-linux_x64-with-macos_x64/posixdemo > /dev/null; echo "macos_x64 bindings on Linux (expect >0 failed checks): $?"
