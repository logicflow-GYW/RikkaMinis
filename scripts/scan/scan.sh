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
echo "━━━ [1/18] Four-way sync check ━━━"
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
echo "━━━ [2/18] i18n consistency check ━━━"
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
echo "━━━ [3/18] Enum parse safety check ━━━"
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
echo "━━━ [4/18] Provider process-boundary guard ━━━"
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
echo "━━━ [5/18] Agent trace replay eval ━━━"
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
echo "━━━ [6/18] Legacy pipeline guard ━━━"
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
echo "━━━ [7/18] Native crash-field host test ━━━"
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
echo "━━━ [8/18] Debug-code release boundary (source) ━━━"
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
echo "━━━ [9/18] Room migration chain ━━━"
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
echo "━━━ [10/18] Process-logging coverage ━━━"
if python3 scripts/scan/process_logging_gate.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 11. AlarmClock EXTRA_DAYS container type (silent-failure guard) ---
#   EXTRA_DAYS is specified as ArrayList<Integer>; the framework reads it with
#   ArrayList.class.cast(), swallows the ClassCastException and returns null --
#   so an int[] (or Kotlin's listOf()) makes a repeating alarm silently replay
#   as a one-shot. No crash, no failing test, no user-visible log. The repo has
#   two ACTION_SET_ALARM writers and they drifted apart once already.
echo "━━━ [11/18] AlarmClock EXTRA_DAYS container type ━━━"
if python3 scripts/scan/extra_days_container_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 12. Stale-closure guard (long-lived effect reads a plain parameter) ---
#   produceState/LaunchedEffect keys ARE remember() keys: when a key does not
#   change the producer closure is NOT rebuilt and keeps its captured values,
#   and `snapshotFlow { plainParam }` observes nothing so it emits exactly
#   once. The 2026-09-20 incident: FIX-4 dropped `content` from a produceState
#   key to fix a broken throttle — the throttle started working and the live
#   streaming text froze at the first composed frame (user saw the first two
#   characters of an answer until they re-entered the session). Nothing
#   crashed, no test failed. Fix is always `rememberUpdatedState`.
echo "━━━ [12/18] Stale-closure guard ━━━"
if python3 scripts/scan/stale_closure_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 13. IME-inset guard (text field hosted by a bare Scaffold) ---
#   Edge-to-edge + adjustResize: the window is NOT resized for the keyboard, so
#   Compose must consume WindowInsets.ime itself. A bare Scaffold consumes
#   systemBars ONLY (Scaffold.kt:293) — a text field under it has nothing
#   shrinking it above the IME, and BasicTextField's own "scroll caret into
#   view" parks the caret behind the keyboard. Silent: no crash, no failing
#   test, no log. User-reported 2026-09-20 (skill + memory editors) after the
#   same family had been patched piecemeal 4+ times (BrowserSettingsSheet /
#   MCPIntegrations GH#44 / EnvironmentVariables / SettingsComponents T183).
echo "━━━ [13/18] IME-inset guard ━━━"
if python3 scripts/scan/ime_inset_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 14. Stream-flow dispatch guard (blocking cold flow on the collector) ---
#   A callbackFlow/channelFlow body runs in the COLLECTOR's context. The
#   provider stream bodies block (call.execute() then reader.readLine()), so
#   without a flowOn the producer owns the collector thread and starves every
#   timer scheduled on it — including the flow's OWN watchdogs. Invisible while
#   the collector was a thread pool; 01cfcc0e (TF-D) moved it into the
#   :modelservice worker's `runBlocking`, which has no dispatcher, so the TTFB
#   watchdog, the first-data watchdog AND the worker's outer timeout all became
#   unreachable at once. 2026-09-20: a 1.6MB request sat 272s with no byte back.
#   No test can catch this (it would have to wait 30 real minutes), so it has
#   to be a static judgement.
echo "━━━ [14/18] Stream-flow dispatch guard ━━━"
if python3 scripts/scan/stream_flow_dispatch_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 15. Prefs change-listener holder guard (WeakHashMap collection) ---
#   AOSP SharedPreferencesImpl keeps listeners in a WeakHashMap whose value is a
#   sentinel that does NOT reference the key. A listener passed as a bare lambda
#   argument therefore has no strong reference: the GC collects it and the
#   callback silently stops firing, never re-registered. F-134: this was live in
#   ConfigBuiltins, so `minis-config set runtime.shellOutputKb 512` returned
#   ok:true while every reader kept serving the primed default (128 KB) until
#   process restart. Unobservable at runtime (no exception, no log), so it has
#   to be a static judgement.
echo "━━━ [15/18] Prefs listener holder guard ━━━"
if python3 scripts/scan/prefs_listener_holder_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 16. Cross-process boundary completeness ---
#   Models cross into the :modelservice worker as a named-key bundle. A field
#   added to the model but never written by the dispatcher silently falls back
#   to its default inside the worker -- no exception, no log, wrong behaviour.
#   Repeat offender. Live example (P0, 2026-09-22): LLMModel.maxOutputTokens was
#   never written, so the worker clamped 96.3% of real requests to 16384.
#   Sibling guard four_way_sync_check.py covers Model<->Entity<->snapshot for
#   ProviderInstance/ModelGroup only; it does not cover this IPC hop, nor
#   LLMModel. Any new boundary model must be registered in BOUNDARY_MODELS.
echo "━━━ [16/18] Cross-process boundary completeness ━━━"
if python3 scripts/scan/xproc_boundary_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 17. Thinking-level wiring ---
#   [T-thinking-effective-level] The picker takes `current` (drives the
#   highlight) and `requested` (drives the orange up-arrow). Both are
#   ThinkingLevel, so swapping them compiles, passes every pure-rule unit test,
#   and silently deletes the cue — on 2026-09-22 the branch's own fix did
#   exactly that (`requested` was wired to the effective flow, so isCappedBy
#   compared the level against itself: 15 of 64 (ceiling, choice) combinations
#   showed the cue before, 0 after). A @Composable call site cannot be unit
#   tested in the sandbox, so this gate reads the call site as text and checks
#   the argument's PROVENANCE (following local `val` bindings, so a rename
#   cannot defeat it).
echo "━━━ [17/18] Thinking-level wiring guard ━━━"
if python3 scripts/scan/thinking_wiring_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 18. Liveness static guard (probe census integrity) ---
#   `liveness_audit.py --strict` needs a real log window, so CI can never run
#   it. This gate enforces the log-free half: (A) every public AppLogger log
#   method is enumerated by the census — a missed method makes every probe
#   through it invisible BY CONSTRUCTION (2026-09-22: `fun debug` was missed);
#   (B) every RARE registry entry still resolves to a live declaration — an
#   entry whose probe was deleted is a dead ruler holding a permission slip
#   (2026-09-22: buildFlatChatItems.ledgerReseed); (C) census call-site counts
#   match raw counts (regex-drift guard; the D4 `[dwiev]` shape hid 47.6%).
echo "━━━ [18/18] Liveness static guard ━━━"
if python3 scripts/scan/liveness_static_guard.py "$ROOT"; then
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