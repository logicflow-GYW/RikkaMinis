#!/bin/sh
# gh_ci_wait.sh — dispatch (unless --no-dispatch), show jobs on every poll,
# then return the final workflow verdict. See --help for options.
exec python3 "$(dirname -- "$0")/gh_ci.py" wait "$@"
