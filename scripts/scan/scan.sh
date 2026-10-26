#!/bin/sh
# CI scan gate — driver for the 112 pre-build consistency checks.
#
# The checks live in scripts/scan/scan_blocks.sh (data file, one block per
# check). They are independent, so the driver runs them in a bounded process
# pool and prints the transcripts in 1..112 order; the pool turns the old
# ~9.5 minute serial phase into roughly the cost of the single slowest check
# plus scheduling overhead.
#
# Usage: sh scripts/scan/scan.sh [--jobs N|auto] [--timings]
#   MINIS_SCAN_JOBS=1  → serial execution (byte-comparable with the old script)
# Exit code: 0 = all clean, 1 = any check found issues.
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec python3 "$ROOT/scripts/scan/scan_parallel.py" --root "$ROOT" "$@"
