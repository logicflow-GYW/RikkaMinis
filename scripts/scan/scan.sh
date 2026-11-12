#!/bin/sh
# CI scan gate — driver for the pre-build consistency checks.
#
# The checks live in scripts/scan/scan_blocks.sh (data file, one block per
# check). They are independent, so the driver runs them in a bounded process
# pool and prints the transcripts in 1..N order; the pool turns the old
# ~9.5 minute serial phase into roughly the cost of the single slowest check
# plus scheduling overhead.
#
# [review1112] N is DERIVED, not stated here: the header denominators in
# scan_blocks.sh are the only place the count appears, and scan_parallel.py
# verifies they match the number of blocks it parses. This comment used to say
# "114" and drifted one behind on the very first gate addition.
#
# Usage: sh scripts/scan/scan.sh [--jobs N|auto] [--timings]
#   MINIS_SCAN_JOBS=1  → serial execution (byte-comparable with the old script)
# Exit code: 0 = all clean, 1 = any check found issues.
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec python3 "$ROOT/scripts/scan/scan_parallel.py" --root "$ROOT" "$@"
