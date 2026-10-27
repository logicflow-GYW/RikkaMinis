package com.rikkaminis.app.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.rikkaminis.app.config.collections.EnvVarsCollection
import com.rikkaminis.app.config.collections.GroupsCollection
import com.rikkaminis.app.config.collections.ModelsCollection
import com.rikkaminis.app.config.collections.ProvidersCollection
import com.rikkaminis.app.config.fields.ClosureField
import com.rikkaminis.app.config.fields.PrefsBoolField
import com.rikkaminis.app.config.fields.PrefsIntCodedEnumField
import com.rikkaminis.app.config.fields.PrefsIntField
import com.rikkaminis.app.config.fields.PrefsStringField
import com.rikkaminis.app.config.fields.ReadOnlyField
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.data.repository.EnvVarRepository
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.data.model.SystemVoiceEntries
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.chat.ChatViewModel
import com.rikkaminis.app.ui.chat.ChatViewModelStore
import com.rikkaminis.app.ui.chat.SessionModelResolution
import com.rikkaminis.app.ui.chat.UnusableModelReason
import com.rikkaminis.app.ui.chat.applyDefaultModelFallback
import com.rikkaminis.app.ui.chat.decideSessionModelResolution
import com.rikkaminis.app.ui.chat.restoreFromBinding
import com.rikkaminis.app.ui.chat.setThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Built-in field registrations. Single place to add or remove a
 * setting from the agent-facing surface. Mirrors iOS
 * `ConfigRegistry+Builtins.swift`.
 */
internal object ConfigBuiltins {

    /**
     * [F-134] Strong holder for the runtime-limits change listener.
     *
     * AOSP `SharedPreferencesImpl` keeps its listeners in a
     * `WeakHashMap<OnSharedPreferenceChangeListener, Object>` whose value is a
     * static sentinel that does NOT reference the key. A listener created as a
     * bare lambda *argument* therefore has no strong reference anywhere: the
     * registering frame returns, the GC collects it, and the callback silently
     * stops firing — it is never re-registered. That is exactly the "changed
     * but takes effect never" bug the registration exists to prevent, so the
     * listener has to be held by a field. This object lives for the process, so
     * the reference is permanent by construction; the captured context is the
     * application context (also process-scoped, so no leak).
     *
     * Guard: `scripts/scan/prefs_listener_holder_guard.py`.
     */
    @Volatile private var runtimeLimitsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * [fix/da1026-prefs-priming] Strong holder for the concurrency-cap change
     * listener — the same F-134 rationale as [runtimeLimitsListener] above: a
     * bare lambda *argument* is held only weakly by SharedPreferencesImpl, so
     * the GC collects it and the callback silently stops firing (no error, no
     * exception — the write lands and the registration "succeeded").
     *
     * `ConcurrencyPrefs` caches the cap in a @Volatile field that
     * MinisApp.onCreate primes once. Its PrefsIntField is registered in
     * [registerRuntime] below, so `minis-config set runtime.maxConcurrentSessions`
     * and a backup restore write straight to SharedPreferences — with no
     * listener the primed cache keeps serving the pre-write value to
     * SessionConcurrencyManager / ExecutionCoordinator / NativeOffloadServer
     * until the next process start, and RuntimeLimitsScreen's own read-back
     * shows (then re-persists) that stale value.
     *
     * prime() only refreshes the cache; the sized semaphores still take their
     * bound at process start (see ConcurrencyPrefs's class doc), so this
     * changes *visibility*, not when the cap takes effect.
     *
     * Guard: `scripts/scan/prefs_listener_holder_guard.py`.
     */
    @Volatile private var concurrencyPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * [T8-2] Dependency-injected registration. The config layer must not
     * import service/UI classes — the UI-coupled values are supplied
     * by the caller instead:
     *
     * @param sessionIdProvider live provider of the foregrounded chat
     *   session id (null when no chat is open). ConfigBuiltins reads it at
     *   field-read/write time, so it always reflects the current screen.
     */
    fun registerInto(
        r: ConfigRegistry,
        context: Context,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository,
        chatRepo: ChatRepository,
        sessionIdProvider: () -> String?,
    ) {
        registerSelfMeta(r)
        registerSession(r, providerRepo, chatRepo, sessionIdProvider)
        registerAppearance(r, context)
        registerChat(r, context)
        registerBackground(r, context)
        registerLogs(r, context)
        registerProviderCollections(r, providerRepo, envVarRepo)
        registerDefaults(r, providerRepo)
        registerSoul(r, context)
        registerMemory(r, context)
        registerRuntime(r, context)
    }

    // -- Memory — global default toggle for the persistent memory feature --
    //
    // [T-android-memory-enabled-minisconfig] `memory.enabled` mirrors the
    // Settings → Memory switch (MemoryManagementScreen, backed by
    // MemoryGlobalPrefs: SharedPreferences `minis_memory_prefs`, key
    // `memory.global.enabled`, default true). It is the global DEFAULT
    // applied to newly-created sessions: a new chat seeds its per-session
    // memoryEnabled from this value. It does NOT retroactively flip
    // already-open sessions — those carry their own per-session toggle,
    // changed via the SessionMemorySheet / `/memory`. Registering the same
    // prefs+key as MemoryGlobalPrefs keeps the CLI/agent and the Settings
    // switch reading/writing one source of truth. Topic auto-surfaces in
    // list-topics since topics derive from field-path prefixes.

    private fun registerMemory(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences("minis_memory_prefs", Context.MODE_PRIVATE)
        r.register(
            PrefsBoolField(
                path = "memory.enabled",
                displayName = "Memory enabled (global default)",
                description = "Default for whether NEW chat sessions start with memory on. When on, a new session auto-injects GLOBAL.md + recent daily logs into the system prompt and exposes the memory_get / memory_write tools. When off, new sessions get neither. Already-open sessions keep their own per-session setting (toggle in the session memory sheet) and are not changed by this.",
                prefs = prefs,
                key = "memory.global.enabled",
                defaultValue = true,
            )
        )
    }

    // -- Runtime / agent knobs --
    //
    // Settings surfaced under Settings → Agent Runtime, stored in their own
    // prefs file (minis_concurrency_prefs). Registering them here folds them
    // into the ConfigRegistry so (a) minis-config / the agent can read them
    // and (b) the in-app backup carries them — a user's chosen max-concurrent
    // cap previously lived in a standalone prefs file that ConfigBackup never
    // walked, so a restore silently reset it to the default.

    private fun registerRuntime(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences(
            com.rikkaminis.app.data.ConcurrencyPrefs.PREFS,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsIntField(
                path = "runtime.maxConcurrentSessions",
                displayName = "Max concurrent sessions",
                description = "Upper bound on how many agent-loop sessions run at once. Applies on next app start (the controller and semaphores are sized at boot). Bounds 1..16.",
                prefs = prefs,
                key = com.rikkaminis.app.data.ConcurrencyPrefs.KEY_MAX_CONCURRENT_SESSIONS,
                defaultValue = com.rikkaminis.app.data.ConcurrencyPrefs.DEFAULT,
                minValue = com.rikkaminis.app.data.ConcurrencyPrefs.MIN,
                maxValue = com.rikkaminis.app.data.ConcurrencyPrefs.MAX,
            )
        )
        // [fix/da1026-prefs-priming] Re-prime the ConcurrencyPrefs cache on any
        // write to its prefs file — the same "changed but takes effect never"
        // shape the AgentRuntimeLimitsPrefs listener below exists to prevent,
        // and the same strong-holder requirement (see the field KDoc).
        // PrefsIntField writes go straight to SharedPreferences (minis-config
        // / backup restore / the agent), but the cap cache is only primed once
        // at MinisApp.onCreate: without this listener an external write lands
        // while SessionConcurrencyManager, ExecutionCoordinator and
        // NativeOffloadServer keep serving the startup value.
        //
        // prime() refreshes the cache only — the sized semaphores keep the
        // bound they took at process start (deliberate, see ConcurrencyPrefs's
        // class doc), so this makes the persisted value *visible*, it does not
        // resize any live slot controller.
        val appCtx = context.applicationContext
        val concurrencyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            com.rikkaminis.app.data.ConcurrencyPrefs.prime(appCtx)
        }
        concurrencyPrefsListener = concurrencyListener
        prefs.registerOnSharedPreferenceChangeListener(concurrencyListener)
        // [T-subagent-toggle] Cross-session sub-agent dispatch switch. Same
        // prefs+key as SubagentPrefs so Settings / minis-config / the agent
        // all read-write one source of truth. OFF by default (side-effectful:
        // lets the agent open new sessions and run long-lived work).
        val subagentPrefs = context.getSharedPreferences(
            com.rikkaminis.app.data.SubagentPrefs.PREFS,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "runtime.subagentEnabled",
                displayName = "Sub-agent / cross-session dispatch",
                description = "When ON, the agent can spawn independent sub-agents and dispatch work to other chat sessions (exposes the spawn_agent tool and enables minis-sessions-cli send). OFF by default: this lets the agent open new sessions and run long-lived work, so it stays disabled until you explicitly turn it on.",
                prefs = subagentPrefs,
                key = com.rikkaminis.app.data.SubagentPrefs.KEY_ENABLED,
                defaultValue = false,
            )
        )

        // [feat/runtime-limits-panel] Agent runtime limits — every knob that
        // Settings → Agent Runtime → Runtime Limits exposes, registered so
        // minis-config can read/write them and the in-app backup carries
        // them (same fold-in rationale as maxConcurrentSessions above).
        val limits = context.getSharedPreferences(
            com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.PREFS,
            Context.MODE_PRIVATE,
        )
        // [fix/runtime-limits-audit] PrefsIntField writes go straight to
        // SharedPreferences; AgentRuntimeLimitsPrefs' primed cache does NOT
        // auto-refresh (unlike Compose-backed appearance keys, its readers
        // are context-free engine/worker code). Without a change listener a
        // `minis-config set runtime.*` from an agent session would land in
        // prefs but every reader would keep serving the stale cached value
        // until process restart — the "changed but takes effect never" bug.
        // Re-prime on any write to this file: cheap (17 getInts), immediate,
        // and preserves the run-consistency semantics (runs snapshot at
        // runAgentLoop entry, so an in-flight run still keeps its budget).
        //
        // [F-134] The listener is stored in `runtimeLimitsListener`, NOT passed
        // as a bare lambda: SharedPreferencesImpl holds listeners weakly, so an
        // unheld lambda is collected by the GC and the callback silently stops
        // firing (see the field's KDoc). Registration is idempotent per
        // (prefs, listener) pair, so re-entering registerInto is harmless.
        // (The `appCtx` this listener captures is declared once above, for the
        // concurrency listener — same application context, one declaration.)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.prime(appCtx)
        }
        runtimeLimitsListener = listener
        limits.registerOnSharedPreferenceChangeListener(listener)
        val L = com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
        r.register(PrefsIntField(
            path = "runtime.maxTurns",
            displayName = "Agent loop turn limit",
            description = "Hard cap on agent-loop turns per run. Hitting it pauses the run (resumable) with a runaway-tool banner. Default 256. Applies to the next message (a run keeps the budget it started with).",
            prefs = limits, key = L.KEY_MAX_TURNS,
            defaultValue = L.TURNS_DEFAULT, minValue = L.TURNS_MIN, maxValue = L.TURNS_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.maxProviderAttempts",
            displayName = "Provider call budget",
            description = "Max provider calls (incl. retries/fallback) per run. Hitting it pauses the run (resumable). Default 256. Raising it loosens the runaway-loop guard — a stuck loop burns more tokens before being stopped.",
            prefs = limits, key = L.KEY_MAX_PROVIDER_ATTEMPTS,
            defaultValue = L.PROVIDER_ATTEMPTS_DEFAULT, minValue = L.PROVIDER_ATTEMPTS_MIN, maxValue = L.PROVIDER_ATTEMPTS_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.maxToolCalls",
            displayName = "Tool call budget",
            description = "Max tool calls per agent run. Hitting it pauses the run (resumable). Default 256. Same runaway-guard trade-off as the provider budget.",
            prefs = limits, key = L.KEY_MAX_TOOL_CALLS,
            defaultValue = L.TOOL_CALLS_DEFAULT, minValue = L.TOOL_CALLS_MIN, maxValue = L.TOOL_CALLS_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.maxShellCommands",
            displayName = "Shell command budget",
            description = "Max sandbox shell commands per agent run. Hitting it pauses the run (resumable). Default 256.",
            prefs = limits, key = L.KEY_MAX_SHELL_COMMANDS,
            defaultValue = L.SHELL_COMMANDS_DEFAULT, minValue = L.SHELL_COMMANDS_MIN, maxValue = L.SHELL_COMMANDS_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.maxCompactionCalls",
            displayName = "Context compaction budget",
            description = "Max context-compaction calls per run. Default 8. Lowering it makes long sessions hit the context window sooner (compaction is what keeps them running).",
            prefs = limits, key = L.KEY_MAX_COMPACTION_CALLS,
            defaultValue = L.COMPACTION_CALLS_DEFAULT, minValue = L.COMPACTION_CALLS_MIN, maxValue = L.COMPACTION_CALLS_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.maxConcurrentTools",
            displayName = "Concurrent tool limit",
            description = "Max tools executing at once inside one turn. Default 4. Raising it increases peak sandbox load (memory/CPU on-device).",
            prefs = limits, key = L.KEY_MAX_CONCURRENT_TOOLS,
            defaultValue = L.CONCURRENT_TOOLS_DEFAULT, minValue = L.CONCURRENT_TOOLS_MIN, maxValue = L.CONCURRENT_TOOLS_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.runDeadlineMinutes",
            displayName = "Run time limit (minutes)",
            description = "Wall-clock ceiling per agent run. Hitting it pauses the run (resumable). Default 120 min. Lowering it can cut off legitimately long builds/generations — the deadline is a backstop, not a progress signal.",
            prefs = limits, key = L.KEY_RUN_DEADLINE_MIN,
            defaultValue = L.DEADLINE_DEFAULT_MIN, minValue = L.DEADLINE_MIN_MIN, maxValue = L.DEADLINE_MAX_MIN,
        ))
        r.register(PrefsIntField(
            path = "runtime.lengthWallContinues",
            displayName = "Length-truncation continuations",
            description = "Max continuation rounds after a finish_reason=length truncation. Default 4. 0 disables continuation (truncated replies end immediately with a truncation error).",
            prefs = limits, key = L.KEY_LENGTH_WALL_CONTINUES,
            defaultValue = L.LENGTH_WALL_DEFAULT, minValue = L.LENGTH_WALL_MIN, maxValue = L.LENGTH_WALL_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.eofStubContinues",
            displayName = "EOF/stream-drop continuations",
            description = "Max continuation rounds after an EOF-truncated or error-shaped stream (partial text is kept, a network-stub reminder asks the model to continue). Default 2.",
            prefs = limits, key = L.KEY_EOF_STUB_CONTINUES,
            defaultValue = L.EOF_STUB_DEFAULT, minValue = L.EOF_STUB_MIN, maxValue = L.EOF_STUB_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.deterministicEmptyLimit",
            displayName = "Deterministic-empty fast-exit",
            description = "Consecutive provably-empty completions (usage reports 0 output tokens) before the loop stops re-billing. Default 2.",
            prefs = limits, key = L.KEY_DETERMINISTIC_EMPTY_LIMIT,
            defaultValue = L.DET_EMPTY_DEFAULT, minValue = L.DET_EMPTY_MIN, maxValue = L.DET_EMPTY_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.transientRetries",
            displayName = "Transient error retries",
            description = "Auto-retry attempts for transient network errors (backoff 1/2/4/8/16s). Default 3. 0 disables auto-retry — transient errors surface immediately.",
            prefs = limits, key = L.KEY_TRANSIENT_RETRIES,
            defaultValue = L.TRANSIENT_RETRIES_DEFAULT, minValue = L.TRANSIENT_RETRIES_MIN, maxValue = L.TRANSIENT_RETRIES_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.verifyNudges",
            displayName = "Verification nudges",
            description = "Turn-end nudges reminding the agent to verify code edits before finishing. Default 0 = guard OFF: an unverified edit closes the turn silently, exactly like the pre-guard behavior. Raise it (1-4) to opt into bounded reminders — each one costs an extra model round.",
            prefs = limits, key = L.KEY_VERIFY_NUDGES,
            defaultValue = L.VERIFY_NUDGES_DEFAULT, minValue = L.VERIFY_NUDGES_MIN, maxValue = L.VERIFY_NUDGES_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.generationTimeoutMinutes",
            displayName = "Generation hard wall (minutes)",
            description = "Absolute ceiling for one model generation stream (idle-read backstop). Default 30 min. LOWERING it risks killing healthy slow generations (long thinking / big deliverables are silent for minutes); raising it lets a wedged stream hold a worker longer.",
            prefs = limits, key = L.KEY_GENERATION_TIMEOUT_MIN,
            defaultValue = L.GENERATION_TIMEOUT_DEFAULT_MIN, minValue = L.GENERATION_TIMEOUT_MIN_MIN, maxValue = L.GENERATION_TIMEOUT_MAX_MIN,
        ))
        r.register(PrefsIntField(
            path = "runtime.firstChunkDirectSec",
            displayName = "First-chunk timeout, direct (seconds)",
            description = "Route-aware first-chunk budget for DIRECT endpoints (rare; the generation stream path uses the uniform hard wall). Default 30s.",
            prefs = limits, key = L.KEY_FIRST_CHUNK_DIRECT_SEC,
            defaultValue = L.FIRST_CHUNK_DIRECT_DEFAULT_SEC, minValue = L.FIRST_CHUNK_DIRECT_MIN_SEC, maxValue = L.FIRST_CHUNK_DIRECT_MAX_SEC,
        ))
        r.register(PrefsIntField(
            path = "runtime.firstChunkProxySec",
            displayName = "First-chunk timeout, proxy (seconds)",
            description = "Route-aware first-chunk budget for PROXY/gateway routes (rare; the generation stream path uses the uniform hard wall). Default 45s.",
            prefs = limits, key = L.KEY_FIRST_CHUNK_PROXY_SEC,
            defaultValue = L.FIRST_CHUNK_PROXY_DEFAULT_SEC, minValue = L.FIRST_CHUNK_PROXY_MIN_SEC, maxValue = L.FIRST_CHUNK_PROXY_MAX_SEC,
        ))
        r.register(PrefsIntField(
            path = "runtime.providerSlots",
            displayName = "Provider worker slots",
            description = "Max provider calls executing concurrently in one model-service worker process. Default 2. RAISING IT IS MEMORY-RISKY: concurrent streams multiply the worker's native-heap peak (SSE buffers, DirectByteBuffer) — sized by measurement, not optimism. Applies when the next worker process spawns.",
            prefs = limits, key = L.KEY_PROVIDER_SLOTS,
            defaultValue = L.PROVIDER_SLOTS_DEFAULT, minValue = L.PROVIDER_SLOTS_MIN, maxValue = L.PROVIDER_SLOTS_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.queueAdmission",
            displayName = "Worker queue admission bound",
            description = "Max slot-waiting requests before a new dispatch is rejected with a transient error (auto re-dispatched onto a fresher worker). Default 6.",
            prefs = limits, key = L.KEY_QUEUE_ADMISSION,
            defaultValue = L.QUEUE_ADMISSION_DEFAULT, minValue = L.QUEUE_ADMISSION_MIN, maxValue = L.QUEUE_ADMISSION_MAX,
        ))
        // ── [feat/chat-tuning-panel-b] Group 5: context & memory budget ──
        r.register(PrefsIntField(
            path = "runtime.autoCompactMinTailTokens",
            displayName = "Auto-compact tail-token floor",
            description = "Auto-compaction never cuts below this many tail tokens — the newest conversation stays verbatim. Higher = more context kept per compaction, larger requests. Default 8000.",
            prefs = limits, key = L.KEY_AUTO_COMPACT_MIN_TAIL_TOKENS,
            defaultValue = L.COMPACT_TAIL_TOKENS_DEFAULT, minValue = L.COMPACT_TAIL_TOKENS_MIN, maxValue = L.COMPACT_TAIL_TOKENS_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.autoCompactMinIntervalMin",
            displayName = "Auto-compact minimum interval (min)",
            description = "Minimum minutes between auto-compactions of the same conversation. Default 5.",
            prefs = limits, key = L.KEY_AUTO_COMPACT_MIN_INTERVAL_MIN,
            defaultValue = L.COMPACT_INTERVAL_DEFAULT_MIN, minValue = L.COMPACT_INTERVAL_MIN_MIN, maxValue = L.COMPACT_INTERVAL_MAX_MIN,
        ))
        // [feat/compact-model-pin-1005] Pinned compaction model entry id.
        // "" = follow the session chain (default). Written by the Model
        // Groups screen's compaction-model picker (moved out of the Runtime
        // Limits panel in [ui/runtime-page-adjust-1006]); registered here so
        // the in-app backup carries it and minis-config can inspect it.
        // Accepts any string — a stale/invalid id simply resolves to nothing
        // at chain-build time and compaction falls back to the session chain.
        r.register(PrefsStringField(
            path = "runtime.compactModelEntryId",
            displayName = "Auto-compact model entry id",
            description = "Model entry pinned as the compaction-chain head. Empty = follow the session chain (default). Stale ids are skipped silently at chain build.",
            prefs = limits, key = L.KEY_COMPACT_MODEL_ENTRY_ID,
            defaultValue = L.COMPACT_MODEL_ENTRY_DEFAULT,
            maxLength = 128,
        ))
        r.register(PrefsIntField(
            path = "runtime.memoryInjectLines",
            displayName = "Memory: daily-log injection lines",
            description = "Max daily-log lines injected into the system prompt. Default 200.",
            prefs = limits, key = L.KEY_MEMORY_INJECT_LINES,
            defaultValue = L.MEMORY_INJECT_LINES_DEFAULT, minValue = L.MEMORY_INJECT_LINES_MIN, maxValue = L.MEMORY_INJECT_LINES_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.memoryRollupInjectKb",
            displayName = "Memory: rollup injection ceiling (KB)",
            description = "Byte ceiling for MEMORY-ROLLUP.md content injected into the system prompt; older rules are truncated from the head. Default 12.",
            prefs = limits, key = L.KEY_MEMORY_ROLLUP_INJECT_KB,
            defaultValue = L.MEMORY_ROLLUP_KB_DEFAULT, minValue = L.MEMORY_ROLLUP_KB_MIN, maxValue = L.MEMORY_ROLLUP_KB_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.memorySearchLines",
            displayName = "Memory: search result lines",
            description = "Max lines a keyword memory search returns. Default 60.",
            prefs = limits, key = L.KEY_MEMORY_SEARCH_LINES,
            defaultValue = L.MEMORY_SEARCH_LINES_DEFAULT, minValue = L.MEMORY_SEARCH_LINES_MIN, maxValue = L.MEMORY_SEARCH_LINES_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.memoryLookbackDays",
            displayName = "Memory: search lookback (days)",
            description = "How many days back recent daily logs are walked for the context injection; memory search itself reads all logs. Default 30.",
            prefs = limits, key = L.KEY_MEMORY_LOOKBACK_DAYS,
            defaultValue = L.MEMORY_LOOKBACK_DAYS_DEFAULT, minValue = L.MEMORY_LOOKBACK_DAYS_MIN, maxValue = L.MEMORY_LOOKBACK_DAYS_MAX,
        ))
        // ── [feat/chat-tuning-panel-b] Group 6: media & tool budgets ──────
        r.register(PrefsIntField(
            path = "runtime.imageMaxPerImageMb",
            displayName = "Images: per-image ceiling (MB)",
            description = "Single-image byte ceiling before re-encode kicks in. Default 5.",
            prefs = limits, key = L.KEY_IMAGE_MAX_PER_IMAGE_MB,
            defaultValue = L.IMAGE_PER_IMAGE_MB_DEFAULT, minValue = L.IMAGE_PER_IMAGE_MB_MIN, maxValue = L.IMAGE_PER_IMAGE_MB_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.imageMaxTotalMb",
            displayName = "Images: per-message total (MB)",
            description = "Cumulative inline-image bytes allowed per user message; the tail is dropped past this. Default 25.",
            prefs = limits, key = L.KEY_IMAGE_MAX_TOTAL_MB,
            defaultValue = L.IMAGE_TOTAL_MB_DEFAULT, minValue = L.IMAGE_TOTAL_MB_MIN, maxValue = L.IMAGE_TOTAL_MB_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.imageMaxRequestMb",
            displayName = "Images: per-request ceiling (MB)",
            description = "Whole-request image byte ceiling across history; oldest images are elided past this. Default 25.",
            prefs = limits, key = L.KEY_IMAGE_MAX_REQUEST_MB,
            defaultValue = L.IMAGE_REQUEST_MB_DEFAULT, minValue = L.IMAGE_REQUEST_MB_MIN, maxValue = L.IMAGE_REQUEST_MB_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.imageMaxEdgePx",
            displayName = "Images: re-encode max edge (px)",
            description = "Longest edge images are downscaled to before encoding. Default 2000.",
            prefs = limits, key = L.KEY_IMAGE_MAX_EDGE_PX,
            defaultValue = L.IMAGE_EDGE_DEFAULT, minValue = L.IMAGE_EDGE_MIN, maxValue = L.IMAGE_EDGE_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.imageJpegQuality",
            displayName = "Images: JPEG quality",
            description = "JPEG re-encode quality (0-100). Default 80.",
            prefs = limits, key = L.KEY_IMAGE_JPEG_QUALITY,
            defaultValue = L.IMAGE_QUALITY_DEFAULT, minValue = L.IMAGE_QUALITY_MIN, maxValue = L.IMAGE_QUALITY_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.browserNavTimeoutSec",
            displayName = "Browser: page-load timeout (s)",
            description = "How long a browser navigation may run before it fails. Default 30.",
            prefs = limits, key = L.KEY_BROWSER_NAV_TIMEOUT_SEC,
            defaultValue = L.BROWSER_NAV_TIMEOUT_DEFAULT_SEC, minValue = L.BROWSER_NAV_TIMEOUT_MIN_SEC, maxValue = L.BROWSER_NAV_TIMEOUT_MAX_SEC,
        ))
        r.register(PrefsIntField(
            path = "runtime.browserDomStableSec",
            displayName = "Browser: DOM-stable wait default (s)",
            description = "Default wait for the page DOM to settle before reading it, when a call does not pass its own timeout. Default 5.",
            prefs = limits, key = L.KEY_BROWSER_DOM_STABLE_SEC,
            defaultValue = L.BROWSER_DOM_STABLE_DEFAULT_SEC, minValue = L.BROWSER_DOM_STABLE_MIN_SEC, maxValue = L.BROWSER_DOM_STABLE_MAX_SEC,
        ))
        r.register(PrefsIntField(
            path = "runtime.browserScreenshotQuality",
            displayName = "Browser: screenshot quality",
            description = "JPEG quality for browser screenshots (0-100). Default 80.",
            prefs = limits, key = L.KEY_BROWSER_SCREENSHOT_QUALITY,
            defaultValue = L.BROWSER_SCREENSHOT_Q_DEFAULT, minValue = L.BROWSER_SCREENSHOT_Q_MIN, maxValue = L.BROWSER_SCREENSHOT_Q_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.shellOutputKb",
            displayName = "Shell: output capture ceiling (KB)",
            description = "Max shell output kept per command; the rest is truncated with a marker. Default 128.",
            prefs = limits, key = L.KEY_SHELL_OUTPUT_KB,
            defaultValue = L.SHELL_OUTPUT_KB_DEFAULT, minValue = L.SHELL_OUTPUT_KB_MIN, maxValue = L.SHELL_OUTPUT_KB_MAX,
        ))
        r.register(PrefsIntField(
            path = "runtime.shellTimeoutSec",
            displayName = "Shell: default command timeout (s)",
            description = "Default timeout for shell commands that don't pass their own; explicit per-call values keep their own (max 900 s). Default 900.",
            prefs = limits, key = L.KEY_SHELL_TIMEOUT_SEC,
            defaultValue = L.SHELL_TIMEOUT_DEFAULT_SEC, minValue = L.SHELL_TIMEOUT_MIN_SEC, maxValue = L.SHELL_TIMEOUT_MAX_SEC,
        ))
        r.register(PrefsIntField(
            path = "runtime.guestTmpMaxAgeMin",
            displayName = "Sandbox: /tmp sweep age (min)",
            description = "Minimum age before the guest /tmp sweeper may delete an entry. Guest /tmp is RAM-backed, so a longer window keeps transient data in memory longer. Default 60.",
            prefs = limits, key = L.KEY_GUEST_TMP_MAX_AGE_MIN,
            defaultValue = L.GUEST_TMP_MAX_AGE_DEFAULT_MIN, minValue = L.GUEST_TMP_MAX_AGE_MIN_MIN, maxValue = L.GUEST_TMP_MAX_AGE_MAX_MIN,
        ))
        r.register(PrefsIntField(
            path = "runtime.guestTmpSweepIntervalSec",
            displayName = "Sandbox: sweep cadence (s)",
            description = "How often idle shells are recycled and guest /tmp is pruned. Default 60.",
            prefs = limits, key = L.KEY_GUEST_TMP_SWEEP_INTERVAL_SEC,
            defaultValue = L.GUEST_TMP_SWEEP_INTERVAL_DEFAULT_SEC, minValue = L.GUEST_TMP_SWEEP_INTERVAL_MIN_SEC, maxValue = L.GUEST_TMP_SWEEP_INTERVAL_MAX_SEC,
        ))
        r.register(PrefsIntField(
            path = "runtime.stallNoProgressSec",
            displayName = "Shell: stall guard window (s)",
            description = "A command with no output and no CPU for this long is treated as hung and aborted. 0 disables the guard. Default 180.",
            prefs = limits, key = L.KEY_STALL_NO_PROGRESS_SEC,
            defaultValue = L.STALL_NO_PROGRESS_DEFAULT_SEC, minValue = L.STALL_NO_PROGRESS_MIN_SEC, maxValue = L.STALL_NO_PROGRESS_MAX_SEC,
        ))
        r.register(PrefsIntField(
            path = "runtime.shellIdleTimeoutMin",
            displayName = "Shell: idle recycle window (min)",
            description = "A shell idle this long is recycled to release its PRoot footprint. Default 10.",
            prefs = limits, key = L.KEY_SHELL_IDLE_TIMEOUT_MIN,
            defaultValue = L.SHELL_IDLE_TIMEOUT_DEFAULT_MIN, minValue = L.SHELL_IDLE_TIMEOUT_MIN_MIN, maxValue = L.SHELL_IDLE_TIMEOUT_MAX_MIN,
        ))
        r.register(PrefsIntField(
            path = "runtime.heavyGateTimeoutSec",
            displayName = "Sandbox: heavy gate wait (s)",
            description = "How long a heavy command waits for the global heavy-command gate before reporting that another heavy command is still running. Default 600.",
            prefs = limits, key = L.KEY_HEAVY_GATE_TIMEOUT_SEC,
            defaultValue = L.HEAVY_GATE_TIMEOUT_DEFAULT_SEC, minValue = L.HEAVY_GATE_TIMEOUT_MIN_SEC, maxValue = L.HEAVY_GATE_TIMEOUT_MAX_SEC,
        ))
    }

    // -- Master switch surface (read-only via the registry; UI toggles it) --

    private fun registerSelfMeta(r: ConfigRegistry) {
        r.register(
            ClosureField(
                path = "permissions.minisConfig.enabled",
                displayName = "Allow minis-config",
                description = "Master switch. Read-only here — toggle via Settings → Permissions.",
                valueSchema = ConfigSchema.Bool,
                access = ConfigAccess.READONLY,
                risk = ConfigRisk.DESTRUCTIVE,
                revertable = false,
                reader = { ConfigValue.Bool(MinisConfigPermissionStore.isEnabled) },
                writer = { _ ->
                    throw ConfigError.PermissionDenied(
                        "Master switch — toggle via Settings → Permissions only"
                    )
                },
            )
        )
    }

    // -- Session (current chat) --
    //
    // Mirrors iOS `session.*` keys in ConfigRegistry+Builtins.swift. The
    // "current session" is whatever ChatScreen is composed with right now,
    // tracked via `sessionIdProvider()`. Reads return empty
    // / writes throw "No active session" when no chat is foregrounded.

    private fun registerSession(
        r: ConfigRegistry,
        providerRepo: ProviderRepository,
        chatRepo: ChatRepository,
        sessionIdProvider: () -> String?,
    ) {
        r.register(
            ClosureField(
                path = "session.thinkingLevel",
                displayName = "Thinking level (current session)",
                // [audit-0909 T6-M1] Keep this list in sync with
                // thinkingLevelToToken/FromToken below (8 levels): the UI
                // (ChatSlashTokenExt setThinkingLevel, ModelGroupDetailScreen)
                // persists MAX/ULTRA/AUTO too, so the reader legitimately
                // returns those tokens — a narrower StrEnum rejected the
                // agent's own round-trip write with `validation_failed`.
                description = "off / low / medium / high / xhigh / max / ultra / auto. Applied to the active chat.",
                valueSchema = ConfigSchema.StrEnum(
                    listOf("off", "low", "medium", "high", "xhigh", "max", "ultra", "auto")
                ),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                reader = {
                    val sid = sessionIdProvider()
                    if (sid == null) ConfigValue.Null
                    else {
                        // [dv25-A06] Read the LIVE value, not just the row: the
                        // writer above pushes into the resident view model, so a
                        // row-only read reported a different value than the one
                        // the write had just accepted (and for a draft it
                        // reported the empty default while the VM held the real
                        // level). Precedence lives in
                        // `SessionConfigWriteThrough.thinkingLevelReadSource`.
                        val vm = residentViewModel(sid)
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        val level = when (
                            thinkingLevelReadSource(
                                hasLiveViewModel = vm != null,
                                vmAdoptedRow = vm?.sessionLoaded?.value == true,
                                hasPersistedRow = session != null,
                            )
                        ) {
                            SessionConfigReadSource.ResidentViewModel ->
                                vm!!.requestedThinkingLevel.value
                            SessionConfigReadSource.PersistedRow -> {
                                val raw = session?.thinkingOverride
                                if (raw == null) ThinkingLevel.OFF
                                else runCatching { ThinkingLevel.valueOf(raw) }.getOrDefault(ThinkingLevel.OFF)
                            }
                            SessionConfigReadSource.Absent -> ThinkingLevel.OFF
                        }
                        ConfigValue.Str(thinkingLevelToToken(level))
                    }
                },
                writer = { v ->
                    val token = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val level = thinkingLevelFromToken(token)
                        ?: throw ConfigError.InvalidValue("Unknown thinking level: $token")
                    val sid = sessionIdProvider()
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    // [dv24-A05] Persist AND push into the resident VM: the row is
                    // the only cross-restart store, the VM is what the user sees.
                    writeSessionConfig(
                        sessionId = sid,
                        path = "session.thinkingLevel",
                        chatRepo = chatRepo,
                        persist = { chatRepo.dao.updateThinkingOverride(sid, level.name) },
                        refresh = { vm -> vm.setThinkingLevel(level) },
                    )
                },
            )
        )
        r.register(
            ClosureField(
                path = "session.primaryModel",
                displayName = "Primary model (current session)",
                description = "Set as `entry:<uuid>` or `group:<id>` (use `models` / `groups` topics to discover ids). Empty clears the binding.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    val sid = sessionIdProvider()
                    if (sid == null) ConfigValue.Str("")
                    else {
                        // [dv25-A06] Same asymmetry as `session.thinkingLevel`, but
                        // the precedence is ROW-first: the row holds the explicit
                        // pin, the VM holds the model in use (also non-empty when
                        // the pin is empty and loadSession's fallback chain picked
                        // one). Reading the VM would invent a pin the user never
                        // made. See `SessionConfigWriteThrough.modelBindingReadSource`.
                        val vm = residentViewModel(sid)
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        ConfigValue.Str(
                            when (
                                modelBindingReadSource(
                                    hasLiveViewModel = vm != null,
                                    hasPersistedRow = session != null,
                                )
                            ) {
                                SessionConfigReadSource.ResidentViewModel ->
                                    residentBindingToken(vm!!)
                                SessionConfigReadSource.PersistedRow ->
                                    formatBinding(session?.modelBinding)
                                SessionConfigReadSource.Absent -> ""
                            }
                        )
                    }
                },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val sid = sessionIdProvider()
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    if (s.isEmpty()) {
                        // Clear the binding — fall back to default group/entry on next load.
                        // [dv24-A05] The live VM must run the SAME fallback the next
                        // `loadSession()` would (priority 3: default group → new-chat
                        // default), otherwise the chat keeps showing the cleared pin
                        // while the row already says "no binding".
                        writeSessionConfig(
                            sessionId = sid,
                            path = "session.primaryModel",
                            chatRepo = chatRepo,
                            persist = { chatRepo.dao.updateSessionBinding(sid, "", "") },
                            refresh = { vm -> vm.applyDefaultModelFallback() },
                        )
                        return@ClosureField
                    }
                    val cfg = providerRepo.config.value
                    val (bindingJson, modelId) = when {
                        s.startsWith("entry:") -> {
                            val uuid = s.removePrefix("entry:")
                            if (uuid.isEmpty()) {
                                throw ConfigError.InvalidValue("entry uuid is empty")
                            }
                            val entry = cfg.modelEntries.find { it.id == uuid }
                                ?: throw ConfigError.InvalidValue("Unknown model entry uuid: $uuid")
                            // [dv25-A06] Existence is not usability: writing a
                            // binding to a disabled instance / a keyless instance
                            // produces a row that `restoreFromBinding` then REFUSES
                            // (see decideSessionModelResolution), so the write
                            // reported success and the chat fell back to another
                            // model. Same decision source as the runtime gate.
                            requireUsableEntry(providerRepo, entry, "entry:$uuid")
                            """{"type":"entry","entryId":"$uuid"}""" to entry.baseModel.id
                        }
                        s.startsWith("group:") -> {
                            val gid = s.removePrefix("group:")
                            if (gid.isEmpty()) {
                                throw ConfigError.InvalidValue("group id is empty")
                            }
                            val group = cfg.modelGroups.find { it.id == gid }
                                ?: throw ConfigError.InvalidValue("Unknown group id: $gid")
                            // [dv25-A06] A group binding is usable only when at
                            // least one member is: `resolveProviderFromGroup`
                            // returns false when every member is disabled /
                            // keyless, so binding such a group wrote a row the
                            // runtime then discarded. Pick the first USABLE member
                            // for `model_id` (it used to be the first member,
                            // which could itself be the unusable one).
                            val firstEntry = firstUsableGroupMember(providerRepo, group)
                            """{"type":"group","groupId":"$gid"}""" to firstEntry.baseModel.id
                        }
                        else -> throw ConfigError.InvalidValue(
                            "Expected `entry:<uuid>` or `group:<id>`, got '$s'"
                        )
                    }
                    writeSessionConfig(
                        sessionId = sid,
                        path = "session.primaryModel",
                        chatRepo = chatRepo,
                        persist = { chatRepo.dao.updateSessionBinding(sid, bindingJson, modelId) },
                        refresh = { vm ->
                            // Same three priorities `loadSession` uses: the persisted
                            // binding first (this also honours the provider-enabled /
                            // credential gates), then the default group → new-chat
                            // default when that binding is unusable.
                            if (!vm.restoreFromBinding(bindingJson)) vm.applyDefaultModelFallback()
                        },
                    )
                },
            )
        )
        // T311: iOS `session.subModel` is not registered on Android — the
        // platform has no per-session sub/fallback-model concept (no
        // `subModelSource` field in `model_binding` JSON, no agent-loop
        // fallback path). Reported up at the spec level rather than
        // adding a no-op key that silently throws on every write.
    }

    private const val TAG = "ConfigBuiltins"

    /**
     * [dv24-A05] A `session.*` write lands on **every** surface that can hold
     * the value, in one call.
     *
     * Before this, `minis-config set session.thinkingLevel high` /
     * `session.primaryModel entry:<uuid>` ran a single
     * `runBlocking { dao.update… }`. `ChatViewModel` reads both fields exactly
     * once, inside `loadSession()`, and `ChatViewModelStore` keeps that instance
     * alive for the whole process — so the agent's write returned success while
     * the chat kept using the OLD model / level. Worse, the next in-app picker
     * tap wrote that stale in-memory state back over the row, silently undoing
     * the config write.
     *
     * Both halves are load-bearing, which is why they live in one function
     * rather than at each call site:
     *   - `persist` — the Room row is the only cross-restart store;
     *   - `refresh` — the resident VM is the only live surface, and it is the
     *     one that would otherwise clobber the row.
     * The refresh goes through the VM's own entry points (`setThinkingLevel`,
     * `restoreFromBinding` / `applyDefaultModelFallback`), i.e. the same
     * decisions the UI and `loadSession()` take — no second state path.
     *
     * Row first, VM second: if the VM-side entry throws, the value is already
     * persisted (the write succeeded) and the UI is one load behind — the
     * opposite order would report failure for a write that did land.
     *
     * Draft sessions (`__new__…`, no row yet): the UPDATE matches 0 rows, so
     * the resident VM is the only place the value can live — it holds it and
     * `ensureSession()` folds it into the row at insert time. With no VM either
     * there is nothing to hold it; that case is logged rather than silently
     * accepted (it used to be indistinguishable from success).
     */
    private fun writeSessionConfig(
        sessionId: String,
        path: String,
        chatRepo: ChatRepository,
        persist: suspend () -> Unit,
        refresh: (ChatViewModel) -> Unit,
    ) {
        // The resident VM is looked up BEFORE the write and refreshed after it
        // (see SessionConfigWriteThrough.deliver): the row is the only
        // cross-restart store, the VM is the only live surface, and the VM is
        // the one that would otherwise clobber the row with stale memory.
        //
        // `peekViewModel` is main-thread-only (it walks ViewModelStore), hence
        // the hop — and the refresh must run on Main too, exactly like the
        // in-app picker does.
        val vm = runOnMainThread { ChatViewModelStore.peekViewModel(sessionId) }
        SessionConfigWriteThrough.deliver(
            vm = vm,
            persist = persist,
            hasPersistedRow = { chatRepo.dao.getSession(sessionId) != null },
            refresh = { resident ->
                runOnMainThread {
                    runCatching { refresh(resident) }.onFailure {
                        AppLogger.warning(
                            TAG,
                            "$path: resident view model refresh failed for " +
                                "'$sessionId': ${it.message}",
                        )
                    }
                }
            },
            onDraftWithoutRow = {
                AppLogger.warning(
                    TAG,
                    "$path: session '$sessionId' has no persisted row and no resident " +
                        "view model — the UPDATE matched 0 rows, so the value is not retained",
                )
            },
        )
    }

    /**
     * Run [block] on the Android main thread and wait for it.
     *
     * `minis-config` is served from the offload worker thread, while every
     * `ChatViewModel` `MutableStateFlow` write and every `ProviderFactory.create`
     * call behind the VM's entry points is main-thread-only. Waiting (rather
     * than `launch`) is what makes the write-through real: the agent's next
     * sentence has to see the new level. The fast path exists because hopping to
     * a looper we are already running on would deadlock `runBlocking`.
     */
    /**
     * [dv25-A06] The resident ViewModel for [sessionId], or null.
     *
     * `peekViewModel` is main-thread-only (it walks the ViewModelStore) and
     * non-creating (a read for a chat that is not on screen must not
     * materialise one) — both properties are load-bearing here, which is why
     * the read path uses the same lookup as the write path.
     */
    private fun residentViewModel(sessionId: String): ChatViewModel? =
        runOnMainThread { ChatViewModelStore.peekViewModel(sessionId) }

    /**
     * [dv25-A06] The binding token the resident VM is currently on, in the same
     * `entry:<uuid>` / `group:<id>` syntax [formatBinding] renders from the row
     * (empty = no explicit pin).
     *
     * Only consulted when the session has NO row (see
     * `modelBindingReadSource`): a draft's write lands in the VM, so the VM is
     * the only place that can answer. `selectedGroupId` first — a group-bound
     * VM also carries a concrete `activeEntryId` (the rotated member), and
     * reporting that member as an `entry:` pin would be a different fact.
     */
    private fun residentBindingToken(vm: ChatViewModel): String {
        val groupId = vm.selectedGroupId.value
        if (!groupId.isNullOrEmpty()) return "group:$groupId"
        val entryId = vm.activeEntryId.value
        if (!entryId.isNullOrEmpty()) return "entry:$entryId"
        return ""
    }

    /**
     * [dv25-A06] Fail-closed availability gate for `session.primaryModel`
     * (entry branch). Same decision as the runtime gate
     * ([decideSessionModelResolution]) — a binding the chat would refuse to
     * restore must not be reported as written.
     */
    private fun requireUsableEntry(
        providerRepo: ProviderRepository,
        entry: com.rikkaminis.app.data.model.ModelEntry,
        what: String,
    ) {
        val instance = providerRepo.instance(entry.providerInstanceId)
        val apiKey = instance?.let { providerRepo.loadApiKey(it.id) }
        val decision = decideSessionModelResolution(entry, instance, apiKey)
        if (decision is SessionModelResolution.Unusable) {
            throw ConfigError.InvalidValue("$what is not usable — ${unusableReasonMessage(decision.reason)}")
        }
    }

    /**
     * [dv25-A06] The first member of [group] that the runtime would actually
     * use, or [ConfigError.InvalidValue] when there is none.
     *
     * Mirrors `ProviderRepository.enabledMemberEntries` +
     * `resolveProviderFromGroup`'s credential check in ONE place, so the
     * validation and the later restore cannot disagree.
     */
    private fun firstUsableGroupMember(
        providerRepo: ProviderRepository,
        group: com.rikkaminis.app.data.model.ModelGroup,
    ): com.rikkaminis.app.data.model.ModelEntry {
        if (group.memberEntryIds.isEmpty()) {
            throw ConfigError.InvalidValue("Group ${group.id} has no member entries")
        }
        val cfg = providerRepo.config.value
        val members = group.memberEntryIds.mapNotNull { id -> cfg.modelEntries.find { it.id == id } }
        if (members.isEmpty()) {
            throw ConfigError.InvalidValue(
                "Group ${group.id} references no existing model entry — " +
                    "every member id in ${group.memberEntryIds} is gone"
            )
        }
        members.firstOrNull { member ->
            val instance = providerRepo.instance(member.providerInstanceId)
            decideSessionModelResolution(
                member,
                instance,
                instance?.let { providerRepo.loadApiKey(it.id) },
            ) is SessionModelResolution.Use
        }?.let { return it }
        throw ConfigError.InvalidValue(
            "Group ${group.id} has no usable member — every member's provider " +
                "instance is disabled or has no API key"
        )
    }

    /** [dv25-A06] Human-readable cause for a rejected `session.primaryModel`. */
    private fun unusableReasonMessage(reason: UnusableModelReason): String = when (reason) {
        UnusableModelReason.MISSING_INSTANCE ->
            "its provider instance no longer exists"
        UnusableModelReason.PROVIDER_DISABLED ->
            "its provider instance is disabled (enable it in Settings → Providers)"
        UnusableModelReason.MISSING_API_KEY ->
            "its provider instance has no API key"
    }

    private fun <T> runOnMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        return runBlocking { withContext(Dispatchers.Main) { block() } }
    }

    /** iOS uses lowercase tokens (`off`/`low`/…); Android `ThinkingLevel.name`
     *  is uppercase. Centralised so reader/writer round-trip identical strings. */
    private fun thinkingLevelToToken(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF -> "off"
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // [T-android-thinking-level-arch] GPT-5.6 higher tiers.
        ThinkingLevel.MAX -> "max"
        ThinkingLevel.ULTRA -> "ultra"
        ThinkingLevel.AUTO -> "auto"
    }

    private fun thinkingLevelFromToken(token: String): ThinkingLevel? = when (token) {
        "off" -> ThinkingLevel.OFF
        "low" -> ThinkingLevel.LOW
        "medium" -> ThinkingLevel.MEDIUM
        "high" -> ThinkingLevel.HIGH
        "xhigh" -> ThinkingLevel.XHIGH
        "max" -> ThinkingLevel.MAX
        "ultra" -> ThinkingLevel.ULTRA
        "auto" -> ThinkingLevel.AUTO
        else -> null
    }

    /** Convert the `model_binding` JSON the chat layer writes into the
     *  `entry:<uuid>` / `group:<id>` form the iOS minis-config surface uses.
     *  Returns empty string when the session has no explicit binding (the
     *  default group/entry fallback applies on next load). */
    private fun formatBinding(bindingJson: String?): String {
        if (bindingJson.isNullOrEmpty()) return ""
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "entry" -> {
                    val id = obj.optString("entryId")
                    if (id.isEmpty()) "" else "entry:$id"
                }
                "group" -> {
                    val id = obj.optString("groupId")
                    if (id.isEmpty()) "" else "group:$id"
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    // -- Appearance & display --

    private fun registerAppearance(r: ConfigRegistry, context: Context) {
        // Source-of-truth lives in `appearance_prefs` (see
        // ui/settings/AppearanceScreen.kt PREF_APPEARANCE).
        val prefs = context.getSharedPreferences("appearance_prefs", Context.MODE_PRIVATE)
        r.register(
            PrefsIntCodedEnumField(
                path = "appearance.theme",
                displayName = "Theme",
                description = "Light / Dark / System color scheme.",
                prefs = prefs,
                key = "theme_mode",
                cases = listOf("system", "light", "dark"),
                defaultIndex = 0,
            )
        )
        r.register(
            PrefsIntCodedEnumField(
                path = "appearance.launchSession",
                displayName = "Launch behaviour",
                description = "What the app opens on cold start: auto, the last session, a new chat, or a safe start (new chat, no resume bounce).",
                prefs = prefs,
                key = com.rikkaminis.app.ui.settings.KEY_LAUNCH_SESSION,
                cases = listOf("auto", "lastSession", "newChat", "safeStart"),
                defaultIndex = 0,
            )
        )
        // T-chat-title-pill: sticky session-title pill on the chat screen.
        // Default ON; key string deliberately matches iOS so a future
        // cross-platform settings sync round-trips identically.
        r.register(
            PrefsBoolField(
                path = "appearance.show_chat_title",
                displayName = "Show chat title pill",
                description = "Show a sticky title pill at the top of the chat while scrolling through history.",
                prefs = prefs,
                key = com.rikkaminis.app.ui.settings.KEY_SHOW_CHAT_TITLE,
                defaultValue = true,
            )
        )
        r.register(
            PrefsStringField(
                path = "appearance.language",
                displayName = "App language",
                // T311: aligned with iOS appearance.language help wording.
                description = "BCP-47 code (zh-Hans, en, ja, …) or empty for system.",
                prefs = prefs,
                key = "app_language",
                defaultValue = "",
                maxLength = 16,
            )
        )
    }

    // -- Chat / input --

    private fun registerChat(r: ConfigRegistry, context: Context) {
        // [T-backup-dead-key-fix] These two fields previously wrote to a
        // `minis_settings` file with keys `return_key_behavior` /
        // `keep_screen_awake_during_tasks` that NOTHING reads: AppearanceScreen
        // and the runtime read `appearance_prefs` with the iOS-aligned keys
        // `returnKeyBehavior` / `keepScreenAwakeDuringTasks`. A minis-config
        // write or a backup-restore therefore landed in a dead file and never
        // took effect. Repoint to the same SharedPreferences file+keys the UI
        // uses, via the shared constants so the two can't drift apart again.
        val prefs = context.getSharedPreferences(
            com.rikkaminis.app.ui.settings.PREF_APPEARANCE,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsIntCodedEnumField(
                path = "chat.returnKey",
                displayName = "Return key behavior",
                description = "What the on-screen Return key does in the chat box.",
                prefs = prefs,
                key = com.rikkaminis.app.ui.settings.KEY_RETURN_KEY_BEHAVIOR,
                cases = listOf("newline", "send"),
                defaultIndex = 0,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.keepScreenAwake",
                displayName = "Keep screen awake during tasks",
                description = "Prevents auto-lock while the agent is busy.",
                prefs = prefs,
                key = com.rikkaminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE,
                defaultValue = false,
            )
        )
        // T311: chat.toolPreview / inputFontSize / messageFontSize live in
        // `appearance_prefs` — same SharedPreferences AppearanceScreen.kt
        // reads/writes, so flipping via minis-config flows back into the
        // settings UI and into ChatScreen's `OnSharedPreferenceChangeListener`
        // (Compose recomposes immediately, no manual cache invalidation
        // needed on Android — there is no analogue to iOS
        // FontSettings.messageBaseScale .Published cache).
        val appearancePrefs = context.getSharedPreferences(
            com.rikkaminis.app.ui.settings.PREF_APPEARANCE,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "chat.toolPreview",
                displayName = "Tool preview",
                description = "Show live preview during agent tool execution.",
                prefs = appearancePrefs,
                key = com.rikkaminis.app.ui.settings.KEY_TOOL_PREVIEW,
                defaultValue = false,
            )
        )
        // Whole floating tool-status bar visibility (independent of the
        // thumbnail preview above). Default ON keeps existing behavior.
        r.register(
            PrefsBoolField(
                path = "appearance.showToolStatusBar",
                displayName = "Tool status bar",
                description = "When ON, the floating bar above the composer while the agent calls tools is shown (status text, spinner, and thumbnail preview). Turn OFF to hide it entirely.",
                prefs = appearancePrefs,
                key = com.rikkaminis.app.ui.settings.KEY_TOOL_STATUS_BAR,
                defaultValue = true,
            )
        )
        r.register(fontScaleField(
            path = "chat.inputFontSize",
            displayName = "Input font size",
            description = "Font size for the chat composer text editor.",
            prefs = appearancePrefs,
            key = com.rikkaminis.app.ui.settings.KEY_FONT_CHAT_INPUT,
        ))
        r.register(fontScaleField(
            path = "chat.messageFontSize",
            displayName = "Message font size",
            description = "Base font size for assistant / user message bodies (markdown). Setting this invalidates rendered markdown caches.",
            prefs = appearancePrefs,
            key = com.rikkaminis.app.ui.settings.KEY_FONT_MESSAGE,
        ))
        // The three below were settable in AppearanceScreen but never
        // registered, so they were invisible to minis-config AND silently
        // absent from every backup (ConfigBackup walks the registry).
        r.register(fontScaleField(
            path = "appearance.appFontSize",
            displayName = "App font size",
            description = "Base font size for app chrome outside message bodies (lists, settings, labels).",
            prefs = appearancePrefs,
            key = com.rikkaminis.app.ui.settings.KEY_FONT_APP_BASE,
        ))
        r.register(
            PrefsBoolField(
                path = "chat.autoExpandThinking",
                displayName = "Auto-expand thinking",
                description = "When ON, a model's reasoning block starts expanded instead of collapsed.",
                prefs = appearancePrefs,
                key = com.rikkaminis.app.ui.settings.KEY_AUTO_EXPAND_THINKING,
                defaultValue = false,
            )
        )
        // [T-thinking-header-toggles] The thinking header's two optional
        // right-edge extras. Registered so backup/restore and minis-config see
        // them like every other pref; defaults mirror the pre-toggle behaviour
        // (length ON, duration OFF — the latter is in-session only anyway).
        r.register(
            PrefsBoolField(
                path = "chat.thinkingShowCharCount",
                displayName = "Show thinking length",
                description = "When ON, a thinking block header shows its content length (e.g. 9K) next to the label.",
                prefs = appearancePrefs,
                key = com.rikkaminis.app.ui.settings.KEY_THINKING_SHOW_CHAR_COUNT,
                defaultValue = true,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.thinkingShowDuration",
                displayName = "Show thinking time",
                description = "When ON, a finished thinking block header shows how long that reasoning phase took. The value is measured in-session only.",
                prefs = appearancePrefs,
                key = com.rikkaminis.app.ui.settings.KEY_THINKING_SHOW_DURATION,
                defaultValue = false,
            )
        )
        // Chat action customization (Settings → Appearance → Chat Menu).
        // The action pool spans the top-right "..." menu AND the history-drawer
        // footer. Each stable key contributes two independent bool fields:
        //   appearance.chatMenu.<key>      — "..." menu visibility
        //   appearance.chatMenuPin.<key>   — footer pin
        // plus two comma-separated order fields. All live in appearance_prefs so
        // they round-trip through minis-config AND every local backup
        // automatically (ConfigBackup walks the registry). Defaults keep the
        // eight original menu entries visible and pin Token Usage + Settings to
        // the footer — identical to the pre-customization UI.
        for (entryKey in ChatMenuPrefs.ALL_ENTRIES) {
            r.register(
                PrefsBoolField(
                    path = ChatMenuPrefs.visibilityPath(entryKey),
                    displayName = "Chat menu: show $entryKey",
                    description = "When ON, the \"$entryKey\" entry is shown in the chat \"...\" menu.",
                    prefs = appearancePrefs,
                    key = ChatMenuPrefs.visibilityKey(entryKey),
                    defaultValue = ChatMenuPrefs.defaultVisible(entryKey),
                )
            )
            r.register(
                PrefsBoolField(
                    path = ChatMenuPrefs.pinPath(entryKey),
                    displayName = "Chat footer: pin $entryKey",
                    description = "When ON, the \"$entryKey\" action is pinned to the history-drawer footer.",
                    prefs = appearancePrefs,
                    key = ChatMenuPrefs.pinKey(entryKey),
                    defaultValue = ChatMenuPrefs.defaultPinned(entryKey),
                )
            )
        }
        r.register(
            PrefsStringField(
                path = ChatMenuPrefs.ORDER_PATH,
                displayName = "Chat menu order",
                description = "Comma-separated stable keys defining the display order of customizable chat \"...\" menu entries. Unknown keys are ignored; missing keys fall back to their default position.",
                prefs = appearancePrefs,
                key = ChatMenuPrefs.ORDER_KEY,
                defaultValue = ChatMenuPrefs.DEFAULT_ORDER.joinToString(","),
                maxLength = 512,
            )
        )
        r.register(
            PrefsStringField(
                path = ChatMenuPrefs.PIN_ORDER_PATH,
                displayName = "Chat footer pin order",
                description = "Comma-separated stable keys defining the display order of actions pinned to the history-drawer footer. Unknown keys are ignored; missing pinned keys fall back to their default position. Settings is always anchored last when pinned.",
                prefs = appearancePrefs,
                key = ChatMenuPrefs.PIN_ORDER_KEY,
                defaultValue = ChatMenuPrefs.DEFAULT_ORDER.joinToString(",") +
                    "," + ChatMenuPrefs.TOKEN_USAGE + "," + ChatMenuPrefs.SETTINGS,
                maxLength = 512,
            )
        )
        // Attach ("+") menu customization (Settings → Appearance → Chat Menu).
        // Independent domain from the "..." menu pool: three attach actions each
        // get one visibility bool plus one order string, all in appearance_prefs
        // so they round-trip through minis-config and local backups too.
        for (entryKey in AttachActionCatalog.DEFAULT_ORDER) {
            r.register(
                PrefsBoolField(
                    path = ChatMenuPrefs.attachVisibilityPath(entryKey),
                    displayName = "Chat attach: show $entryKey",
                    description = "When ON, the \"$entryKey\" attach action is shown in the composer \"+\" menu.",
                    prefs = appearancePrefs,
                    key = ChatMenuPrefs.attachVisibilityKey(entryKey),
                    defaultValue = true,
                )
            )
        }
        r.register(
            PrefsStringField(
                path = ChatMenuPrefs.ATTACH_ORDER_PATH,
                displayName = "Chat attach order",
                description = "Comma-separated stable keys defining the display order of composer attach \"+\" menu actions. Unknown keys are ignored; missing keys fall back to their default position.",
                prefs = appearancePrefs,
                key = ChatMenuPrefs.ATTACH_ORDER_KEY,
                defaultValue = AttachActionCatalog.DEFAULT_ORDER.joinToString(","),
                maxLength = 256,
            )
        )
        // Top-bar pinned-button visibility (independent of the "..." menu pool).
        r.register(
            PrefsBoolField(
                path = "appearance.topBar.inputHistory",
                displayName = "Top bar: show Input History button",
                description = "When ON, a list-bullet icon appears in the top bar alongside New Chat, " +
                    "giving one-tap access to a searchable history of all messages in the session. " +
                    "Hiding it does not disable the feature — it can still be re-enabled here.",
                prefs = appearancePrefs,
                key = "topBar.inputHistory.visible",
                defaultValue = true,
            )
        )
        // Composer model-picker button visibility (independent fixed slot).
        r.register(
            PrefsBoolField(
                path = "appearance.composer.modelPickerButton",
                displayName = "Composer: show model-picker button",
                description = "When ON, the up-arrow circle button appears at the left of the input row " +
                    "for one-tap access to the model picker. Hiding it does not disable the feature — " +
                    "the model name in the top-right subtitle stays tappable and opens the same picker.",
                prefs = appearancePrefs,
                key = "composer.modelPickerButton.visible",
                defaultValue = true,
            )
        )

        // ── [feat/chat-tuning-panel] Chat Tuning knobs ─────────────────────
        // Previously hard-coded literals in ChatScreen / StreamingMarkdownText
        // / ChatInputArea. Defaults equal those literals, so an untouched
        // install behaves byte-identically. Registered here so `minis-config`
        // can read/write them and ConfigBackup carries them; the in-app
        // Chat Tuning panel reads/writes the same prefs file.
        val chatTuningPrefs = context.getSharedPreferences(
            com.rikkaminis.app.data.ChatTuningPrefs.PREFS, Context.MODE_PRIVATE
        )
        r.register(
            PrefsIntField(
                path = "chat.scrollNearBottomDp",
                displayName = "Chat: bottom-stick threshold (dp)",
                description = "How close to the bottom still counts as \"at the bottom\" — governs " +
                    "auto-follow stickiness and when the jump-to-bottom button appears. Default 32.",
                prefs = chatTuningPrefs,
                key = com.rikkaminis.app.data.ChatTuningPrefs.KEY_SCROLL_NEAR_BOTTOM_DP,
                defaultValue = com.rikkaminis.app.data.ChatTuningPrefs.SCROLL_NEAR_BOTTOM_DEFAULT,
                minValue = com.rikkaminis.app.data.ChatTuningPrefs.SCROLL_NEAR_BOTTOM_MIN,
                maxValue = com.rikkaminis.app.data.ChatTuningPrefs.SCROLL_NEAR_BOTTOM_MAX,
            )
        )
        r.register(
            PrefsIntField(
                path = "chat.prewarmRowLimit",
                displayName = "Chat: pre-render rows on open",
                description = "How many newest long messages are block-parsed off-main when a chat " +
                    "opens, so the first frame renders from cache. Higher = smoother cold open, " +
                    "more CPU/memory. Default 16.",
                prefs = chatTuningPrefs,
                key = com.rikkaminis.app.data.ChatTuningPrefs.KEY_PREWARM_ROW_LIMIT,
                defaultValue = com.rikkaminis.app.data.ChatTuningPrefs.PREWARM_ROW_LIMIT_DEFAULT,
                minValue = com.rikkaminis.app.data.ChatTuningPrefs.PREWARM_ROW_LIMIT_MIN,
                maxValue = com.rikkaminis.app.data.ChatTuningPrefs.PREWARM_ROW_LIMIT_MAX,
            )
        )
        r.register(
            PrefsIntField(
                path = "chat.codePreviewLines",
                displayName = "Chat: code-block fold threshold (lines)",
                description = "Code blocks taller than this many lines start folded with an " +
                    "\"Expand N lines\" button. Default 20.",
                prefs = chatTuningPrefs,
                key = com.rikkaminis.app.data.ChatTuningPrefs.KEY_CODE_PREVIEW_LINES,
                defaultValue = com.rikkaminis.app.data.ChatTuningPrefs.CODE_PREVIEW_LINES_DEFAULT,
                minValue = com.rikkaminis.app.data.ChatTuningPrefs.CODE_PREVIEW_LINES_MIN,
                maxValue = com.rikkaminis.app.data.ChatTuningPrefs.CODE_PREVIEW_LINES_MAX,
            )
        )
        r.register(
            PrefsIntField(
                path = "chat.tablePreviewRows",
                displayName = "Chat: table fold threshold (rows)",
                description = "Tables taller than this many rows start folded. Default 10.",
                prefs = chatTuningPrefs,
                key = com.rikkaminis.app.data.ChatTuningPrefs.KEY_TABLE_PREVIEW_ROWS,
                defaultValue = com.rikkaminis.app.data.ChatTuningPrefs.TABLE_PREVIEW_ROWS_DEFAULT,
                minValue = com.rikkaminis.app.data.ChatTuningPrefs.TABLE_PREVIEW_ROWS_MIN,
                maxValue = com.rikkaminis.app.data.ChatTuningPrefs.TABLE_PREVIEW_ROWS_MAX,
            )
        )
        r.register(
            PrefsIntField(
                path = "chat.markdownLineHeightSp",
                displayName = "Chat: Markdown line height (sp)",
                description = "Line spacing for message bodies, in sp at the default font scale " +
                    "(still follows the message font-size setting). Default 24.",
                prefs = chatTuningPrefs,
                key = com.rikkaminis.app.data.ChatTuningPrefs.KEY_MARKDOWN_LINE_HEIGHT_SP,
                defaultValue = com.rikkaminis.app.data.ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_DEFAULT,
                minValue = com.rikkaminis.app.data.ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_MIN,
                maxValue = com.rikkaminis.app.data.ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_MAX,
            )
        )
        r.register(
            PrefsIntField(
                path = "chat.inputMaxLines",
                displayName = "Chat: composer max lines",
                description = "The input box grows up to this many lines before it scrolls " +
                    "internally. Default 6.",
                prefs = chatTuningPrefs,
                key = com.rikkaminis.app.data.ChatTuningPrefs.KEY_INPUT_MAX_LINES,
                defaultValue = com.rikkaminis.app.data.ChatTuningPrefs.INPUT_MAX_LINES_DEFAULT,
                minValue = com.rikkaminis.app.data.ChatTuningPrefs.INPUT_MAX_LINES_MIN,
                maxValue = com.rikkaminis.app.data.ChatTuningPrefs.INPUT_MAX_LINES_MAX,
            )
        )
        r.register(
            PrefsIntField(
                path = "chat.sendSwipeThresholdDp",
                displayName = "Chat: swipe-up-to-send distance (dp)",
                description = "How far you must swipe up inside the input to arm the send gesture. " +
                    "Default 120.",
                prefs = chatTuningPrefs,
                key = com.rikkaminis.app.data.ChatTuningPrefs.KEY_SEND_SWIPE_THRESHOLD_DP,
                defaultValue = com.rikkaminis.app.data.ChatTuningPrefs.SEND_SWIPE_THRESHOLD_DEFAULT,
                minValue = com.rikkaminis.app.data.ChatTuningPrefs.SEND_SWIPE_THRESHOLD_MIN,
                maxValue = com.rikkaminis.app.data.ChatTuningPrefs.SEND_SWIPE_THRESHOLD_MAX,
            )
        )
    }

    /** Mirrors iOS `fontScaleField` — exposes the integer scale level
     *  (-2..3 in AppearanceScreen.kt) as a string token (xSmall / small /
     *  default / medium / large / extraLarge) so reads round-trip the same
     *  vocabulary as `chat.inputFontSize` / `appearance.appFontSize` on
     *  iOS. Centralised so both Android axes share one factory. */
    private fun fontScaleField(
        path: String,
        displayName: String,
        description: String,
        prefs: android.content.SharedPreferences,
        key: String,
    ): ConfigField {
        val cases = listOf("xSmall", "small", "default", "medium", "large", "extraLarge")
        return ClosureField(
            path = path,
            displayName = displayName,
            description = description,
            valueSchema = ConfigSchema.StrEnum(cases),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = {
                val level = prefs.getInt(key, 0)
                val token = when (level) {
                    -2 -> "xSmall"
                    -1 -> "small"
                    0 -> "default"
                    1 -> "medium"
                    2 -> "large"
                    3 -> "extraLarge"
                    else -> "default"
                }
                ConfigValue.Str(token)
            },
            writer = { v ->
                val token = (v as? ConfigValue.Str)?.value
                    ?: throw ConfigError.TypeMismatch("string")
                val level = when (token) {
                    "xSmall" -> -2
                    "small" -> -1
                    "default" -> 0
                    "medium" -> 1
                    "large" -> 2
                    "extraLarge" -> 3
                    else -> throw ConfigError.InvalidValue("Unknown font scale: $token")
                }
                prefs.edit().putInt(key, level).apply()
            },
        )
    }

    // -- Background --

    private fun registerBackground(r: ConfigRegistry, context: Context) {
        // [FIX-6 / F-238 + F-240] Keys come from BackgroundSettingsRepository —
        // the single source of truth for this prefs file. The registry used to
        // re-type the key strings here and they had drifted: it wrote
        // `background_notifications_enabled` / `enhanced_background_execution`
        // while every reader in the app used `taskNotificationsEnabled` (and no
        // reader at all used the second one), so `minis-config set
        // background.notifications false` was silently a no-op and the
        // completion notification kept firing.
        //
        // [T-android-config-feature-unavailable] `background.enhanced`
        // ("Enhanced background execution" / "Keep agent tasks running when the
        // app is backgrounded") was removed 2026-09-20 — Android has no such
        // feature: nothing in the app reads that key and AgentForegroundService's
        // lifetime is driven by SessionActivityTracker, not by a pref. The
        // registration was a leftover mirror of iOS's screen name
        // (`EnhancedBackgroundSettingsView`), so it only served to let
        // minis-config / a backup-restore write a key that does nothing.
        // Tombstone left deliberately (same pattern as the Live Updates note
        // below) so the field isn't re-added by a future iOS-mirroring pass.
        val prefs = context.getSharedPreferences(
            com.rikkaminis.app.data.repository.BackgroundSettingsRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "background.notifications",
                displayName = "Background notifications",
                description = "Post a system notification when long-running tasks complete. Same switch as Settings → Background → Task Notifications.",
                prefs = prefs,
                key = com.rikkaminis.app.data.repository.BackgroundSettingsRepository
                    .KEY_TASK_NOTIFICATIONS,
                defaultValue = com.rikkaminis.app.data.repository.BackgroundSettingsRepository
                    .DEFAULT_TASK_NOTIFICATIONS,
            )
        )
        // [T-android-config-feature-unavailable] Live Updates / "dynamic
        // island" was removed 2026-08-17 — this feature (and its registration)
        // is gone; nothing here references it any more.
        //
        // [da1028-B / ISSUE-03] `background.overlay` ("show the floating
        // tool-status overlay while backgrounded") is a real switch in
        // Settings → Background with no registry entry: `minis-config set
        // background.overlay true` answered "unknown field" while the same
        // toggle was reachable by tap, and the value was in no backup scope.
        // Same key convention as the notification fields above — the key and
        // the default come from BackgroundSettingsRepository, never re-typed
        // here.
        r.register(
            PrefsBoolField(
                path = "background.overlay",
                displayName = "Background task overlay",
                description = "Show a small floating capsule with the current tool while the app is in the background. Same switch as Settings → Background → Background Task Overlay; needs permission to display over other apps.",
                prefs = prefs,
                key = com.rikkaminis.app.data.repository.BackgroundSettingsRepository
                    .KEY_BG_OVERLAY_ENABLED,
                defaultValue = com.rikkaminis.app.data.repository.BackgroundSettingsRepository
                    .DEFAULT_BG_OVERLAY_ENABLED,
            )
        )
    }

    // -- Logs --

    private fun registerLogs(r: ConfigRegistry, context: Context) {
        // T311: route through AppLogger so a write actually starts/stops the
        // capture pipeline. The previous PrefsBoolField wrote a sibling
        // `logging`/`logging_enabled` key that AppLogger never reads
        // (AppLogger uses `logging_prefs`/`logging_enabled`), so toggling via
        // minis-config did nothing. Default mirrors AppLogger's own default
        // (false — opt-in), aligning with iOS.
        r.register(
            ClosureField(
                path = "logs.enabled",
                displayName = "Logging enabled",
                description = "Capture stdout/stderr to daily log files for diagnostics.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.NORMAL,
                revertable = true,
                reader = {
                    ConfigValue.Bool(com.rikkaminis.app.logging.AppLogger.isEnabled(context))
                },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    com.rikkaminis.app.logging.AppLogger.setEnabled(context, b)
                },
            )
        )
    }

    // -- Provider / Models / Groups / EnvVars collections + aggregates --

    private fun registerProviderCollections(
        r: ConfigRegistry,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository,
    ) {
        r.register(ProvidersCollection(providerRepo, envVarRepo))
        r.register(ModelsCollection(providerRepo))
        r.register(GroupsCollection(providerRepo))
        r.register(EnvVarsCollection(envVarRepo))

        // [audit-0917] DateTimeFormatter, matching EnvVarsCollection — this
        // instance is captured by the `envvars` reader closure, so it outlives
        // the registration call and is used whenever the config reader runs
        // (possibly from different threads for concurrent RPC calls). The
        // duplicate ISO pattern here was also drifting from the collection's.
        val isoFormatter: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .withZone(java.time.ZoneOffset.UTC)

        // Aggregate read-only summary so `minis-config get providers`
        // returns a useful list of configured instances. Credentials
        // (apiKey / oauthToken) are deliberately omitted.
        r.register(
            ReadOnlyField(
                path = "providers",
                displayName = "LLM provider instances (summary)",
                description = "Read-only list of configured providers (id, label, type, credential type, enabled, base URL). Credentials are not included. Use `minis-config add providers <json>` to create a new provider (payload mirrors a provider-export `config` block, with optional `apiKey` as literal or `\$\$ENV_VAR`).",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val instances = providerRepo.config.value.instances
                    ConfigValue.Arr(
                        instances.map { inst ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(inst.id),
                                    "label" to ConfigValue.Str(inst.label),
                                    "providerType" to ConfigValue.Str(inst.providerType.name),
                                    "credentialType" to ConfigValue.Str(inst.credentialType.name),
                                    "isEnabled" to ConfigValue.Bool(inst.isEnabled),
                                    "customBaseURL" to (inst.customBaseURL?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                                    "appendV1Suffix" to ConfigValue.Bool(inst.appendV1Suffix),
                                    "useResponsesAPI" to ConfigValue.Bool(inst.useResponsesAPI),
                                    "azureMode" to ConfigValue.Bool(inst.azureMode),
                                    // [da1030-C ISSUE-04] Same symmetry as the
                                    // Debug RPC faces: the image-endpoint
                                    // picker state is readable here too
                                    // ([GH#68] keys, instance-field names).
                                    "imageEndpointMode" to ConfigValue.Str(inst.imageEndpointMode.name),
                                    "imageEndpointResolved" to (inst.imageEndpointResolved?.let { ConfigValue.Str(it.name) } ?: ConfigValue.Null),
                                    // [fix/da1020-memory-gate-provider-fields]
                                    // Same read shape as the per-instance field
                                    // (empty override → Null = branded default).
                                    "customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Aggregate read-only summary so `minis-config get models`
        // returns every model entry along with its provider context.
        r.register(
            ReadOnlyField(
                path = "models",
                displayName = "Model entries (summary)",
                description = "Read-only list of every model entry (entry_id, display_name, model_id, provider).",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val cfg = providerRepo.config.value
                    val providersById = HashMap<String, com.rikkaminis.app.data.model.ProviderInstance>(cfg.instances.size)
                    for (inst in cfg.instances) providersById[inst.id] = inst
                    ConfigValue.Arr(
                        cfg.modelEntries.map { entry ->
                            val inst = providersById[entry.providerInstanceId]
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "entry_id" to ConfigValue.Str(entry.id),
                                    "display_name" to ConfigValue.Str(entry.model.displayName),
                                    "model_id" to ConfigValue.Str(entry.baseModel.id),
                                    "provider_id" to ConfigValue.Str(entry.providerInstanceId),
                                    "provider_label" to (inst?.let { ConfigValue.Str(it.label) } ?: ConfigValue.Null),
                                    "provider_type" to (inst?.let { ConfigValue.Str(it.providerType.name) } ?: ConfigValue.Null),
                                    "is_custom" to ConfigValue.Bool(entry.isCustom),
                                    "is_hidden" to ConfigValue.Bool(entry.isHidden),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Aggregate read-only summary so `minis-config get envvars`
        // returns every env var key with its non-secret metadata.
        r.register(
            ReadOnlyField(
                path = "envvars",
                displayName = "Environment variables (summary)",
                description = "Read-only list of env var keys with group, note and createdAt. Values are never exposed.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    ConfigValue.Arr(
                        envVarRepo.entries.value.map { e ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "key" to ConfigValue.Str(e.key),
                                    "group" to ConfigValue.Str(e.group),
                                    "note" to ConfigValue.Str(e.note),
                                    "created_at" to ConfigValue.Str(isoFormatter.format(java.time.Instant.ofEpochMilli(e.createdAt))),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Privacy Mode toggle. Read/write so the user can ask the
        // agent to flip it without diving into Settings ("turn off
        // privacy mode for the next command"). Routed through
        // EnvVarPrivacyStore.setEnabled so the StateFlow fires and
        // the Compose Switch stays in sync. Default ON; risk
        // SENSITIVE because turning it OFF means the next shell
        // execute can leak unmasked secrets to the model.
        r.register(
            ClosureField(
                path = "envvars.privacyMode",
                displayName = "Privacy Mode",
                description = "When ON, env-var values that appear in shell-execute output are masked before reaching the model. Turning OFF lets the model see raw values — only do this if the user explicitly asked.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Bool(com.rikkaminis.app.data.EnvVarPrivacyStore.isEnabled) },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    com.rikkaminis.app.data.EnvVarPrivacyStore.setEnabled(b)
                },
            )
        )

        // Aggregate read-only summary so `minis-config get groups`
        // returns every model group with its expanded entries.
        r.register(
            ReadOnlyField(
                path = "groups",
                displayName = "Model groups (summary)",
                description = "Read-only list of model groups with entries expanded.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val cfg = providerRepo.config.value
                    val entriesById =
                        HashMap<String, com.rikkaminis.app.data.model.ModelEntry>(cfg.modelEntries.size)
                    for (e in cfg.modelEntries) entriesById[e.id] = e
                    val providersById =
                        HashMap<String, com.rikkaminis.app.data.model.ProviderInstance>(cfg.instances.size)
                    for (inst in cfg.instances) providersById[inst.id] = inst
                    ConfigValue.Arr(
                        cfg.modelGroups.map { g ->
                            val entries = g.memberEntryIds.map { entryId ->
                                // [dv20-a1 A-02] System voice members are
                                // virtual (never in config.modelEntries) but
                                // resolve to a real entry — surface the normal
                                // shape instead of a "missing" stub.
                                val e = entriesById[entryId] ?: SystemVoiceEntries.resolve(entryId)
                                if (e == null) {
                                    ConfigValue.Obj(
                                        linkedMapOf(
                                            "entry_id" to ConfigValue.Str(entryId),
                                            "missing" to ConfigValue.Bool(true),
                                        )
                                    )
                                } else {
                                    val inst = providersById[e.providerInstanceId]
                                    ConfigValue.Obj(
                                        linkedMapOf(
                                            "entry_id" to ConfigValue.Str(entryId),
                                            "display_name" to ConfigValue.Str(e.model.displayName),
                                            "model_id" to ConfigValue.Str(e.baseModel.id),
                                            "provider_id" to ConfigValue.Str(e.providerInstanceId),
                                            "provider_label" to (inst?.let { ConfigValue.Str(it.label) } ?: ConfigValue.Null),
                                        )
                                    )
                                }
                            }
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(g.id),
                                    "name" to ConfigValue.Str(g.name),
                                    "strategy" to ConfigValue.Str(g.strategy.name),
                                    "fallback_strategy" to ConfigValue.Str(g.fallbackStrategy.name),
                                    // [fix/da1026-H] The two per-group session
                                    // defaults were write-only on this face:
                                    // `minis-config get groups` hid them, so an
                                    // agent could set them at add time and never
                                    // read them back. Same read shape as the
                                    // sibling providers summary (carrier field,
                                    // Null = "no override") — the `add` payload
                                    // accepts null/absent for both keys, so a
                                    // summary round-trips through add unchanged.
                                    // Token vocabulary matches the
                                    // `groups.<id>.defaultThinkingLevel` field
                                    // reader (lowercase, `thinkingLevelToToken`).
                                    "default_thinking_level" to (g.defaultThinkingLevel?.let {
                                        ConfigValue.Str(thinkingLevelToToken(it))
                                    } ?: ConfigValue.Null),
                                    "context_limit_tokens" to (g.contextLimitTokens?.let {
                                        ConfigValue.Int(it)
                                    } ?: ConfigValue.Null),
                                    "entries" to ConfigValue.Arr(entries),
                                )
                            )
                        }
                    )
                },
            )
        )
    }

    // -- Defaults — agent loop entries / groups, default primary/sub group --

    private fun registerDefaults(r: ConfigRegistry, repo: ProviderRepository) {
        r.register(
            ClosureField(
                path = "defaults.primaryGroup",
                displayName = "Default primary group",
                description = "Group used by new sessions. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(repo.defaultPrimaryGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.defaultPrimaryGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.defaultPrimaryGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.subGroup",
                displayName = "Default sub group",
                description = "Secondary group for fallback. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(repo.defaultSubGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.defaultSubGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.defaultSubGroupId = s
                    }
                },
            )
        )
        // [da1031-A] The two voice-group bindings are defaults.* references
        // exactly like primaryGroup/subGroup above. They were never
        // registered, so ConfigRegistry.exportJSON() — the source of the
        // backup's `fields` section — could not carry them and a restore
        // silently dropped both bindings.
        r.register(
            ClosureField(
                path = "defaults.voiceInputGroup",
                displayName = "Default voice input group",
                description = "Group that resolves the voice-INPUT model. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(repo.voiceInputGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.voiceInputGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.voiceInputGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.voiceOutputGroup",
                displayName = "Default voice output group",
                description = "Group that resolves the voice-OUTPUT model. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(repo.voiceOutputGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.voiceOutputGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.voiceOutputGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.agentLoopEntries",
                displayName = "Agent loop model entries",
                description = "Model entries available via minis-model-use. Replace with full list to set; use .append/.remove for single-element ops.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    // [T-android-agentloop-dirty-data-skip] Filter out ids that no
                    // longer match a real model entry (legacy bare-UUID / deleted
                    // rows). Surfacing them confuses minis-model-use and the
                    // round-trip set/append flow; skip silently.
                    val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                    ConfigValue.Arr(
                        repo.config.value.agentLoopModelEntryIds
                            .filter { it in valid }
                            .map { ConfigValue.Str(it) }
                    )
                },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                    // [T-android-agentloop-dirty-data-skip] Silent-skip unknown ids
                    // instead of hard-failing the whole write. `.append` reads the
                    // existing array (which may carry legacy bare-UUID dirt) and
                    // re-writes the full list; a hard fail rejected the valid new
                    // entry along with the historical dirt. Filtering cleans the
                    // dirt and lets the valid entries through; one diagnostic line
                    // per skipped id leaves a trace without blocking the op.
                    val cleanIds = ids.filter { it in valid }
                    ids.filterNot { it in valid }.forEach { bad ->
                        com.rikkaminis.app.logging.AppLogger.warning(
                            "MinisConfig", "skipped unknown agentLoopEntries id: $bad"
                        )
                    }
                    repo.setAgentLoopEntryIds(cleanIds)
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.agentLoopGroups",
                displayName = "Agent loop groups",
                description = "Whole groups exposed via minis-model-use.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    // [T-android-agentloop-dirty-data-skip] Skip ids that no
                    // longer match a real group (deleted / legacy bare UUID).
                    val valid = repo.config.value.modelGroups.map { it.id }.toSet()
                    ConfigValue.Arr(
                        repo.config.value.agentLoopGroupIds
                            .filter { it in valid }
                            .map { ConfigValue.Str(it) }
                    )
                },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val valid = repo.config.value.modelGroups.map { it.id }.toSet()
                    // [T-android-agentloop-dirty-data-skip] Silent-skip unknown
                    // group ids (same rationale as agentLoopEntries above).
                    val cleanIds = ids.filter { it in valid }
                    ids.filterNot { it in valid }.forEach { bad ->
                        com.rikkaminis.app.logging.AppLogger.warning(
                            "MinisConfig", "skipped unknown agentLoopGroups id: $bad"
                        )
                    }
                    repo.setAgentLoopGroupIds(cleanIds)
                },
            )
        )
    }

    // -- Soul (SOUL.md personality) --
    //
    // [T-soul-md-minisconfig] Exposes the editable subset of SOUL.md
    // (name / style / lang / body) as `soul.*` config paths. The on-disk
    // YAML emoji field is intentionally NOT registered — matches the
    // iOS rollback where ✨ is locked as the identity icon.
    //
    // Writes go through the standard PendingConfigChange / user-confirm
    // gate (registered with [ConfigRisk.SENSITIVE]); only the *approved*
    // writer touches disk. Each writer reads the current SOUL.md so
    // unrelated fields (incl. the on-disk `emoji` value that survives
    // for round-trip) are preserved when one field changes.

    private fun registerSoul(r: ConfigRegistry, context: Context) {
        // Length cap is language-aware now (Chinese ≤ 1600 chars OR
        // English ≤ 1000 words); see [com.rikkaminis.app.agent.SoulStore.isOverLimit].
        // No schema-level maxLength — a fixed char count would be wrong
        // for either language. The writer below rejects over-limit with a
        // precise [ConfigError.InvalidValue].

        // Re-read SOUL.md every call so concurrent writes from Settings
        // UI don't race with minis-config writes. Parse falls back to
        // the canonical default content if the file was somehow deleted
        // between launches — same behavior as the Settings page.
        fun loadCurrent(): com.rikkaminis.app.agent.SoulFile =
            com.rikkaminis.app.agent.SoulStore.load(context)
                ?: com.rikkaminis.app.agent.SoulMDParser.parse(
                    com.rikkaminis.app.agent.SoulStore.DEFAULT_CONTENT
                )

        fun saveCurrent(file: com.rikkaminis.app.agent.SoulFile) {
            com.rikkaminis.app.agent.SoulStore.save(context, file)
        }

        r.register(
            ClosureField(
                path = "soul.name",
                displayName = "Soul name",
                description = "The agent's name as shown in chat bubble headers and as the system-prompt identity. Trimmed; empty falls back to \"RikkaMinis\".",
                valueSchema = ConfigSchema.Str(maxLength = 64),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    val meta = loadCurrent().metadata
                    ConfigValue.Str(meta.name)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val name = raw.trim().ifBlank {
                        com.rikkaminis.app.agent.SoulMetadata.DEFAULT.name
                    }
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(name = name)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.style",
                displayName = "Soul style",
                description = "Short style descriptor used as a hint in the system prompt (e.g. \"Warm, direct, opinionated\").",
                valueSchema = ConfigSchema.Str(maxLength = 256),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    val meta = loadCurrent().metadata
                    ConfigValue.Str(meta.style)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val style = raw.trim()
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(style = style)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.lang",
                displayName = "Soul language",
                description = "Preferred response language: auto / zh / en. Older SOUL.md files missing the field are upgraded on first write.",
                valueSchema = ConfigSchema.StrEnum(listOf("auto", "zh", "en")),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    val cur = loadCurrent()
                    // Older files may be missing the lang frontmatter
                    // key — parser already substitutes DEFAULT.lang in
                    // that case, so we just round-trip it.
                    ConfigValue.Str(cur.metadata.lang.ifBlank {
                        com.rikkaminis.app.agent.SoulMetadata.DEFAULT.lang
                    })
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val lang = raw.trim()
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(lang = lang)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.body",
                displayName = "Soul personality prompt",
                description = "Personality / voice instructions injected as a block in the system prompt. Length cap is language-aware: Chinese ≤ ${com.rikkaminis.app.agent.SoulStore.CHINESE_CHAR_LIMIT} chars OR English ≤ ${com.rikkaminis.app.agent.SoulStore.ENGLISH_WORD_LIMIT} words (rule picked by the majority language). The writer also rejects prompt-injection patterns (\"ignore previous instructions\" etc.) — keep this to genuine character / tone guidance.",
                // No schema maxLength because either character or word
                // count is wrong for the *other* language. Both checks
                // (length + injection) run in the writer below.
                valueSchema = ConfigSchema.Str(),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    ConfigValue.Str(loadCurrent().body)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    if (com.rikkaminis.app.agent.SystemPromptBuilder
                            .containsInjectionPattern(raw)
                    ) {
                        throw ConfigError.InvalidValue(
                            "Body contains a prompt-injection pattern (\"ignore/disregard/forget … previous/prior instructions\"). SOUL.md is for personality, not instructions to the model."
                        )
                    }
                    val check = com.rikkaminis.app.agent.SoulStore.isOverLimit(raw)
                    when (check) {
                        is com.rikkaminis.app.agent.SoulBodyLimitCheck.Ok -> Unit
                        is com.rikkaminis.app.agent.SoulBodyLimitCheck.OverLimitChinese ->
                            throw ConfigError.InvalidValue(
                                "Over limit: ${check.chars} Chinese chars exceeds cap of ${check.cap}. Either trim to ≤ ${check.cap} chars, or rewrite mostly in English to use the ≤ ${com.rikkaminis.app.agent.SoulStore.ENGLISH_WORD_LIMIT}-word cap."
                            )
                        is com.rikkaminis.app.agent.SoulBodyLimitCheck.OverLimitEnglish ->
                            throw ConfigError.InvalidValue(
                                "Over limit: ${check.words} English words exceeds cap of ${check.cap}. Either trim to ≤ ${check.cap} words, or rewrite mostly in Chinese to use the ≤ ${com.rikkaminis.app.agent.SoulStore.CHINESE_CHAR_LIMIT}-char cap."
                            )
                    }
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(body = raw))
                },
            )
        )
    }
}
