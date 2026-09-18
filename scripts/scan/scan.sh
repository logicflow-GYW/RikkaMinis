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
echo "━━━ [1/10] Four-way sync check ━━━"
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
echo "━━━ [2/10] i18n consistency check ━━━"
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
echo "━━━ [3/10] Enum parse safety check ━━━"
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
echo "━━━ [4/10] Provider process-boundary guard ━━━"
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
echo "━━━ [5/10] Agent trace replay eval ━━━"
if python3 scripts/scan/trace_eval_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 6. Legacy flat-chat pipeline guard (no new references to the dead path) ---
#   AGGREGATE_MESSAGE_ITEMS = true makes the pre-aggregate pipeline runtime-dead
#   but it is kept as the Stage-E fallback. Referencing it from a live file is
#   how the 2026-09-13 cold-open prewarm incident happened: the source list was
#   always empty and nothing ever failed, logged or warned. New references are a
#   build failure unless the file is allow-listed or justifies it inline with
#   `legacy-ok: <reason>`.
echo "━━━ [6/10] Legacy pipeline guard ━━━"
if python3 scripts/scan/legacy_pipeline_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 7. Native crash-report field host test (no NDK needed) ---
#   crash_report_fields.h backs the native crash handler's death-state capture
#   (RssAnon/RssFile/RssShmem/VmSize/MemAvailable/mapping count). The handler
#   only runs on Android after a real fatal signal, so without this the parsing
#   would ship unverified — the same header source is compiled by the host
#   compiler and asserted against fixture /proc text. Skipped (not failed) on
#   machines without a host C++ compiler.
echo "━━━ [7/10] Native crash-field host test ━━━"
if sh "$ROOT/scripts/native/crash_fields_host_test.sh" > /tmp/minis_crash_fields_host_test.log 2>&1; then
    tail -n 2 /tmp/minis_crash_fields_host_test.log
    PASS=$((PASS + 1))
    echo ""
elif command -v g++ >/dev/null 2>&1 || command -v clang++ >/dev/null 2>&1; then
    cat /tmp/minis_crash_fields_host_test.log
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
else
    echo "⚠ no host C++ compiler (g++/clang++) — skipped"
    PASS=$((PASS + 1))
    echo ""
fi

# --- 8. Debug-code release boundary (source mode) ---
#   debug/ lives in src/main; release hygiene depends on R8 DCE +
#   BuildConfig.DEBUG guards at every call site. A NEW debug-package call site
#   outside the audited allow-list is the silent path by which debug code
#   (token-free loopback JSON-RPC on 127.0.0.1:5321) reaches release. The apk
#   mode (below, in build-apk.yml) verifies the artifact itself.
echo "━━━ [8/10] Debug-code release boundary (source) ━━━"
if python3 scripts/scan/debug_leak_guard.py source "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 9. Room migration chain (schema integrity) ---
#   Chain completeness for the hand-written Room migrations: a version gap, a
#   non-contiguous step, or a migration DECLARED in the builder but never
#   wired. The last one compiles and runs — it just never executes, so a
#   fresh install and an upgraded install end up with different schemas and
#   nothing fails until a user hits the missing column.
echo "━━━ [9/10] Room migration chain ━━━"
if python3 scripts/scan/room_migration_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 10. Process-logging coverage (TF full-coverage) ---
#   Every android:process declaration must have an explicit AppLogger.init
#   decision (wire init in that process's Application branch, or justify with
#   `logging-ok:`). The 2026-09-18 incident: :modelservice never ran init, so
#   the LLM-request process logged zero lines — 9 provider 400s, no trace.
echo "━━━ [10/10] Process-logging coverage ━━━"
if python3 scripts/scan/process_logging_gate.py "$ROOT"; then
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