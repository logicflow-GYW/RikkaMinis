#!/bin/sh
# CI scan gate — runs all pre-build consistency checks.
# Called from the CI workflow before the Gradle build.
# Usage: sh scripts/scan/scan.sh
# Exit code: 0 = all clean, 1 = any scanner found issues

set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
RC=0
PASS=0
FAIL=0

echo "╔══════════════════════════════════════════════════╗"
echo "║  RikkaMinis CI Scan Gate                        ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# --- 1. Four-way sync check ---
echo "━━━ [1/5] Four-way sync check ━━━"
if python3 scripts/scan/four_way_sync_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 2. i18n consistency ---
#   - Orphan keys (in code but not in strings.xml) = HARD FAIL
#   - Missing translations = WARNING only (known legacy from upstream)
echo "━━━ [2/5] i18n consistency check ━━━"
python3 -c "
import re, os, sys
root = '$ROOT'
values_dir = os.path.join(root, 'src/android/app/src/main/res/values')
strings_xml = os.path.join(values_dir, 'strings.xml')
with open(strings_xml) as f:
    defined = set(re.findall(r'name=\"([a-z0-9_]+)\"', f.read()))
code_keys = set()
src_dir = os.path.join(root, 'src/android/app/src/main')
for dirpath, _, fns in os.walk(src_dir):
    for fn in fns:
        if not fn.endswith('.kt'): continue
        with open(os.path.join(dirpath, fn)) as f:
            code_keys.update(re.findall(r'R\.string\.([a-z0-9_]+)', f.read()))
orphans = code_keys - defined
if orphans:
    print(f'❌ Orphan keys: {sorted(orphans)}')
    sys.exit(1)
else:
    print(f'✅ No orphan keys ({len(code_keys)} refs, {len(defined)} defs)')
    # Missing translations: warning only
    res_dir = os.path.join(root, 'src/android/app/src/main/res')
    for loc in sorted(d for d in os.listdir(res_dir) if d.startswith('values-') and d != 'values'):
        loc_file = os.path.join(res_dir, loc, 'strings.xml')
        if not os.path.exists(loc_file): continue
        with open(loc_file) as f:
            loc_keys = set(re.findall(r'name=\"([a-z0-9_]+)\"', f.read()))
        missing = (defined - loc_keys) & code_keys
        if missing:
            print(f'  ⚠️  {loc}: {len(missing)} active keys untranslated')
    sys.exit(0)
"
if [ $? -eq 1 ]; then
    RC=1
    FAIL=$((FAIL + 1))
else
    PASS=$((PASS + 1))
fi
echo ""

# --- 3. Bare valueOf check (persisted enum safety) ---
echo "━━━ [3/5] Enum parse safety check ━━━"
if python3 scripts/scan/enum_parse_safety_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 4. Provider process-boundary guard (TF-E) ---
# Mechanical constraint: the app process must never call a provider network
# entry point directly — only :modelservice (ModelExecutionService) owns them.
echo "━━━ [4/5] Provider process-boundary guard ━━━"
if python3 scripts/scan/provider_boundary_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 5. Agent trace replay eval (golden assertions on schema 2.0 JSONL) ---
# Replays recorded/synthetic agent traces against golden expectations:
# tool sequence, terminal state, forbidden tools, all-succeed. Closes the
# loop on AgentTraceRecorder output (produce → consume). Inline selftest
# goldens keep the evaluator itself honest in CI; device traces can be added
# later under tests/traces/golden/ referencing real .jsonl files.
echo "━━━ [5/5] Agent trace replay eval ━━━"
if python3 scripts/scan/trace_eval_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- Summary ---
echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║  Summary: $PASS passed, $FAIL failed"
echo "╚══════════════════════════════════════════════════╝"
exit $RC