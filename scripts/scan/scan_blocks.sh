#!/bin/sh
# scan_blocks.sh — DATA FILE for scripts/scan/scan_parallel.py. Do not run directly.
#
# 112 static checks, one block per check. Each block is a self-contained shell
# snippet whose verdict is carried by the RC variable: RC stays 0 on success and
# is set to 1 by the block's failure branch (a block may have several sub-checks;
# any RC=1 makes the whole block red). The runner executes each block in its own
# `sh -c` with cwd = repo root and ROOT exported, so blocks must not rely on
# variables or side effects produced by another block.
#
# Edit here to add/reorder checks; scripts/scan/scan.sh is only a driver.

# --- 1. Four-way sync check ---
echo "━━━ [1/112] Four-way sync check ━━━"
if python3 scripts/scan/four_way_sync_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 2. i18n consistency ---
#   - Orphan keys (in code but not in any values/strings*.xml) = HARD FAIL
#   - Missing translations = WARNING only (known legacy from upstream)
#   Strings may be split across several files per res folder:
#   values/strings_db_guard.xml keeps the database-downgrade guidance strings
#   out of the shared strings.xml, so the default set is the UNION over
#   strings*.xml. Reading strings.xml alone reports those keys as orphans and
#   fails the build for resources that exist.
echo "━━━ [2/112] i18n consistency check ━━━"
python3 -c "
import re, os, sys
root = '$ROOT'
res_dir = os.path.join(root, 'src/android/app/src/main/res')
def defined_in(folder):
    keys = set()
    if not os.path.isdir(folder): return keys
    for fn in sorted(os.listdir(folder)):
        if not (fn.startswith('strings') and fn.endswith('.xml')): continue
        with open(os.path.join(folder, fn)) as f:
            keys.update(re.findall(r'name=\"([a-z0-9_]+)\"', f.read()))
    return keys
defined = defined_in(os.path.join(res_dir, 'values'))
if not defined:
    print('❌ no strings*.xml found under ' + os.path.join(res_dir, 'values'))
    sys.exit(1)
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
    for loc in sorted(d for d in os.listdir(res_dir) if d.startswith('values-') and d != 'values'):
        loc_keys = defined_in(os.path.join(res_dir, loc))
        if not loc_keys: continue
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
echo "━━━ [3/112] Enum parse safety check ━━━"
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
echo "━━━ [4/112] Provider process-boundary guard ━━━"
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
echo "━━━ [5/112] Agent trace replay eval ━━━"
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
echo "━━━ [6/112] Legacy pipeline guard ━━━"
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
echo "━━━ [7/112] Native crash-field host test ━━━"
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
echo "━━━ [8/112] Debug-code release boundary (source) ━━━"
if python3 scripts/scan/debug_leak_guard.py "$ROOT"; then
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
echo "━━━ [9/112] Room migration chain ━━━"
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
echo "━━━ [10/112] Process-logging coverage ━━━"
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
echo "━━━ [11/112] AlarmClock EXTRA_DAYS container type ━━━"
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
echo "━━━ [12/112] Stale-closure guard ━━━"
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
echo "━━━ [13/112] IME-inset guard ━━━"
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
echo "━━━ [14/112] Stream-flow dispatch guard ━━━"
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
echo "━━━ [15/112] Prefs listener holder guard ━━━"
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
echo "━━━ [16/112] Cross-process boundary completeness ━━━"
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
echo "━━━ [17/112] Thinking-level wiring guard ━━━"
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
echo "━━━ [18/112] Liveness static guard ━━━"
if python3 scripts/scan/liveness_static_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 19. Log URL redaction guard (a secret must not reach an output surface) ---
#   Added 2026-09-26 after the absorb pack shipped a trace listener logging the
#   whole request URL (`?key=` providers). Every existing gate was green because
#   they check SHAPES (keys, mappings, compiles) and this is a WIRING question:
#   can a credential reach an output through this new line? The gate flags whole
#   URL interpolation inside log calls within the credential-bearing subtrees and
#   keeps an audited allow-list (`--seed` regenerates it).
echo "━━━ [19/112] Log URL redaction guard ━━━"
if python3 scripts/scan/log_url_redaction_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 20. Discarded-return guard (a call whose value MUST be consumed) ---
#   Added 2026-09-26 after a TTFB observer called an immutable-by-copy recorder in
#   statement position: it compiled, logged, and recorded nothing, so a budget
#   silently stayed at its 30 s default. Watchlist-driven, so extending it is the
#   maintenance path when another value-returning recorder is discovered.
echo "━━━ [20/112] Discarded-return guard ━━━"
if python3 scripts/scan/discarded_return_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 21. Claim/release ownership guard ---
#   2026-10-12 14:40 field regression: a predecessor turn's teardown landed
#   after the new turn's `_isStreaming = true` but before the new claim's
#   marker was published, passed the stale-job gate, and cleared the live
#   turn's flag. Third generation of the family (09-06 unconditional clear ->
#   "fixed one side" retry paths -> 10-12 publication window).
#   Check A: ungated release with a gated sibling; Check B: claim flips before
#   the release gate's marker is published. Verified exact on history: the
#   pre-fix tree trips B at all 7 claim sites, the post-fix tree at none.
#   Audited-safe families carry an ALLOWED entry with its rationale; new
#   ungated writes get `release-ok:` / `claim-ok:` or a re-audit.
echo "━━━ [21/112] Claim/release ownership guard ━━━"
if python3 scripts/scan/claim_release_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 22. Loop counter reset guard (comment semantics vs execution position) ---
#   Added 2026-10-15 (audit2 BUG-01). The agent loop's three continuation
#   counters were reset ~640 lines ABOVE the `if (toolCalls.isEmpty())` branch,
#   at a spot every turn passes through, while their comments claimed
#   "a tool-call turn clears …". Result: the length-wall, deterministic-empty
#   and EOF-stub guards were all dead (each counter pinned to 0 before it could
#   reach its limit); only maxTurns bounded the loop. Every existing gate
#   checks shapes or wiring — this is a SEMANTICS-vs-EXECUTION-POSITION check,
#   which is why nothing caught it. Criterion is mechanical: a numeric
#   `loopState.<name> = 0` whose comment block claims tool-call semantics must
#   sit inside the brace span of a `toolCalls` positive guard.
echo "━━━ [22/112] Loop counter reset guard ━━━"
if python3 scripts/scan/loop_counter_reset_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 23. Budget-stop break guard (which loop does the break exit?) ---
#   Added 2026-10-15 (audit3 F-02). The agent loop's two execution-budget
#   gates (deadline / provider_attempt_limit) live inside the turn body's
#   retry `while`; their bare `break` left only that retry loop, so the turn
#   fell through to the success tail — route Success recorded for the member
#   whose attempt budget had just refused it, an empty ASSISTANT entry
#   appended to agentHistory, and the remaining turns burned against a
#   reducer already parked in FINALIZING. Sibling of gate 22: that one checks
#   a statement's execution position against its comment, this one checks a
#   `break` target against the branch's declared intent (t7BudgetStopReason).
#   Criterion: a `break` that follows a `t7BudgetStopReason = "<string>"`
#   assignment inside the same brace block, and is enclosed by >= 2 loops,
#   must carry the outermost loop's label (`break@turnLoop`).
echo "━━━ [23/112] Budget-stop break guard ━━━"
if python3 scripts/scan/budget_stop_break_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 24. KaTeX JS-bridge arity guard (HTML call site vs Kotlin host) ---
#   Added 2026-10-15 (deep-audit BUG-05). `assets/katex/katex-render.html`
#   calls `AndroidBridge.onRendered(w, h, error, token)` (four arguments) and
#   Chromium resolves @JavascriptInterface members by name AND arity and the
#   `KaTeXView.kt` host declared only a three-argument overload, so the call
#   died as "Method not found" *inside the page*: the callback threw out of
#   its requestAnimationFrame, `renderedBitmap` stayed null for the life of
#   the row and every uncached display formula kept its WebView alive. The
#   sibling host (`KatexWebViewPool`) had been updated in the same commit --
#   one side of a two-sided contract. Criterion: for each
#   `<iface>.<method>(...)` call site under assets/**/*.html, every Kotlin
#   file that registers that interface name must declare a matching arity.
#   Checked per file, because a union across hosts is exactly what hides this
#   bug shape.
echo "━━━ [24/112] KaTeX bridge arity guard ━━━"
if python3 scripts/scan/katex_bridge_arity_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

echo "━━━ [25/112] Gemini base version guard ━━━"
if python3 scripts/scan/gemini_base_version_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 25. Read-only-mount write guard (call-site coverage) ---
#   Added 2026-10-16 (deep-audit FIX-C 2b). `isLinuxPathUnderReadOnlyMount` is
#   the contract every host-file write site must consult; T219 wired it into
#   `tools/FileWriteTool`/`FileEditTool` only, so `minis-model-use --output`
#   (shapeRawPassthrough / writeRunOutput) wrote into read-only mounts while the
#   same path refused through `file_write`. Criterion: a function that takes a
#   caller-supplied path parameter and writes a host file must call the guard —
#   unless its file is exempt WITH evidence, and rotted evidence fails loudly.
echo "━━━ [26/112] Read-only-mount write guard ━━━"
if python3 scripts/scan/readonly_mount_write_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 25. Execution-gate guard (heavy-permit rollback / interruptible waits) ---
#   Added 2026-10-16 (deep-audit-1016 FIX-D). Three properties of
#   ExecutionCoordinator's two shell semaphores that no compiler or unit test
#   can see: (4b) every blocking `tryAcquire` must be routed through
#   `runInterruptible(Dispatchers.IO)`, otherwise a cancelled coroutine parks in
#   the wait for the whole timeout and the shell stays reserved; (4a) the
#   global-slot acquire is the only statement that can throw between taking the
#   heavy permit and the `try` that releases it, so it must hand that permit
#   back before rethrowing; (4c) the post-recycle GC loop must be deferred off
#   the main thread instead of running inline. Criterion: exactly two
#   `tryAcquire(` in the file (one per interruptible wrapper), each wrapper body
#   containing `runInterruptible(`, the rollback helper containing
#   catch+release+throw, the dispatch wrapper containing the main-thread check,
#   and the calibrated `Thread.sleep(120)` settle still in place. Losing an
#   anchor FAILS loudly rather than passing silently.
echo "━━━ [27/112] Execution-gate guard ━━━"
if python3 scripts/scan/exec_gate_release_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 25. Scheduled-task store release guard (deep-audit-1016 B-7c) ---
#   Every function in ScheduledTasksStore.kt that drops tasks from the
#   persisted document must notify ScheduledTaskRunner, from OUTSIDE the store
#   lock. Missing call = the deleted task keeps the in-process single-flight
#   lock until the reaper fires (~30 min); a call nested inside the store lock
#   inverts the lock order against the runner's checkMutex and deadlocks.
echo "━━━ [28/112] Scheduled-task store release guard ━━━"
if python3 scripts/scan/scheduled_store_release_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 29. Tool-result image consumer guard (deep-audit-1017 FIX-A / ISSUE-01) ---
#   Every provider serializer that walks ToolResult must also put the attached
#   image on the wire: OpenAI (chat + responses) dropped it entirely while both
#   siblings already carried it, so the model saw the caption and answered blind.
#   vision-unsupported models must emit the shared T264 placeholder instead.
echo "━━━ [29/112] Tool-result image consumer guard ━━━"
if python3 scripts/scan/tool_result_image_branch_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 30. Turn-tail finalize order (deep-audit-1017 FIX-B / ISSUE-02) ---
#   The turn-tail row finalize must sit BELOW every `continue` branch of the
#   agent loop and must run exactly once: finalizing before a continue freezes
#   the assistant row (canonical isStreaming=false) and the later writes are
#   dropped by StaleStreamPublishPolicy. The turn-visibility predicate must read
#   per-turn values, never the run-scoped accumulator.
echo "━━━ [30/112] Turn-tail finalize order ━━━"
if python3 scripts/scan/turn_tail_finalize_order.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 31. Sub-agent turn hand-back guard (deep-audit-1017 FIX-C / ISSUE-04) ---
#   Every continue branch that hands a turn back to the model must first append
#   the turn's visible text to the history as ASSISTANT (otherwise the model
#   cannot see what it already wrote and rewrites it -> duplicated body), and
#   the loop-wrapping catch(Exception) must rethrow CancellationException (a
#   user stop was otherwise recorded as a sub-agent failure).
echo "━━━ [31/112] Sub-agent turn hand-back guard ━━━"
if python3 scripts/scan/subagent_handback_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 32. Calendar Events nullable-column predicate guard (deep-audit-1017 FIX-D / ISSUE-06) ---
#   Events.DTEND is NULL for every recurring row (the provider rejects DTEND and
#   DURATION together), so a selection comparing it is never true -- freebusy
#   answered "free" for windows holding a weekly occurrence. Predicates must be
#   built on CalendarContract.Instances.
echo "━━━ [32/112] Calendar Events nullable-column predicate guard ━━━"
if python3 scripts/scan/calendar_events_nullable_predicate_check.py --root "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 33. android-shizuku-cli argv guard (deep-audit-1017 FIX-E / ISSUE-08) ---
#   `input text` must emit the platform's %s escape (a bare % types a literal
#   '%'); `notification channel` must read parsed positionals and declare its
#   value-less switches (otherwise a flag eats the package and is handed to
#   `cmd notification` as the target); `package clear --cache-only` must be a
#   per-package clear and must report the scope it actually cleared.
echo "━━━ [33/112] android-shizuku-cli argv guard ━━━"
if python3 scripts/scan/shizuku_args_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 34. Shell marker boundary guard (deep-audit-1017 FIX-F / ISSUE-09) ---
#   internalScanMarker must only report a hit once internalParseMinisExitCode
#   yields a real code: a bare prefix hit (the `_EXIT_<n>__` marker split at the
#   4096-char read boundary) finished the command with -1, which
#   ExecutionCoordinator maps to SHELL_DIED -> shell rebuild + retry, and the
#   remaining output of that command was discarded.
echo "━━━ [34/112] Shell marker boundary guard ━━━"
if python3 scripts/scan/marker_boundary_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 35. Accessibility CLI contract guard (deep-audit-1017 FIX-G / ISSUE-10) ---
#   A late screenshot reply must not be published into a slot whose caller has
#   already left (and the bitmap must be recycled), `tap text --contains` needs
#   "contains" declared in booleanFlags, tapText must propagate the click result
#   instead of always reporting ok, and starting the recognizer twice must tear
#   down the previous session and report the on-device state that is used.
echo "━━━ [35/112] Accessibility CLI contract guard ━━━"
if python3 scripts/scan/a11y_handler_contract_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 36. TTS voice channel + sanitizer wiring (deep-audit-1016c FIX-E / ISSUE-07) ---
#   android-speak --voice used to be silently overwritten by speak()'s
#   unconditional auto-detect (zh/en binary), and the JSON body echoed the
#   requested tag as if chosen; VoiceTextSanitizer existed with tests but had
#   zero production call sites. This gate pins: single-point language
#   resolution (policy + honest effective-voice report), sanitize-before-
#   engine-call with an honest nothing_to_speak bail, and the streaming path
#   keeping its auto-detection.
echo "━━━ [36/112] TTS voice channel + sanitizer wiring ━━━"
if python3 scripts/scan/tts_voice_and_sanitizer_check.py --root "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 37. Loop persist slice guard (ISSUE-01) ---
echo "━━━ [37/112] Loop persist slice guard (ISSUE-01) ━━━"
if python3 scripts/scan/loop_persist_slice_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 38. Offload host-path injection guard (ISSUE-02) ---
echo "━━━ [38/112] Offload host-path injection guard (ISSUE-02) ━━━"
if python3 scripts/scan/offload_host_path_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 39. Browser tab exec serialisation guard (ISSUE-03A) ---
echo "━━━ [39/112] Browser tab exec serialisation guard (ISSUE-03A) ━━━"
if python3 scripts/scan/browser_tab_exec_serial_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 40. Downloads atomic update guard (ISSUE-03C) ---
echo "━━━ [40/112] Downloads atomic update guard (ISSUE-03C) ━━━"
if python3 scripts/scan/downloads_atomic_update_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 41. Compact decision gate coverage (ISSUE-04/05) ---
echo "━━━ [41/112] Compact decision gate coverage (ISSUE-04/05) ━━━"
if python3 scripts/scan/compact_decision_gate_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 42. Schedule date source guard (ISSUE-09) ---
echo "━━━ [42/112] Schedule date source guard (ISSUE-09) ━━━"
if python3 scripts/scan/schedule_date_source_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 43. SysInfo pending-state thread guard (ISSUE-10) ---
echo "━━━ [43/112] SysInfo pending-state thread guard (ISSUE-10) ━━━"
if python3 scripts/scan/sysinfo_state_thread_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 44. RSS probe lock scope guard (ISSUE-11) ---
echo "━━━ [44/112] RSS probe lock scope guard (ISSUE-11) ━━━"
if python3 scripts/scan/rss_probe_lock_scope_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 45. A/ISSUE-01 Truncated tool-call guard ---
echo "━━━ [45/112] A/ISSUE-01 Truncated tool-call guard ━━━"
if python3 scripts/scan/truncated_call_guard_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 46. B/ISSUE-04 outcomeKnown chain ---
echo "━━━ [46/112] B/ISSUE-04 outcomeKnown chain ━━━"
if python3 scripts/scan/outcome_known_chain_check.py --root "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 47. C/ISSUE-07 compact pin truth ---
echo "━━━ [47/112] C/ISSUE-07 compact pin truth ━━━"
if python3 scripts/scan/compact_pin_truth_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 48. D/ISSUE-08 dao.loadMessages funnel ---
echo "━━━ [48/112] D/ISSUE-08 dao.loadMessages funnel ━━━"
if python3 scripts/scan/dao_loadmessages_check.py --root "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 49. E/ISSUE-09 backup remap + rollup ---
echo "━━━ [49/112] E/ISSUE-09 backup remap + rollup ━━━"
if python3 scripts/scan/backup_remap_rollup_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 50. F/ISSUE-10 router/session-slot locks ---
echo "━━━ [50/112] F/ISSUE-10 router/session-slot locks ━━━"
if python3 scripts/scan/router_lock_check.py --root "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 51. H/ISSUE-12a/b modelservice single-read + worker cap ---
echo "━━━ [51/112] H/ISSUE-12a/b modelservice single-read + worker cap ━━━"
if python3 scripts/scan/modelsvc_single_read_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 52. A/ISSUE-01 modeluse envelope dialect single-source ---
echo "━━━ [52/112] A/ISSUE-01 modeluse envelope dialect single-source ━━━"
if python3 scripts/scan/modeluse_proto_single_source_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 53. B/ISSUE-02 tool status publish pass ---
echo "━━━ [53/112] B/ISSUE-02 tool status publish pass ━━━"
if python3 scripts/scan/loop_tool_status_transition_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 54. C/ISSUE-03 fastio tar field bounds + denied exit ---
echo "━━━ [54/112] C/ISSUE-03 fastio tar field bounds + denied exit ━━━"
if python3 scripts/scan/fastio_tar_bounds_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 55. D/ISSUE-04 provider customUserAgent parity + EOF ---
echo "━━━ [55/112] D/ISSUE-04 provider customUserAgent parity + EOF ━━━"
if python3 scripts/scan/provider_ua_parity_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 56. E/ISSUE-05 session provider enabled gate ---
echo "━━━ [56/112] E/ISSUE-05 session provider enabled gate ━━━"
if python3 scripts/scan/session_provider_enabled_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 57. F/ISSUE-06 parts_json funnel ---
echo "━━━ [57/112] F/ISSUE-06 parts_json funnel ━━━"
if python3 scripts/scan/parts_json_funnel_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 58. G/ISSUE-07 katex ready race + alias lock ---
echo "━━━ [58/112] G/ISSUE-07 katex ready race + alias lock ━━━"
if python3 scripts/scan/katex_ready_race_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 59. H/ISSUE-08 browser teardown wake-up ---
echo "━━━ [59/112] H/ISSUE-08 browser teardown wake-up ━━━"
if python3 scripts/scan/browser_destroy_wakeup_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 60. J/ISSUE-10 contacts argv + config cwd ---
echo "━━━ [60/112] J/ISSUE-10 contacts argv + config cwd ━━━"
if python3 scripts/scan/offload_cli_args_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 61. A/ISSUE-01 provider models-cache wiring ---
echo "━━━ [61/112] A/ISSUE-01 provider models-cache wiring ━━━"
if python3 scripts/scan/provider_models_cache_wiring_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 62. D/ISSUE-04 webview holder url source ---
echo "━━━ [62/112] D/ISSUE-04 webview holder url source ━━━"
if python3 scripts/scan/webview_holder_url_source_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 63. E/ISSUE-05 voice cancel guard ---
echo "━━━ [63/112] E/ISSUE-05 voice cancel guard ━━━"
if python3 scripts/scan/voice_cancel_guard_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 64. F/ISSUE-06 deeplink settings routes ---
echo "━━━ [64/112] F/ISSUE-06 deeplink settings routes ━━━"
if python3 scripts/scan/deeplink_settings_routes_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 65. G/ISSUE-07 memory gate + provider UA fields ---
echo "━━━ [65/112] G/ISSUE-07 memory gate + provider UA fields ━━━"
if python3 scripts/scan/memory_gate_provider_fields_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 66. H/ISSUE-08 filepreview print bounded read ---
echo "━━━ [66/112] H/ISSUE-08 filepreview print bounded read ━━━"
if python3 scripts/scan/filepreview_print_bounded_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 67. A/ISSUE-01+10 skill restore preferredId + session cleanup ---
echo "━━━ [67/112] A/ISSUE-01+10 skill restore preferredId + session cleanup ━━━"
if python3 scripts/scan/skill_restore_session_cleanup_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 68. B/ISSUE-02+09 provider import id + backup field parity ---
echo "━━━ [68/112] B/ISSUE-02+09 provider import id + backup field parity ━━━"
if python3 scripts/scan/provider_import_id_roundtrip_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 69. C/ISSUE-03 mirror category detail route wiring ---
echo "━━━ [69/112] C/ISSUE-03 mirror category detail route wiring ━━━"
if python3 scripts/scan/mirror_category_route_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 70. D/ISSUE-05 chat menu prefs listener keys + order reload ---
echo "━━━ [70/112] D/ISSUE-05 chat menu prefs listener keys + order reload ━━━"
if python3 scripts/scan/chat_menu_prefs_listener_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 71. E/ISSUE-06 skill file save result + IO dispatch ---
echo "━━━ [71/112] E/ISSUE-06 skill file save result + IO dispatch ━━━"
if python3 scripts/scan/skill_file_save_result_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 72. F/ISSUE-07 webapp asset url single-source + addtohome cleanup ---
echo "━━━ [72/112] F/ISSUE-07 webapp asset url single-source + addtohome cleanup ━━━"
if python3 scripts/scan/webapp_asset_url_cleanup_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 73. G/ISSUE-08 speech active engine binding ---
echo "━━━ [73/112] G/ISSUE-08 speech active engine binding ━━━"
if python3 scripts/scan/speech_active_engine_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 74. H/ISSUE-11 open-url broker scheme gate ---
echo "━━━ [74/112] H/ISSUE-11 open-url broker scheme gate ━━━"
if python3 scripts/scan/open_url_broker_scheme_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 75. A/ISSUE-01 offload media base64 wire ---
echo "━━━ [75/112] A/ISSUE-01 offload media base64 wire ━━━"
if python3 scripts/scan/offload_media_b64_wire_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 76. B/ISSUE-02 draft session binding ---
echo "━━━ [76/112] B/ISSUE-02 draft session binding ━━━"
if python3 scripts/scan/session_sheet_id_binding_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 77. C/ISSUE-03 mediaplayer release guard ---
echo "━━━ [77/112] C/ISSUE-03 mediaplayer release guard ━━━"
if python3 scripts/scan/mediaplayer_release_guard.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 78. C/ISSUE-08 history save snapshot ---
echo "━━━ [78/112] C/ISSUE-08 history save snapshot ━━━"
if python3 scripts/scan/browser_history_snapshot_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 79. D/ISSUE-04 openminis compat field parity ---
echo "━━━ [79/112] D/ISSUE-04 openminis compat field parity ━━━"
if python3 scripts/scan/openminis_compat_field_parity_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 80. E/ISSUE-05 entry id rename integrity ---
echo "━━━ [80/112] E/ISSUE-05 entry id rename integrity ━━━"
if python3 scripts/scan/entry_id_rename_integrity_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 81. F/ISSUE-06 mirror vm thread/state ---
echo "━━━ [81/112] F/ISSUE-06 mirror vm thread/state ━━━"
if python3 scripts/scan/mirror_vm_thread_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 82. G/ISSUE-07 cookie section refresh ---
echo "━━━ [82/112] G/ISSUE-07 cookie section refresh ━━━"
if python3 scripts/scan/browser_cookie_ui_refresh_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 83. G/ISSUE-09 tool title error branch ---
echo "━━━ [83/112] G/ISSUE-09 tool title error branch ━━━"
if python3 scripts/scan/tool_title_error_branch_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 84. H/ISSUE-10 share dir prune wiring ---
echo "━━━ [84/112] H/ISSUE-10 share dir prune wiring ━━━"
if python3 scripts/scan/share_dir_prune_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 85. H/1021-V file preview url encode ---
echo "━━━ [85/112] H/1021-V file preview url encode ━━━"
if python3 scripts/scan/file_preview_url_encode_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 86. A/ISSUE-01 file provider paths coverage ---
echo "━━━ [86/112] A/ISSUE-01 file provider paths coverage ━━━"
if python3 scripts/scan/file_provider_paths_coverage_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 87. B/ISSUE-02 model entry override parity ---
echo "━━━ [87/112] B/ISSUE-02 model entry override parity ━━━"
if python3 scripts/scan/model_entry_override_parity_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 88. C/ISSUE-03 compact entry pin migration ---
echo "━━━ [88/112] C/ISSUE-03 compact entry pin migration ━━━"
if python3 scripts/scan/compact_entry_pin_migration_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 89. C/ISSUE-09 entry composite key guard ---
echo "━━━ [89/112] C/ISSUE-09 entry composite key guard ━━━"
if python3 scripts/scan/entry_composite_key_guard_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 90. D/ISSUE-04 collection copy on mutate ---
echo "━━━ [90/112] D/ISSUE-04 collection copy on mutate ━━━"
if python3 scripts/scan/collection_copy_on_mutate_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 91. E/ISSUE-05 skill frontmatter roundtrip ---
echo "━━━ [91/112] E/ISSUE-05 skill frontmatter roundtrip ━━━"
if python3 scripts/scan/skill_frontmatter_roundtrip_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 92. E/ISSUE-05 skill import off-main ---
echo "━━━ [92/112] E/ISSUE-05 skill import off-main ━━━"
if python3 scripts/scan/skill_import_offmain_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 93. F/ISSUE-06 chat session id binding ---
echo "━━━ [93/112] F/ISSUE-06 chat session id binding ━━━"
if python3 scripts/scan/chat_session_id_binding_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 94. G/ISSUE-07 memory ui wiring ---
echo "━━━ [94/112] G/ISSUE-07 memory ui wiring ━━━"
if python3 scripts/scan/memory_ui_wiring_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 95. H/ISSUE-08 file uri parse guard ---
echo "━━━ [95/112] H/ISSUE-08 file uri parse guard ━━━"
if python3 scripts/scan/file_uri_parse_guard_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 96. I/ISSUE-10 backup streaming export ---
echo "━━━ [96/112] I/ISSUE-10 backup streaming export ━━━"
if python3 scripts/scan/backup_streaming_export_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 97. trace step tag (analyser wiring + contract) ---
echo "━━━ [97/112] trace step tag ━━━"
if python3 scripts/scan/trace_step_tag_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 98. D/ISSUE-04 MCP OAuth credentials & bridge lifecycle ---
echo "━━━ [98/112] MCP OAuth lifecycle ━━━"
if python3 scripts/scan/mcp_oauth_lifecycle_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 99. F/ISSUE-06 thinking-rule editor preserve ---
echo "━━━ [99/112] thinking-rule editor preserve ━━━"
if python3 scripts/scan/thinking_rule_editor_preserve_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 100. H/ISSUE-08 offload permission reset sync ---
echo "━━━ [100/112] offload permission reset sync ━━━"
if python3 scripts/scan/offload_permission_reset_sync_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 101. I/ISSUE-09 offload relative path unify (ModelUse) ---
echo "━━━ [101/112] offload relative path unify ━━━"
if python3 scripts/scan/offload_relative_path_unify_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi


# --- 102. D/ISSUE-04 browser tabs lifecycle (deleted sessions + orphans) ---
echo "━━━ [102/112] browser_tabs_lifecycle_check ━━━"
if python3 scripts/scan/browser_tabs_lifecycle_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 103. E/ISSUE-05 envvar config surface group field ---
echo "━━━ [103/112] envvar_config_group_check ━━━"
if python3 scripts/scan/envvar_config_group_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 104. F/ISSUE-06 provider list remember keys ---
echo "━━━ [104/112] provider_remember_key_check ━━━"
if python3 scripts/scan/provider_remember_key_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 105. I/ISSUE-09 provider rpc symmetry (debug vs mutation) ---
echo "━━━ [105/112] provider_rpc_symmetry_check ━━━"
if python3 scripts/scan/provider_rpc_symmetry_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi


# --- 106. D/ISSUE-04 models collection provider field + composite-key normalization ---
echo "━━━ [106/112] models_provider_field_check ━━━"
if python3 scripts/scan/models_provider_field_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi


# --- 107. ARCH-01 provider write rules single source (W1-W4) ---
echo "━━━ [107/112] provider_write_rules_check ━━━"
if python3 scripts/scan/provider_write_rules_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 108. ARCH-03 provider type registry exhaustiveness (T1-T4) ---
echo "━━━ [108/112] provider_type_registry_check ━━━"
if python3 scripts/scan/provider_type_registry_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 109. dv16 FIX-4 chat UI interaction wiring (slash MCP rows / main-chat \
#     table copy actions + per-table registry key / swipe send eligibility) ---
echo "━━━ [109/112] chat_ui_interaction_check ━━━"
if python3 scripts/scan/chat_ui_interaction_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 110. deep-audit-1046 B3 Anthropic thinking budget (budget_tokens must be derived from the
#     clamped max_tokens; no interleaved-thinking beta while thinking is disabled) ---
echo "━━━ [110/112] anthropic_thinking_budget_check ━━━"
if python3 scripts/scan/anthropic_thinking_budget_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 111. deep-audit-1046 C6 tool-overlay show/hide intent latch (async isShown cannot gate
#     hide(); the queued attach must re-check the intent) ---
echo "━━━ [111/112] overlay_show_hide_latch_check ━━━"
if python3 scripts/scan/overlay_show_hide_latch_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi

# --- 112. auto-approve mode overlays stay behind their designed single
#     points (policy consult in write path / single gate call site / pure
#     level policy / mode invisible to minis-config) ---
echo "━━━ [112/112] auto_approve_bypass_check ━━━"
if python3 scripts/scan/auto_approve_bypass_check.py "$ROOT"; then
    PASS=$((PASS + 1))
    echo ""
else
    RC=1
    FAIL=$((FAIL + 1))
    echo ""
fi
