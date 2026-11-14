package com.rikkaminis.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.rikkaminis.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.data.ConcurrencyPrefs
import com.rikkaminis.app.data.SubagentPrefs
import com.rikkaminis.app.ui.components.MinisTextButton

/**
 * [feat/runtime-limits-panel] Runtime Limits — the ONE page that groups every
 * user-tunable agent runtime knob:
 *
 *   1. Sessions & dispatch  — sub-agent switch, concurrent-session cap (+live
 *      running/waiting occupancy).
 *   2. Agent-loop budget    — turns / provider attempts / tool calls / shell
 *                             commands / compaction / concurrent tools / run
 *                             deadline (all enforced, run-resumable).
 *   3. Stream recovery      — length-wall & EOF continuations, deterministic
 *                             empty fast-exit, transient retries, verify
 *                             nudges.
 *   4. Network & worker     — generation hard wall, first-chunk budgets,
 *                             worker slot pool + queue admission.
 *
 * Design rules (user's explicit asks):
 *   - The Settings entry row stays SHORT (one-line summary only); everything
 *     detailed — risk notes, effective-when notes — lives HERE, inside the
 *     page, as section footers / per-row subtitles.
 *   - Defaults == the previously hard-coded constants, so an untouched
 *     install behaves identically to the pre-panel build.
 *
 * All edits are local state; the single Save action (top-right app bar,
 * [ui/runtime-page-adjust-1006]) persists every group in one shot
 * (ConcurrencyPrefs + SubagentPrefs + AgentRuntimeLimitsPrefs).
 *
 * [ui/runtime-page-adjust-1006] The pinned compaction-model picker moved to
 * ModelGroupsScreen (Settings → Model Groups): it is a model-routing role
 * choice, not a runtime budget knob, and it now saves immediately there
 * (single-key save) instead of riding this page's batched Save.
 */
@Composable
fun RuntimeLimitsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // ── Group 1 state ──
    var subagentEnabled by remember { mutableStateOf(SubagentPrefs.isEnabled(context)) }
    var maxSessions by remember { mutableStateOf(ConcurrencyPrefs.maxConcurrentSessions()) }
    val occupancy by produceState(
        initialValue = com.rikkaminis.app.service.SessionConcurrencyManager.occupancy(),
    ) {
        while (true) {
            value = com.rikkaminis.app.service.SessionConcurrencyManager.occupancy()
            kotlinx.coroutines.delay(1000L)
        }
    }

    // ── Group 2 state ──
    var maxTurns by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxTurns()) }
    var maxProviderAttempts by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxProviderAttempts()) }
    var maxToolCalls by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxToolCalls()) }
    var maxShellCommands by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxShellCommands()) }
    var maxCompactionCalls by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxCompactionCalls()) }
    var maxConcurrentTools by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxConcurrentTools()) }
    var runDeadlineMin by remember { mutableStateOf(AgentRuntimeLimitsPrefs.runDeadlineMinutes()) }

    // ── Group 3 state ──
    var lengthWallContinues by remember { mutableStateOf(AgentRuntimeLimitsPrefs.lengthWallContinues()) }
    var eofStubContinues by remember { mutableStateOf(AgentRuntimeLimitsPrefs.eofStubContinues()) }
    var deterministicEmptyLimit by remember { mutableStateOf(AgentRuntimeLimitsPrefs.deterministicEmptyLimit()) }
    var transientRetries by remember { mutableStateOf(AgentRuntimeLimitsPrefs.transientRetries()) }
    var verifyNudges by remember { mutableStateOf(AgentRuntimeLimitsPrefs.verifyNudges()) }

    // ── Group 4 state ──
    var generationTimeoutMin by remember { mutableStateOf(AgentRuntimeLimitsPrefs.generationTimeoutMinutes()) }
    var firstChunkDirectSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.firstChunkDirectSec()) }
    var firstChunkProxySec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.firstChunkProxySec()) }
    var providerSlots by remember { mutableStateOf(AgentRuntimeLimitsPrefs.providerSlots()) }
    var queueAdmission by remember { mutableStateOf(AgentRuntimeLimitsPrefs.queueAdmission()) }
    // [feat/chat-tuning-panel-b] Group 5 + 6.
    var autoCompactMinTailTokens by remember { mutableStateOf(AgentRuntimeLimitsPrefs.autoCompactMinTailTokens()) }
    var autoCompactMinIntervalMin by remember { mutableStateOf(AgentRuntimeLimitsPrefs.autoCompactMinIntervalMin()) }
    // [feat/compact-model-pin-1005 → ui/runtime-page-adjust-1006] The picker
    // moved to ModelGroupsScreen (Settings → Model Groups), where it now saves
    // immediately via AgentRuntimeLimitsPrefs.save(compactModelEntryId=...) —
    // it is a model-routing role choice, not a runtime budget knob, and it no
    // longer participates in this page's batched Save.
    var memoryInjectLines by remember { mutableStateOf(AgentRuntimeLimitsPrefs.memoryInjectLines()) }
    var memoryRollupInjectKb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.memoryRollupInjectKb()) }
    var memorySearchLines by remember { mutableStateOf(AgentRuntimeLimitsPrefs.memorySearchLines()) }
    var memoryLookbackDays by remember { mutableStateOf(AgentRuntimeLimitsPrefs.memoryLookbackDays()) }
    var imageMaxPerImageMb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageMaxPerImageMb()) }
    var imageMaxTotalMb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageMaxTotalMb()) }
    var imageMaxRequestMb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageMaxRequestMb()) }
    var imageMaxEdgePx by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageMaxEdgePx()) }
    var imageJpegQuality by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageJpegQuality()) }
    var browserNavTimeoutSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.browserNavTimeoutSec()) }
    var browserDomStableSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.browserDomStableSec()) }
    var browserScreenshotQuality by remember { mutableStateOf(AgentRuntimeLimitsPrefs.browserScreenshotQuality()) }
    var shellOutputKb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.shellOutputKb()) }
    var shellTimeoutSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.shellTimeoutSec()) }
    // [feat/runtime-sandbox-knobs] Sandbox lifecycle group.
    var guestTmpMaxAgeMin by remember { mutableStateOf(AgentRuntimeLimitsPrefs.guestTmpMaxAgeMin()) }
    var guestTmpSweepIntervalSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.guestTmpSweepIntervalSec()) }
    var stallNoProgressSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.stallNoProgressSec()) }
    var shellIdleTimeoutMin by remember { mutableStateOf(AgentRuntimeLimitsPrefs.shellIdleTimeoutMin()) }
    var heavyGateTimeoutSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.heavyGateTimeoutSec()) }

    // [RC-1114 U-17] Drives the "restore defaults" confirmation at the bottom
    // of the page. Declared here (not inline in composition) so the dialog's
    // open state survives recomposition like every other dialog on this screen.
    var showRestoreDefaults by remember { mutableStateOf(false) }

    // [FIX-6 / F-241] Re-read every snapshot when the backing prefs change
    // OUTSIDE this screen — `minis-config set runtime.*` (ConfigBuiltins
    // registers all of these), a backup-restore, or the agent. Without this
    // the `remember{}` snapshot is stale forever (see the identical rationale
    // in AppearanceScreen's [T-backup-import-refresh] listener).
    //
    // This screen is the one where staleness is DATA-DESTRUCTIVE rather than
    // cosmetic: Save writes back all 40 knobs in one shot, so the sequence
    // "open page (old values) → change one slider → Save" silently rolls every
    // other field back to the values captured at open time — and
    // AgentRuntimeLimitsPrefs' write path refreshes its cache, so the rollback
    // takes effect at runtime immediately.
    // [fix/dv22-b1-prefs-listener-filter] The re-read is now PER KEY (one
    // `when (key)` arm per control) and Save captures its argument values into
    // locals BEFORE the first write — see the [dv22-b1] comments at the
    // listener and at Save for the synchronous-apply mechanics this closes.
    //
    // Deliberately NOT a shared abstraction: the three other screens that need
    // the same treatment each read a different prefs file with a different
    // shape (see FIX-6 REPORT.md), and a generic helper would have to own all
    // of their key lists to be worth its weight.
    DisposableEffect(context) {
        val app = context.applicationContext
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // [fix/da1026-prefs-priming] Prime FIRST, then read. SharedPreferencesImpl dispatches
            // pending callbacks in unspecified order, so this screen's listener can run BEFORE
            // ConfigBuiltins' own re-priming listener -- without the primes here the getters
            // below would read whatever cache state happened to be current, i.e. the value from
            // before the write that triggered this callback. And this screen's Save re-persists
            // every knob it shows, so a stale read here is not cosmetic: it would silently roll
            // the external write back on the next Save. (ConfigBuiltins primes too -- this is the
            // ordering-independent belt, not a duplicate of the core fix.)
            ConcurrencyPrefs.prime(app)
            AgentRuntimeLimitsPrefs.prime(app)
            // [fix/dv22-b1-prefs-listener-filter] Re-read ONLY the control the changed key owns.
            // AOSP SharedPreferencesImpl.apply() notifies registered listeners
            // SYNCHRONOUSLY on the main thread (notifyListeners runs the callbacks inline before
            // apply() returns), and Save writes three prefs files in one onClick -- the first
            // apply() fires this listener before the later writes evaluate their arguments, so
            // an unkeyed body that resets every state would make Save itself roll the user's
            // other edits back to disk values (dv22-B-01). Per-key arms keep the external
            // refresh behavior (minis-config / backup restore / the agent) for the ONE key that
            // actually changed without ever touching the 39 other edit buffers.
            when (key) {
                SubagentPrefs.KEY_ENABLED ->
                    subagentEnabled = SubagentPrefs.isEnabled(context)
                ConcurrencyPrefs.KEY_MAX_CONCURRENT_SESSIONS ->
                    maxSessions = ConcurrencyPrefs.maxConcurrentSessions()
                AgentRuntimeLimitsPrefs.KEY_MAX_TURNS ->
                    maxTurns = AgentRuntimeLimitsPrefs.maxTurns()
                AgentRuntimeLimitsPrefs.KEY_MAX_PROVIDER_ATTEMPTS ->
                    maxProviderAttempts = AgentRuntimeLimitsPrefs.maxProviderAttempts()
                AgentRuntimeLimitsPrefs.KEY_MAX_TOOL_CALLS ->
                    maxToolCalls = AgentRuntimeLimitsPrefs.maxToolCalls()
                AgentRuntimeLimitsPrefs.KEY_MAX_SHELL_COMMANDS ->
                    maxShellCommands = AgentRuntimeLimitsPrefs.maxShellCommands()
                AgentRuntimeLimitsPrefs.KEY_MAX_COMPACTION_CALLS ->
                    maxCompactionCalls = AgentRuntimeLimitsPrefs.maxCompactionCalls()
                AgentRuntimeLimitsPrefs.KEY_MAX_CONCURRENT_TOOLS ->
                    maxConcurrentTools = AgentRuntimeLimitsPrefs.maxConcurrentTools()
                AgentRuntimeLimitsPrefs.KEY_RUN_DEADLINE_MIN ->
                    runDeadlineMin = AgentRuntimeLimitsPrefs.runDeadlineMinutes()
                AgentRuntimeLimitsPrefs.KEY_LENGTH_WALL_CONTINUES ->
                    lengthWallContinues = AgentRuntimeLimitsPrefs.lengthWallContinues()
                AgentRuntimeLimitsPrefs.KEY_EOF_STUB_CONTINUES ->
                    eofStubContinues = AgentRuntimeLimitsPrefs.eofStubContinues()
                AgentRuntimeLimitsPrefs.KEY_DETERMINISTIC_EMPTY_LIMIT ->
                    deterministicEmptyLimit = AgentRuntimeLimitsPrefs.deterministicEmptyLimit()
                AgentRuntimeLimitsPrefs.KEY_TRANSIENT_RETRIES ->
                    transientRetries = AgentRuntimeLimitsPrefs.transientRetries()
                AgentRuntimeLimitsPrefs.KEY_VERIFY_NUDGES ->
                    verifyNudges = AgentRuntimeLimitsPrefs.verifyNudges()
                AgentRuntimeLimitsPrefs.KEY_GENERATION_TIMEOUT_MIN ->
                    generationTimeoutMin = AgentRuntimeLimitsPrefs.generationTimeoutMinutes()
                AgentRuntimeLimitsPrefs.KEY_FIRST_CHUNK_DIRECT_SEC ->
                    firstChunkDirectSec = AgentRuntimeLimitsPrefs.firstChunkDirectSec()
                AgentRuntimeLimitsPrefs.KEY_FIRST_CHUNK_PROXY_SEC ->
                    firstChunkProxySec = AgentRuntimeLimitsPrefs.firstChunkProxySec()
                AgentRuntimeLimitsPrefs.KEY_PROVIDER_SLOTS ->
                    providerSlots = AgentRuntimeLimitsPrefs.providerSlots()
                AgentRuntimeLimitsPrefs.KEY_QUEUE_ADMISSION ->
                    queueAdmission = AgentRuntimeLimitsPrefs.queueAdmission()
                AgentRuntimeLimitsPrefs.KEY_AUTO_COMPACT_MIN_TAIL_TOKENS ->
                    autoCompactMinTailTokens = AgentRuntimeLimitsPrefs.autoCompactMinTailTokens()
                AgentRuntimeLimitsPrefs.KEY_AUTO_COMPACT_MIN_INTERVAL_MIN ->
                    autoCompactMinIntervalMin = AgentRuntimeLimitsPrefs.autoCompactMinIntervalMin()
                AgentRuntimeLimitsPrefs.KEY_MEMORY_INJECT_LINES ->
                    memoryInjectLines = AgentRuntimeLimitsPrefs.memoryInjectLines()
                AgentRuntimeLimitsPrefs.KEY_MEMORY_ROLLUP_INJECT_KB ->
                    memoryRollupInjectKb = AgentRuntimeLimitsPrefs.memoryRollupInjectKb()
                AgentRuntimeLimitsPrefs.KEY_MEMORY_SEARCH_LINES ->
                    memorySearchLines = AgentRuntimeLimitsPrefs.memorySearchLines()
                AgentRuntimeLimitsPrefs.KEY_MEMORY_LOOKBACK_DAYS ->
                    memoryLookbackDays = AgentRuntimeLimitsPrefs.memoryLookbackDays()
                AgentRuntimeLimitsPrefs.KEY_IMAGE_MAX_PER_IMAGE_MB ->
                    imageMaxPerImageMb = AgentRuntimeLimitsPrefs.imageMaxPerImageMb()
                AgentRuntimeLimitsPrefs.KEY_IMAGE_MAX_TOTAL_MB ->
                    imageMaxTotalMb = AgentRuntimeLimitsPrefs.imageMaxTotalMb()
                AgentRuntimeLimitsPrefs.KEY_IMAGE_MAX_REQUEST_MB ->
                    imageMaxRequestMb = AgentRuntimeLimitsPrefs.imageMaxRequestMb()
                AgentRuntimeLimitsPrefs.KEY_IMAGE_MAX_EDGE_PX ->
                    imageMaxEdgePx = AgentRuntimeLimitsPrefs.imageMaxEdgePx()
                AgentRuntimeLimitsPrefs.KEY_IMAGE_JPEG_QUALITY ->
                    imageJpegQuality = AgentRuntimeLimitsPrefs.imageJpegQuality()
                AgentRuntimeLimitsPrefs.KEY_BROWSER_NAV_TIMEOUT_SEC ->
                    browserNavTimeoutSec = AgentRuntimeLimitsPrefs.browserNavTimeoutSec()
                AgentRuntimeLimitsPrefs.KEY_BROWSER_DOM_STABLE_SEC ->
                    browserDomStableSec = AgentRuntimeLimitsPrefs.browserDomStableSec()
                AgentRuntimeLimitsPrefs.KEY_BROWSER_SCREENSHOT_QUALITY ->
                    browserScreenshotQuality = AgentRuntimeLimitsPrefs.browserScreenshotQuality()
                AgentRuntimeLimitsPrefs.KEY_SHELL_OUTPUT_KB ->
                    shellOutputKb = AgentRuntimeLimitsPrefs.shellOutputKb()
                AgentRuntimeLimitsPrefs.KEY_SHELL_TIMEOUT_SEC ->
                    shellTimeoutSec = AgentRuntimeLimitsPrefs.shellTimeoutSec()
                AgentRuntimeLimitsPrefs.KEY_GUEST_TMP_MAX_AGE_MIN ->
                    guestTmpMaxAgeMin = AgentRuntimeLimitsPrefs.guestTmpMaxAgeMin()
                AgentRuntimeLimitsPrefs.KEY_GUEST_TMP_SWEEP_INTERVAL_SEC ->
                    guestTmpSweepIntervalSec = AgentRuntimeLimitsPrefs.guestTmpSweepIntervalSec()
                AgentRuntimeLimitsPrefs.KEY_STALL_NO_PROGRESS_SEC ->
                    stallNoProgressSec = AgentRuntimeLimitsPrefs.stallNoProgressSec()
                AgentRuntimeLimitsPrefs.KEY_SHELL_IDLE_TIMEOUT_MIN ->
                    shellIdleTimeoutMin = AgentRuntimeLimitsPrefs.shellIdleTimeoutMin()
                AgentRuntimeLimitsPrefs.KEY_HEAVY_GATE_TIMEOUT_SEC ->
                    heavyGateTimeoutSec = AgentRuntimeLimitsPrefs.heavyGateTimeoutSec()
            }
        }
        val limitsPrefs = app.getSharedPreferences(AgentRuntimeLimitsPrefs.PREFS, Context.MODE_PRIVATE)
        val concurrencyPrefs = app.getSharedPreferences(ConcurrencyPrefs.PREFS, Context.MODE_PRIVATE)
        val subagentPrefs = app.getSharedPreferences(SubagentPrefs.PREFS, Context.MODE_PRIVATE)
        limitsPrefs.registerOnSharedPreferenceChangeListener(listener)
        concurrencyPrefs.registerOnSharedPreferenceChangeListener(listener)
        subagentPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            limitsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            concurrencyPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            subagentPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.runtime_limits_title),
        onBack = onBack,
        // [ui/runtime-page-adjust-1006] Save lives in the top bar (same
        // actions-slot pattern as ProviderDetailScreen / OffloadPermissionScreen):
        // this page is ~35 knobs tall, and a bottom-of-page Save forced a full
        // scroll after every edit. Same body, same save-and-exit semantics.
        actions = {
            MinisTextButton(
                onClick = {
                    // [fix/da1026-prefs-priming] Prime both caches before the
                    // batched write. This Save re-persists every knob on the
                    // page from screen state, so the write path must run on top
                    // of a fresh cache: if a change was written to either prefs
                    // file by someone other than this screen (minis-config / a
                    // backup restore / the agent) while the page was open, a
                    // stale read here would persist the pre-change value back
                    // over it. Belt for the write path — the load-bearing fix
                    // for the display half is the re-priming listener in the
                    // DisposableEffect above (and ConfigBuiltins' own listener).
                    // [fix/dv22-b1-prefs-listener-filter] Capture FIRST, write second. The first
                    // apply() below notifies this screen's own listener synchronously (see the
                    // comment in the listener above), so by the time the second and third writes
                    // evaluate their arguments the 40 state vars have already been re-read from
                    // disk. Reading the state vars at write time would therefore persist the
                    // RESET values and silently roll the user's edits back; an immutable local
                    // snapshot taken before the first write is immune to that reset.
                    val pendingSubagentEnabled = subagentEnabled
                    val pendingMaxSessions = maxSessions
                    val pendingMaxTurns = maxTurns
                    val pendingMaxProviderAttempts = maxProviderAttempts
                    val pendingMaxToolCalls = maxToolCalls
                    val pendingMaxShellCommands = maxShellCommands
                    val pendingMaxCompactionCalls = maxCompactionCalls
                    val pendingMaxConcurrentTools = maxConcurrentTools
                    val pendingRunDeadlineMin = runDeadlineMin
                    val pendingLengthWallContinues = lengthWallContinues
                    val pendingEofStubContinues = eofStubContinues
                    val pendingDeterministicEmptyLimit = deterministicEmptyLimit
                    val pendingTransientRetries = transientRetries
                    val pendingVerifyNudges = verifyNudges
                    val pendingGenerationTimeoutMin = generationTimeoutMin
                    val pendingFirstChunkDirectSec = firstChunkDirectSec
                    val pendingFirstChunkProxySec = firstChunkProxySec
                    val pendingProviderSlots = providerSlots
                    val pendingQueueAdmission = queueAdmission
                    val pendingAutoCompactMinTailTokens = autoCompactMinTailTokens
                    val pendingAutoCompactMinIntervalMin = autoCompactMinIntervalMin
                    val pendingMemoryInjectLines = memoryInjectLines
                    val pendingMemoryRollupInjectKb = memoryRollupInjectKb
                    val pendingMemorySearchLines = memorySearchLines
                    val pendingMemoryLookbackDays = memoryLookbackDays
                    val pendingImageMaxPerImageMb = imageMaxPerImageMb
                    val pendingImageMaxTotalMb = imageMaxTotalMb
                    val pendingImageMaxRequestMb = imageMaxRequestMb
                    val pendingImageMaxEdgePx = imageMaxEdgePx
                    val pendingImageJpegQuality = imageJpegQuality
                    val pendingBrowserNavTimeoutSec = browserNavTimeoutSec
                    val pendingBrowserDomStableSec = browserDomStableSec
                    val pendingBrowserScreenshotQuality = browserScreenshotQuality
                    val pendingShellOutputKb = shellOutputKb
                    val pendingShellTimeoutSec = shellTimeoutSec
                    val pendingGuestTmpMaxAgeMin = guestTmpMaxAgeMin
                    val pendingGuestTmpSweepIntervalSec = guestTmpSweepIntervalSec
                    val pendingStallNoProgressSec = stallNoProgressSec
                    val pendingShellIdleTimeoutMin = shellIdleTimeoutMin
                    val pendingHeavyGateTimeoutSec = heavyGateTimeoutSec
                    ConcurrencyPrefs.prime(context)
                    AgentRuntimeLimitsPrefs.prime(context)
                    SubagentPrefs.setEnabled(context, pendingSubagentEnabled)
                    ConcurrencyPrefs.setMaxConcurrentSessions(context, pendingMaxSessions)
                    AgentRuntimeLimitsPrefs.save(
                        context = context,
                        maxTurns = pendingMaxTurns,
                        maxProviderAttempts = pendingMaxProviderAttempts,
                        maxToolCalls = pendingMaxToolCalls,
                        maxShellCommands = pendingMaxShellCommands,
                        maxCompactionCalls = pendingMaxCompactionCalls,
                        maxConcurrentTools = pendingMaxConcurrentTools,
                        runDeadlineMinutes = pendingRunDeadlineMin,
                        lengthWallContinues = pendingLengthWallContinues,
                        eofStubContinues = pendingEofStubContinues,
                        deterministicEmptyLimit = pendingDeterministicEmptyLimit,
                        transientRetries = pendingTransientRetries,
                        verifyNudges = pendingVerifyNudges,
                        generationTimeoutMinutes = pendingGenerationTimeoutMin,
                        firstChunkDirectSec = pendingFirstChunkDirectSec,
                        firstChunkProxySec = pendingFirstChunkProxySec,
                        providerSlots = pendingProviderSlots,
                        queueAdmission = pendingQueueAdmission,
                        autoCompactMinTailTokens = pendingAutoCompactMinTailTokens,
                        autoCompactMinIntervalMin = pendingAutoCompactMinIntervalMin,
                        memoryInjectLines = pendingMemoryInjectLines,
                        memoryRollupInjectKb = pendingMemoryRollupInjectKb,
                        memorySearchLines = pendingMemorySearchLines,
                        memoryLookbackDays = pendingMemoryLookbackDays,
                        imageMaxPerImageMb = pendingImageMaxPerImageMb,
                        imageMaxTotalMb = pendingImageMaxTotalMb,
                        imageMaxRequestMb = pendingImageMaxRequestMb,
                        imageMaxEdgePx = pendingImageMaxEdgePx,
                        imageJpegQuality = pendingImageJpegQuality,
                        browserNavTimeoutSec = pendingBrowserNavTimeoutSec,
                        browserDomStableSec = pendingBrowserDomStableSec,
                        browserScreenshotQuality = pendingBrowserScreenshotQuality,
                        shellOutputKb = pendingShellOutputKb,
                        shellTimeoutSec = pendingShellTimeoutSec,
                        guestTmpMaxAgeMin = pendingGuestTmpMaxAgeMin,
                        guestTmpSweepIntervalSec = pendingGuestTmpSweepIntervalSec,
                        stallNoProgressSec = pendingStallNoProgressSec,
                        shellIdleTimeoutMin = pendingShellIdleTimeoutMin,
                        heavyGateTimeoutSec = pendingHeavyGateTimeoutSec,
                    )
                    onBack()
                },
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        // scaffold already wraps content in verticalScroll (default true) —
        // no nested scrolling here.
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {

            // ── 1. Sessions & dispatch ───────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_sessions)) {
                LimitsSwitchRow(
                    title = stringResource(R.string.settings_subagent_dispatch),
                    subtitle = stringResource(R.string.settings_subagent_dispatch_subtitle),
                    checked = subagentEnabled,
                    onCheckedChange = { subagentEnabled = it },
                    showDivider = true,
                )
                LimitsSliderRow(
                    title = stringResource(R.string.settings_max_concurrent),
                    valueLabel = stringResource(
                        R.string.settings_max_concurrent_subtitle,
                        maxSessions, occupancy.active, occupancy.waiting,
                    ),
                    value = maxSessions,
                    min = ConcurrencyPrefs.MIN,
                    max = ConcurrencyPrefs.MAX,
                    onCommit = { maxSessions = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_sessions_footer))

            // ── 2. Agent-loop budget ─────────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_budget)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_turns),
                    subtitle = stringResource(R.string.runtime_limits_turns_desc),
                    value = maxTurns,
                    min = AgentRuntimeLimitsPrefs.TURNS_MIN,
                    max = AgentRuntimeLimitsPrefs.TURNS_MAX,
                    onCommit = { maxTurns = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_provider_attempts),
                    subtitle = stringResource(R.string.runtime_limits_provider_attempts_desc),
                    value = maxProviderAttempts,
                    min = AgentRuntimeLimitsPrefs.PROVIDER_ATTEMPTS_MIN,
                    max = AgentRuntimeLimitsPrefs.PROVIDER_ATTEMPTS_MAX,
                    onCommit = { maxProviderAttempts = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_tool_calls),
                    subtitle = stringResource(R.string.runtime_limits_tool_calls_desc),
                    value = maxToolCalls,
                    min = AgentRuntimeLimitsPrefs.TOOL_CALLS_MIN,
                    max = AgentRuntimeLimitsPrefs.TOOL_CALLS_MAX,
                    onCommit = { maxToolCalls = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_shell_commands),
                    subtitle = stringResource(R.string.runtime_limits_shell_commands_desc),
                    value = maxShellCommands,
                    min = AgentRuntimeLimitsPrefs.SHELL_COMMANDS_MIN,
                    max = AgentRuntimeLimitsPrefs.SHELL_COMMANDS_MAX,
                    onCommit = { maxShellCommands = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_compaction),
                    subtitle = stringResource(R.string.runtime_limits_compaction_desc),
                    value = maxCompactionCalls,
                    min = AgentRuntimeLimitsPrefs.COMPACTION_CALLS_MIN,
                    max = AgentRuntimeLimitsPrefs.COMPACTION_CALLS_MAX,
                    onCommit = { maxCompactionCalls = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_concurrent_tools),
                    subtitle = stringResource(R.string.runtime_limits_concurrent_tools_desc),
                    value = maxConcurrentTools,
                    min = AgentRuntimeLimitsPrefs.CONCURRENT_TOOLS_MIN,
                    max = AgentRuntimeLimitsPrefs.CONCURRENT_TOOLS_MAX,
                    onCommit = { maxConcurrentTools = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_deadline),
                    subtitle = stringResource(R.string.runtime_limits_deadline_desc),
                    value = runDeadlineMin,
                    min = AgentRuntimeLimitsPrefs.DEADLINE_MIN_MIN,
                    max = AgentRuntimeLimitsPrefs.DEADLINE_MAX_MIN,
                    onCommit = { runDeadlineMin = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_budget_footer))

            // ── 3. Stream recovery ───────────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_recovery)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_length_wall),
                    subtitle = stringResource(R.string.runtime_limits_length_wall_desc),
                    value = lengthWallContinues,
                    min = AgentRuntimeLimitsPrefs.LENGTH_WALL_MIN,
                    max = AgentRuntimeLimitsPrefs.LENGTH_WALL_MAX,
                    onCommit = { lengthWallContinues = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_eof_stub),
                    subtitle = stringResource(R.string.runtime_limits_eof_stub_desc),
                    value = eofStubContinues,
                    min = AgentRuntimeLimitsPrefs.EOF_STUB_MIN,
                    max = AgentRuntimeLimitsPrefs.EOF_STUB_MAX,
                    onCommit = { eofStubContinues = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_det_empty),
                    subtitle = stringResource(R.string.runtime_limits_det_empty_desc),
                    value = deterministicEmptyLimit,
                    min = AgentRuntimeLimitsPrefs.DET_EMPTY_MIN,
                    max = AgentRuntimeLimitsPrefs.DET_EMPTY_MAX,
                    onCommit = { deterministicEmptyLimit = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_transient_retries),
                    subtitle = stringResource(R.string.runtime_limits_transient_retries_desc),
                    value = transientRetries,
                    min = AgentRuntimeLimitsPrefs.TRANSIENT_RETRIES_MIN,
                    max = AgentRuntimeLimitsPrefs.TRANSIENT_RETRIES_MAX,
                    onCommit = { transientRetries = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_verify_nudges),
                    subtitle = stringResource(R.string.runtime_limits_verify_nudges_desc),
                    value = verifyNudges,
                    min = AgentRuntimeLimitsPrefs.VERIFY_NUDGES_MIN,
                    max = AgentRuntimeLimitsPrefs.VERIFY_NUDGES_MAX,
                    onCommit = { verifyNudges = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_recovery_footer))

            // ── 4. Network & worker ──────────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_network)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_generation_wall),
                    subtitle = stringResource(R.string.runtime_limits_generation_wall_desc),
                    value = generationTimeoutMin,
                    min = AgentRuntimeLimitsPrefs.GENERATION_TIMEOUT_MIN_MIN,
                    max = AgentRuntimeLimitsPrefs.GENERATION_TIMEOUT_MAX_MIN,
                    onCommit = { generationTimeoutMin = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_first_chunk_direct),
                    subtitle = stringResource(R.string.runtime_limits_first_chunk_direct_desc),
                    value = firstChunkDirectSec,
                    min = AgentRuntimeLimitsPrefs.FIRST_CHUNK_DIRECT_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.FIRST_CHUNK_DIRECT_MAX_SEC,
                    onCommit = { firstChunkDirectSec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_first_chunk_proxy),
                    subtitle = stringResource(R.string.runtime_limits_first_chunk_proxy_desc),
                    value = firstChunkProxySec,
                    min = AgentRuntimeLimitsPrefs.FIRST_CHUNK_PROXY_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.FIRST_CHUNK_PROXY_MAX_SEC,
                    onCommit = { firstChunkProxySec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_provider_slots),
                    subtitle = stringResource(R.string.runtime_limits_provider_slots_desc),
                    value = providerSlots,
                    min = AgentRuntimeLimitsPrefs.PROVIDER_SLOTS_MIN,
                    max = AgentRuntimeLimitsPrefs.PROVIDER_SLOTS_MAX,
                    onCommit = { providerSlots = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_queue_admission),
                    subtitle = stringResource(R.string.runtime_limits_queue_admission_desc),
                    value = queueAdmission,
                    min = AgentRuntimeLimitsPrefs.QUEUE_ADMISSION_MIN,
                    max = AgentRuntimeLimitsPrefs.QUEUE_ADMISSION_MAX,
                    onCommit = { queueAdmission = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_network_footer))

            // ── [feat/chat-tuning-panel-b] 5. Context & memory budget ─────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_context)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_compact_tail),
                    subtitle = stringResource(R.string.runtime_limits_compact_tail_desc),
                    valueLabel = "$autoCompactMinTailTokens",
                    value = autoCompactMinTailTokens,
                    min = AgentRuntimeLimitsPrefs.COMPACT_TAIL_TOKENS_MIN,
                    max = AgentRuntimeLimitsPrefs.COMPACT_TAIL_TOKENS_MAX,
                    onCommit = { autoCompactMinTailTokens = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_compact_interval),
                    subtitle = stringResource(R.string.runtime_limits_compact_interval_desc),
                    valueLabel = "$autoCompactMinIntervalMin",
                    value = autoCompactMinIntervalMin,
                    min = AgentRuntimeLimitsPrefs.COMPACT_INTERVAL_MIN_MIN,
                    max = AgentRuntimeLimitsPrefs.COMPACT_INTERVAL_MAX_MIN,
                    onCommit = { autoCompactMinIntervalMin = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_memory_inject),
                    subtitle = stringResource(R.string.runtime_limits_memory_inject_desc),
                    valueLabel = "$memoryInjectLines",
                    value = memoryInjectLines,
                    min = AgentRuntimeLimitsPrefs.MEMORY_INJECT_LINES_MIN,
                    max = AgentRuntimeLimitsPrefs.MEMORY_INJECT_LINES_MAX,
                    onCommit = { memoryInjectLines = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_memory_rollup),
                    subtitle = stringResource(R.string.runtime_limits_memory_rollup_desc),
                    valueLabel = "$memoryRollupInjectKb",
                    value = memoryRollupInjectKb,
                    min = AgentRuntimeLimitsPrefs.MEMORY_ROLLUP_KB_MIN,
                    max = AgentRuntimeLimitsPrefs.MEMORY_ROLLUP_KB_MAX,
                    onCommit = { memoryRollupInjectKb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_memory_search),
                    subtitle = stringResource(R.string.runtime_limits_memory_search_desc),
                    valueLabel = "$memorySearchLines",
                    value = memorySearchLines,
                    min = AgentRuntimeLimitsPrefs.MEMORY_SEARCH_LINES_MIN,
                    max = AgentRuntimeLimitsPrefs.MEMORY_SEARCH_LINES_MAX,
                    onCommit = { memorySearchLines = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_memory_lookback),
                    subtitle = stringResource(R.string.runtime_limits_memory_lookback_desc),
                    valueLabel = "$memoryLookbackDays",
                    value = memoryLookbackDays,
                    min = AgentRuntimeLimitsPrefs.MEMORY_LOOKBACK_DAYS_MIN,
                    max = AgentRuntimeLimitsPrefs.MEMORY_LOOKBACK_DAYS_MAX,
                    onCommit = { memoryLookbackDays = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_context_footer))

            // ── [feat/chat-tuning-panel-b] 6. Media & tool budgets ────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_media)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_per_image),
                    subtitle = stringResource(R.string.runtime_limits_image_per_image_desc),
                    valueLabel = "$imageMaxPerImageMb",
                    value = imageMaxPerImageMb,
                    min = AgentRuntimeLimitsPrefs.IMAGE_PER_IMAGE_MB_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_PER_IMAGE_MB_MAX,
                    onCommit = { imageMaxPerImageMb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_total),
                    subtitle = stringResource(R.string.runtime_limits_image_total_desc),
                    valueLabel = "$imageMaxTotalMb",
                    value = imageMaxTotalMb,
                    min = AgentRuntimeLimitsPrefs.IMAGE_TOTAL_MB_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_TOTAL_MB_MAX,
                    onCommit = { imageMaxTotalMb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_request),
                    subtitle = stringResource(R.string.runtime_limits_image_request_desc),
                    valueLabel = "$imageMaxRequestMb",
                    value = imageMaxRequestMb,
                    min = AgentRuntimeLimitsPrefs.IMAGE_REQUEST_MB_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_REQUEST_MB_MAX,
                    onCommit = { imageMaxRequestMb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_edge),
                    subtitle = stringResource(R.string.runtime_limits_image_edge_desc),
                    valueLabel = "$imageMaxEdgePx",
                    value = imageMaxEdgePx,
                    min = AgentRuntimeLimitsPrefs.IMAGE_EDGE_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_EDGE_MAX,
                    onCommit = { imageMaxEdgePx = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_quality),
                    subtitle = stringResource(R.string.runtime_limits_image_quality_desc),
                    valueLabel = "$imageJpegQuality",
                    value = imageJpegQuality,
                    min = AgentRuntimeLimitsPrefs.IMAGE_QUALITY_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_QUALITY_MAX,
                    onCommit = { imageJpegQuality = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_browser_nav),
                    subtitle = stringResource(R.string.runtime_limits_browser_nav_desc),
                    valueLabel = "$browserNavTimeoutSec",
                    value = browserNavTimeoutSec,
                    min = AgentRuntimeLimitsPrefs.BROWSER_NAV_TIMEOUT_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.BROWSER_NAV_TIMEOUT_MAX_SEC,
                    onCommit = { browserNavTimeoutSec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_browser_dom),
                    subtitle = stringResource(R.string.runtime_limits_browser_dom_desc),
                    valueLabel = "$browserDomStableSec",
                    value = browserDomStableSec,
                    min = AgentRuntimeLimitsPrefs.BROWSER_DOM_STABLE_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.BROWSER_DOM_STABLE_MAX_SEC,
                    onCommit = { browserDomStableSec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_browser_screenshot),
                    subtitle = stringResource(R.string.runtime_limits_browser_screenshot_desc),
                    valueLabel = "$browserScreenshotQuality",
                    value = browserScreenshotQuality,
                    min = AgentRuntimeLimitsPrefs.BROWSER_SCREENSHOT_Q_MIN,
                    max = AgentRuntimeLimitsPrefs.BROWSER_SCREENSHOT_Q_MAX,
                    onCommit = { browserScreenshotQuality = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_shell_output),
                    subtitle = stringResource(R.string.runtime_limits_shell_output_desc),
                    valueLabel = "$shellOutputKb",
                    value = shellOutputKb,
                    min = AgentRuntimeLimitsPrefs.SHELL_OUTPUT_KB_MIN,
                    max = AgentRuntimeLimitsPrefs.SHELL_OUTPUT_KB_MAX,
                    onCommit = { shellOutputKb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_shell_timeout),
                    subtitle = stringResource(R.string.runtime_limits_shell_timeout_desc),
                    valueLabel = "$shellTimeoutSec",
                    value = shellTimeoutSec,
                    min = AgentRuntimeLimitsPrefs.SHELL_TIMEOUT_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.SHELL_TIMEOUT_MAX_SEC,
                    onCommit = { shellTimeoutSec = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_media_footer))

            // ── Sandbox lifecycle ────────────────────────────────────────
            // [feat/runtime-sandbox-knobs] Formerly hard-coded literals in
            // ExecutionCoordinator / PersistentShellStall.
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_sandbox)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_guest_tmp_age),
                    subtitle = stringResource(R.string.runtime_limits_guest_tmp_age_desc),
                    valueLabel = "$guestTmpMaxAgeMin",
                    value = guestTmpMaxAgeMin,
                    min = AgentRuntimeLimitsPrefs.GUEST_TMP_MAX_AGE_MIN_MIN,
                    max = AgentRuntimeLimitsPrefs.GUEST_TMP_MAX_AGE_MAX_MIN,
                    onCommit = { guestTmpMaxAgeMin = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_sweep_interval),
                    subtitle = stringResource(R.string.runtime_limits_sweep_interval_desc),
                    valueLabel = "$guestTmpSweepIntervalSec",
                    value = guestTmpSweepIntervalSec,
                    min = AgentRuntimeLimitsPrefs.GUEST_TMP_SWEEP_INTERVAL_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.GUEST_TMP_SWEEP_INTERVAL_MAX_SEC,
                    onCommit = { guestTmpSweepIntervalSec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_stall_guard),
                    subtitle = stringResource(R.string.runtime_limits_stall_guard_desc),
                    valueLabel = "$stallNoProgressSec",
                    value = stallNoProgressSec,
                    min = AgentRuntimeLimitsPrefs.STALL_NO_PROGRESS_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.STALL_NO_PROGRESS_MAX_SEC,
                    onCommit = { stallNoProgressSec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_shell_idle),
                    subtitle = stringResource(R.string.runtime_limits_shell_idle_desc),
                    valueLabel = "$shellIdleTimeoutMin",
                    value = shellIdleTimeoutMin,
                    min = AgentRuntimeLimitsPrefs.SHELL_IDLE_TIMEOUT_MIN_MIN,
                    max = AgentRuntimeLimitsPrefs.SHELL_IDLE_TIMEOUT_MAX_MIN,
                    onCommit = { shellIdleTimeoutMin = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_heavy_gate),
                    subtitle = stringResource(R.string.runtime_limits_heavy_gate_desc),
                    valueLabel = "$heavyGateTimeoutSec",
                    value = heavyGateTimeoutSec,
                    min = AgentRuntimeLimitsPrefs.HEAVY_GATE_TIMEOUT_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.HEAVY_GATE_TIMEOUT_MAX_SEC,
                    onCommit = { heavyGateTimeoutSec = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_sandbox_footer))

            // ── Restore defaults ─────────────────────────────────────────
            // [RC-1114 U-17] The page had no way back to the shipped values:
            // every knob is a slider whose coarse step cannot land on the
            // default again once dragged past it (e.g. `guestTmpMaxAgeMin`
            // snaps in 30-minute slots, so the 60-minute default is only
            // reachable if you never touched it). Reset is a STATE change,
            // not a write — this page is a draft (every slider below edits
            // local state, the top-bar Save persists all of it), so restoring
            // the drafts and letting the user press Save is the same two-step
            // shape as every other control here. Writing through immediately
            // would be the inconsistent choice: one row in this page would
            // persist itself while the other forty wait for Save.
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_reset)) {
                LimitsActionRow(
                    title = stringResource(R.string.runtime_limits_restore_defaults),
                    subtitle = stringResource(R.string.runtime_limits_restore_defaults_desc),
                    onClick = { showRestoreDefaults = true },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_restore_defaults_footer))
        }
    }

    if (showRestoreDefaults) {
        AlertDialog(
            onDismissRequest = { showRestoreDefaults = false },
            title = { Text(stringResource(R.string.runtime_limits_restore_confirm_title)) },
            text = { Text(stringResource(R.string.runtime_limits_restore_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    // Every value below is the named `*_DEFAULT` constant from
                    // AgentRuntimeLimitsPrefs / ConcurrencyPrefs — no second
                    // copy of the numbers lives in the UI, so a default that
                    // moves in the data layer moves here too.
                    subagentEnabled = false
                    maxSessions = ConcurrencyPrefs.DEFAULT
                    maxTurns = AgentRuntimeLimitsPrefs.TURNS_DEFAULT
                    maxProviderAttempts = AgentRuntimeLimitsPrefs.PROVIDER_ATTEMPTS_DEFAULT
                    maxToolCalls = AgentRuntimeLimitsPrefs.TOOL_CALLS_DEFAULT
                    maxShellCommands = AgentRuntimeLimitsPrefs.SHELL_COMMANDS_DEFAULT
                    maxCompactionCalls = AgentRuntimeLimitsPrefs.COMPACTION_CALLS_DEFAULT
                    maxConcurrentTools = AgentRuntimeLimitsPrefs.CONCURRENT_TOOLS_DEFAULT
                    runDeadlineMin = AgentRuntimeLimitsPrefs.DEADLINE_DEFAULT_MIN
                    lengthWallContinues = AgentRuntimeLimitsPrefs.LENGTH_WALL_DEFAULT
                    eofStubContinues = AgentRuntimeLimitsPrefs.EOF_STUB_DEFAULT
                    deterministicEmptyLimit = AgentRuntimeLimitsPrefs.DET_EMPTY_DEFAULT
                    transientRetries = AgentRuntimeLimitsPrefs.TRANSIENT_RETRIES_DEFAULT
                    verifyNudges = AgentRuntimeLimitsPrefs.VERIFY_NUDGES_DEFAULT
                    generationTimeoutMin = AgentRuntimeLimitsPrefs.GENERATION_TIMEOUT_DEFAULT_MIN
                    firstChunkDirectSec = AgentRuntimeLimitsPrefs.FIRST_CHUNK_DIRECT_DEFAULT_SEC
                    firstChunkProxySec = AgentRuntimeLimitsPrefs.FIRST_CHUNK_PROXY_DEFAULT_SEC
                    providerSlots = AgentRuntimeLimitsPrefs.PROVIDER_SLOTS_DEFAULT
                    queueAdmission = AgentRuntimeLimitsPrefs.QUEUE_ADMISSION_DEFAULT
                    autoCompactMinTailTokens = AgentRuntimeLimitsPrefs.COMPACT_TAIL_TOKENS_DEFAULT
                    autoCompactMinIntervalMin = AgentRuntimeLimitsPrefs.COMPACT_INTERVAL_DEFAULT_MIN
                    memoryInjectLines = AgentRuntimeLimitsPrefs.MEMORY_INJECT_LINES_DEFAULT
                    memoryRollupInjectKb = AgentRuntimeLimitsPrefs.MEMORY_ROLLUP_KB_DEFAULT
                    memorySearchLines = AgentRuntimeLimitsPrefs.MEMORY_SEARCH_LINES_DEFAULT
                    memoryLookbackDays = AgentRuntimeLimitsPrefs.MEMORY_LOOKBACK_DAYS_DEFAULT
                    imageMaxPerImageMb = AgentRuntimeLimitsPrefs.IMAGE_PER_IMAGE_MB_DEFAULT
                    imageMaxTotalMb = AgentRuntimeLimitsPrefs.IMAGE_TOTAL_MB_DEFAULT
                    imageMaxRequestMb = AgentRuntimeLimitsPrefs.IMAGE_REQUEST_MB_DEFAULT
                    imageMaxEdgePx = AgentRuntimeLimitsPrefs.IMAGE_EDGE_DEFAULT
                    imageJpegQuality = AgentRuntimeLimitsPrefs.IMAGE_QUALITY_DEFAULT
                    browserNavTimeoutSec = AgentRuntimeLimitsPrefs.BROWSER_NAV_TIMEOUT_DEFAULT_SEC
                    browserDomStableSec = AgentRuntimeLimitsPrefs.BROWSER_DOM_STABLE_DEFAULT_SEC
                    browserScreenshotQuality = AgentRuntimeLimitsPrefs.BROWSER_SCREENSHOT_Q_DEFAULT
                    shellOutputKb = AgentRuntimeLimitsPrefs.SHELL_OUTPUT_KB_DEFAULT
                    shellTimeoutSec = AgentRuntimeLimitsPrefs.SHELL_TIMEOUT_DEFAULT_SEC
                    guestTmpMaxAgeMin = AgentRuntimeLimitsPrefs.GUEST_TMP_MAX_AGE_DEFAULT_MIN
                    guestTmpSweepIntervalSec = AgentRuntimeLimitsPrefs.GUEST_TMP_SWEEP_INTERVAL_DEFAULT_SEC
                    stallNoProgressSec = AgentRuntimeLimitsPrefs.STALL_NO_PROGRESS_DEFAULT_SEC
                    shellIdleTimeoutMin = AgentRuntimeLimitsPrefs.SHELL_IDLE_TIMEOUT_DEFAULT_MIN
                    heavyGateTimeoutSec = AgentRuntimeLimitsPrefs.HEAVY_GATE_TIMEOUT_DEFAULT_SEC
                    showRestoreDefaults = false
                }) { Text(stringResource(R.string.runtime_limits_restore_defaults)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showRestoreDefaults = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

// ── Shared building blocks (used by RuntimeLimitsScreen AND ChatTuningScreen;
//    originally mirrored SettingsScreen's private ones) ────────────────────

@Composable
internal fun LimitsSectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
    }
}

@Composable
internal fun LimitsSectionFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        lineHeight = 16.sp,
    )
}

/**
 * [RC-1114 U-17] A tappable row with no value — the shape a "restore
 * defaults" action needs. Same paddings/dividers as [LimitsSwitchRow] so it
 * sits in the card without looking like a different widget family.
 */
@Composable
internal fun LimitsActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.primary,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
internal fun LimitsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

// [fix/tuning-slider-density] Spans up to MAX_FINE_SPAN keep exact 1-unit
// stepping — at 64 units the ticks are ~5dp apart on a phone-width track, so
// every unit is still a visible, tappable snap point. Wider spans collapse
// onto the slot widths chosen in [sliderStepsFor].
//
// [T-slider-visible-snap] 2026-09-24: lowered from 1024 to 64. Every tuning
// slider now has VISIBLE snapping — the user-facing rule is "all sliders
// snap": a 983-step row (e.g. 50..1000 turns) drew sub-pixel ticks that read
// as a continuous drag, while the wide rows (500-token / 50-px steps) visibly
// snapped. Unifying on the coarse side also strictly REDUCES tick count
// (983 → 38 on that row), so the [fix/tuning-slider-density] scan-cost
// motivation only gets stronger.
internal const val MAX_FINE_SPAN = 64
/** Slot target for wide spans; the actual count is `span / width`. */
internal const val WIDE_TARGET_SLOTS = 64
/** Below this many slots a wide span falls back to a continuous slider. */
internal const val MIN_SLIDE_SLOTS = 16

/**
 * [fix/tuning-slider-density] Tick count for [Slider]'s `steps` parameter.
 *
 * Material3 draws one tick shape per step on EVERY frame of a drag and
 * linearly scans all of them per pointer move, so `steps = span - 1` on the
 * widest tuning rows was heavy: the 2000..32000 token row would draw
 * ~30 000 circles per frame (shipped maximum before this fix: 983 steps,
 * which stayed affordable).
 *
 * [T-slider-visible-snap] Spans up to [MAX_FINE_SPAN] (64) keep exact 1-unit
 * stepping — those ticks are ~5dp apart, every unit a visible snap point.
 * Wider spans snap to ≈[WIDE_TARGET_SLOTS] slots. The slot width is chosen
 * as the smallest exact divisor of the span (preferring a round multiple of
 * 5), so dragged values stay nice: 2000..32000 lands on 500-token steps,
 * 800..4000 on 50-px steps, 60..1800 on 30-s steps. A span with no usable
 * divisor (prime-ish, e.g. 1031) falls back to ≈[WIDE_TARGET_SLOTS]
 * NON-exact slots rather than a continuous slider: the thumb still snaps,
 * the ticks stay visible, and [LimitsSliderRow] rounds the fractional slot
 * values to the nearest int on commit — so no slider ever reads as
 * "continuous" again.
 */
internal fun sliderStepsFor(min: Int, max: Int): Int {
    val span = max - min
    if (span <= 1) return 0
    if (span <= MAX_FINE_SPAN) return span - 1
    val start = (span + WIDE_TARGET_SLOTS - 1) / WIDE_TARGET_SLOTS
    // Slots = span / width; requiring MIN_SLIDE_SLOTS slots caps the scan.
    val scanLimit = span / MIN_SLIDE_SLOTS
    var width = start
    var firstDivisor = -1
    var roundDivisor = -1
    while (width <= scanLimit) {
        if (span % width == 0) {
            if (firstDivisor < 0) firstDivisor = width
            if (width % 5 == 0) {
                roundDivisor = width
                break
            }
        }
        width++
    }
    val pick = if (roundDivisor > 0) roundDivisor else firstDivisor
    if (pick > 0) return span / pick - 1
    // [T-slider-visible-snap] Prime-ish span: no exact divisor in the window.
    // Round(span/start) non-exact slots ≈ WIDE_TARGET_SLOTS, so the ticks
    // stay visible and the scan stays bounded; the slot values are
    // fractional and get rounded on commit.
    val slots = (span + start / 2) / start
    return (slots - 1).coerceAtLeast(1)
}

/**
 * One slider row: title, optional description (the DETAIL the user asked to
 * keep off the Settings list and INSIDE this page), current value on the
 * right, slider beneath. Sliding commits immediately to local state; the
 * page-level Save persists.
 */
@Composable
internal fun LimitsSliderRow(
    title: String,
    subtitle: String? = null,
    valueLabel: String? = null,
    value: Int,
    min: Int,
    max: Int,
    onCommit: (Int) -> Unit,
    showDivider: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                    )
                }
            }
            Text(
                text = valueLabel ?: value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            // [T-slider-visible-snap] roundToInt, not toInt(): with the
            // non-exact slot fallback the Slider hands back fractional slot
            // positions, and float slot math can also land at 23.9999… —
            // truncation would snap that to 23 instead of 24. Rounding keeps
            // exact-divisor slots unchanged and makes fallback slots int.
            onValueChange = { onCommit(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = remember(min, max) { sliderStepsFor(min, max) },
        )
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}
