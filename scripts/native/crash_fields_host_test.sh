#!/bin/sh
# Host-side verification for cpp/crash_report_fields.h — the pure parsing
# helpers behind the native crash handler's death-state capture.
#
# The handler only runs on Android after a real fatal signal, so its parsing
# would otherwise ship unverified. These tests compile the exact same header
# source with the host compiler and assert on fixture /proc text.
#
# Needs only a C++ compiler (no NDK, no Android SDK, no adb).
#   sh scripts/native/crash_fields_host_test.sh
set -e

here=$(dirname "$0")
inc=$(cd "$here/../../src/android/app/src/main/cpp" && pwd)
tmp=${TMPDIR:-/tmp}
bin="$tmp/crash_fields_host_test.bin"

# Pick a compiler: $CXX wins, else the first of g++/clang++ on PATH.
if [ -z "$CXX" ]; then
    for c in g++ clang++; do
        if command -v "$c" >/dev/null 2>&1; then CXX=$c; break; fi
    done
fi
if [ -z "$CXX" ]; then
    echo "no host C++ compiler (g++/clang++) found" >&2
    exit 2
fi

"$CXX" -std=c++17 -O1 -Wall -Wextra -Werror -I"$inc" \
    -o "$bin" "$here/crash_fields_host_test.cpp"
"$bin"
