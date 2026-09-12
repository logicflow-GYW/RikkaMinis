package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [feat/runtime-limits-panel] User-tunable agent runtime limits — the single
 * source of truth behind Settings → Agent Runtime → Runtime Limits.
 *
 * Everything here was previously a hard-coded `const val` scattered across
 * AgentLoopEngine / ChatAgentTraceObserver / ProviderExecSlotPolicy /
 * FirstChunkTimeoutPolicy / VerificationStopPolicy. The shipped values of
 * those constants become the DEFAULTs below, so an untouched install behaves
 * byte-identically to the pre-panel build — the panel only lets the user
 * deviate deliberately.
 *
 * Mirrors the ConcurrencyPrefs pattern: [prime] captures the application
 * context once at app startup (MinisApp.onCreate) and warms a volatile
 * cache; context-free readers (engine loop, worker service, offload
 * handlers) can then read without a Context. Writers update the cache AND
 * persist, so:
 *   - agent-loop scoped limits take effect on the NEXT message (the engine
 *     re-reads at runAgentLoop entry, not mid-run — a run keeps its budget);
 *   - worker-process scoped limits (provider slots, queue admission, first-
 *     chunk/generation timeouts) are read when the `:modelservice` worker
 *     process sizes its pool — i.e. when the NEXT worker process spawns
 *     (minutes-scale; the current worker finishes its in-flight runs on the
 *     old limits, which is the safe handoff).
 *
 * Every key is also registered in ConfigBuiltins (runtime.* paths) so
 * minis-config can read/write them and the in-app backup carries them.
 */
object AgentRuntimeLimitsPrefs {
    const val PREFS = "minis_runtime_limits_prefs"

    // ── Group 1: sessions & dispatch ─────────────────────────────────────
    // (maxConcurrentSessions lives in ConcurrencyPrefs — same panel, kept in
    //  its own file because three sandbox gates already read it. The
    //  subagent toggle lives in SubagentPrefs for the same reason. The panel
    //  reads/writes all three through one UI; the storage stays split so no
    //  existing consumer has to change.)

    // ── Group 2: agent-loop budget (per run, enforced) ───────────────────

    const val KEY_MAX_TURNS = "maxTurns"
    const val KEY_MAX_PROVIDER_ATTEMPTS = "maxProviderAttempts"
    const val KEY_MAX_TOOL_CALLS = "maxToolCalls"
    const val KEY_MAX_SHELL_COMMANDS = "maxShellCommands"
    const val KEY_MAX_COMPACTION_CALLS = "maxCompactionCalls"
    const val KEY_MAX_CONCURRENT_TOOLS = "maxConcurrentTools"
    const val KEY_RUN_DEADLINE_MIN = "runDeadlineMinutes"

    const val TURNS_MIN = 50
    const val TURNS_MAX = 1000
    const val TURNS_DEFAULT = 256

    const val PROVIDER_ATTEMPTS_MIN = 16
    const val PROVIDER_ATTEMPTS_MAX = 1000
    const val PROVIDER_ATTEMPTS_DEFAULT = 256

    const val TOOL_CALLS_MIN = 16
    const val TOOL_CALLS_MAX = 1000
    const val TOOL_CALLS_DEFAULT = 256

    const val SHELL_COMMANDS_MIN = 16
    const val SHELL_COMMANDS_MAX = 1000
    const val SHELL_COMMANDS_DEFAULT = 256

    const val COMPACTION_CALLS_MIN = 2
    const val COMPACTION_CALLS_MAX = 16
    const val COMPACTION_CALLS_DEFAULT = 8

    const val CONCURRENT_TOOLS_MIN = 1
    const val CONCURRENT_TOOLS_MAX = 8
    const val CONCURRENT_TOOLS_DEFAULT = 4

    const val DEADLINE_MIN_MIN = 15
    const val DEADLINE_MAX_MIN = 360 // 6h ceiling
    const val DEADLINE_DEFAULT_MIN = 120

    // ── Group 3: stream recovery / resilience (per run) ──────────────────

    const val KEY_LENGTH_WALL_CONTINUES = "lengthWallContinues"
    const val KEY_EOF_STUB_CONTINUES = "eofStubContinues"
    const val KEY_DETERMINISTIC_EMPTY_LIMIT = "deterministicEmptyLimit"
    const val KEY_TRANSIENT_RETRIES = "transientRetries"
    const val KEY_VERIFY_NUDGES = "verifyNudges"

    const val LENGTH_WALL_MIN = 0
    const val LENGTH_WALL_MAX = 8
    const val LENGTH_WALL_DEFAULT = 4

    const val EOF_STUB_MIN = 0
    const val EOF_STUB_MAX = 6
    const val EOF_STUB_DEFAULT = 2

    const val DET_EMPTY_MIN = 1
    const val DET_EMPTY_MAX = 5
    const val DET_EMPTY_DEFAULT = 2

    const val TRANSIENT_RETRIES_MIN = 0
    const val TRANSIENT_RETRIES_MAX = 5
    const val TRANSIENT_RETRIES_DEFAULT = 3

    const val VERIFY_NUDGES_MIN = 0
    const val VERIFY_NUDGES_MAX = 4
    // [fix/verify-nudges-default-off] Shipped default is 0 == the guard is
    // OFF out of the box: an unverified code edit closes the turn silently
    // (exactly the pre-guard behavior). Raising it opts into up to N bounded
    // turn-end reminders. Users who never touched the slider follow this
    // value (prime reads the default for an absent key); anyone who did gets
    // their stored value back.
    const val VERIFY_NUDGES_DEFAULT = 0

    // ── Group 4: worker network timeouts (worker-process scoped) ─────────

    const val KEY_GENERATION_TIMEOUT_MIN = "generationTimeoutMinutes"
    const val KEY_FIRST_CHUNK_DIRECT_SEC = "firstChunkDirectSec"
    const val KEY_FIRST_CHUNK_PROXY_SEC = "firstChunkProxySec"

    const val GENERATION_TIMEOUT_MIN_MIN = 10
    const val GENERATION_TIMEOUT_MAX_MIN = 60
    const val GENERATION_TIMEOUT_DEFAULT_MIN = 30

    const val FIRST_CHUNK_DIRECT_MIN_SEC = 10
    const val FIRST_CHUNK_DIRECT_MAX_SEC = 120
    const val FIRST_CHUNK_DIRECT_DEFAULT_SEC = 30

    const val FIRST_CHUNK_PROXY_MIN_SEC = 15
    const val FIRST_CHUNK_PROXY_MAX_SEC = 180
    const val FIRST_CHUNK_PROXY_DEFAULT_SEC = 45

    // ── Group 4b: worker slot policy (worker-process scoped) ─────────────

    const val KEY_PROVIDER_SLOTS = "providerSlots"
    const val KEY_QUEUE_ADMISSION = "queueAdmission"

    const val PROVIDER_SLOTS_MIN = 1
    const val PROVIDER_SLOTS_MAX = 4
    const val PROVIDER_SLOTS_DEFAULT = 2

    const val QUEUE_ADMISSION_MIN = 2
    const val QUEUE_ADMISSION_MAX = 12
    const val QUEUE_ADMISSION_DEFAULT = 6

    // ── Group 5: context & memory budget ─────────────────────────────────
    // [feat/chat-tuning-panel-b] Previously hard-coded in ContextCompactor /
    // MemoryRepository; the literals become the DEFAULTs below.

    const val KEY_AUTO_COMPACT_MIN_TAIL_TOKENS = "autoCompactMinTailTokens"
    const val KEY_AUTO_COMPACT_MIN_INTERVAL_MIN = "autoCompactMinIntervalMin"
    const val KEY_MEMORY_INJECT_LINES = "memoryInjectLines"
    const val KEY_MEMORY_ROLLUP_INJECT_KB = "memoryRollupInjectKb"
    const val KEY_MEMORY_SEARCH_LINES = "memorySearchLines"
    const val KEY_MEMORY_LOOKBACK_DAYS = "memoryLookbackDays"

    const val COMPACT_TAIL_TOKENS_MIN = 2000
    const val COMPACT_TAIL_TOKENS_MAX = 32000
    const val COMPACT_TAIL_TOKENS_DEFAULT = 8000

    const val COMPACT_INTERVAL_MIN_MIN = 1
    const val COMPACT_INTERVAL_MAX_MIN = 60
    const val COMPACT_INTERVAL_DEFAULT_MIN = 5

    const val MEMORY_INJECT_LINES_MIN = 50
    const val MEMORY_INJECT_LINES_MAX = 500
    const val MEMORY_INJECT_LINES_DEFAULT = 200

    const val MEMORY_ROLLUP_KB_MIN = 4
    const val MEMORY_ROLLUP_KB_MAX = 64
    const val MEMORY_ROLLUP_KB_DEFAULT = 12

    const val MEMORY_SEARCH_LINES_MIN = 20
    const val MEMORY_SEARCH_LINES_MAX = 200
    const val MEMORY_SEARCH_LINES_DEFAULT = 60

    const val MEMORY_LOOKBACK_DAYS_MIN = 7
    const val MEMORY_LOOKBACK_DAYS_MAX = 180
    const val MEMORY_LOOKBACK_DAYS_DEFAULT = 30

    // ── Group 6: media & tool budgets ────────────────────────────────────
    // [feat/chat-tuning-panel-b] Previously hard-coded in ImageBudget /
    // BrowserUseManager / PersistentShell; the literals become the DEFAULTs.

    const val KEY_IMAGE_MAX_PER_IMAGE_MB = "imageMaxPerImageMb"
    const val KEY_IMAGE_MAX_TOTAL_MB = "imageMaxTotalMb"
    const val KEY_IMAGE_MAX_REQUEST_MB = "imageMaxRequestMb"
    const val KEY_IMAGE_MAX_EDGE_PX = "imageMaxEdgePx"
    const val KEY_IMAGE_JPEG_QUALITY = "imageJpegQuality"

    const val IMAGE_PER_IMAGE_MB_MIN = 1
    const val IMAGE_PER_IMAGE_MB_MAX = 20
    const val IMAGE_PER_IMAGE_MB_DEFAULT = 5

    const val IMAGE_TOTAL_MB_MIN = 5
    const val IMAGE_TOTAL_MB_MAX = 100
    const val IMAGE_TOTAL_MB_DEFAULT = 25

    const val IMAGE_REQUEST_MB_MIN = 5
    const val IMAGE_REQUEST_MB_MAX = 100
    const val IMAGE_REQUEST_MB_DEFAULT = 25

    const val IMAGE_EDGE_MIN = 800
    const val IMAGE_EDGE_MAX = 4000
    const val IMAGE_EDGE_DEFAULT = 2000

    const val IMAGE_QUALITY_MIN = 40
    const val IMAGE_QUALITY_MAX = 100
    const val IMAGE_QUALITY_DEFAULT = 80

    const val KEY_BROWSER_NAV_TIMEOUT_SEC = "browserNavTimeoutSec"
    const val KEY_BROWSER_DOM_STABLE_SEC = "browserDomStableSec"
    const val KEY_BROWSER_SCREENSHOT_QUALITY = "browserScreenshotQuality"
    const val KEY_SHELL_OUTPUT_KB = "shellOutputKb"
    const val KEY_SHELL_TIMEOUT_SEC = "shellTimeoutSec"

    const val BROWSER_NAV_TIMEOUT_MIN_SEC = 10
    const val BROWSER_NAV_TIMEOUT_MAX_SEC = 120
    const val BROWSER_NAV_TIMEOUT_DEFAULT_SEC = 30

    const val BROWSER_DOM_STABLE_MIN_SEC = 1
    const val BROWSER_DOM_STABLE_MAX_SEC = 60
    const val BROWSER_DOM_STABLE_DEFAULT_SEC = 5

    const val BROWSER_SCREENSHOT_Q_MIN = 40
    const val BROWSER_SCREENSHOT_Q_MAX = 100
    const val BROWSER_SCREENSHOT_Q_DEFAULT = 80

    const val SHELL_OUTPUT_KB_MIN = 32
    const val SHELL_OUTPUT_KB_MAX = 512
    const val SHELL_OUTPUT_KB_DEFAULT = 128

    const val SHELL_TIMEOUT_MIN_SEC = 60
    const val SHELL_TIMEOUT_MAX_SEC = 1800
    // [fix/tuning-shell-timeout] 900 keeps the effective pre-panel default:
    // ChatShellExecution's `optInt("timeout", 900)` is what actually ran for
    // calls that omit their own timeout (PersistentShell's old 600_000 ms
    // default parameter was never reached — its only caller passes an
    // explicit value). The knob now feeds that optInt fallback directly.
    const val SHELL_TIMEOUT_DEFAULT_SEC = 900

    // ── primed cache ─────────────────────────────────────────────────────

    @Volatile private var cachedMaxTurns = TURNS_DEFAULT
    @Volatile private var cachedMaxProviderAttempts = PROVIDER_ATTEMPTS_DEFAULT
    @Volatile private var cachedMaxToolCalls = TOOL_CALLS_DEFAULT
    @Volatile private var cachedMaxShellCommands = SHELL_COMMANDS_DEFAULT
    @Volatile private var cachedMaxCompactionCalls = COMPACTION_CALLS_DEFAULT
    @Volatile private var cachedMaxConcurrentTools = CONCURRENT_TOOLS_DEFAULT
    @Volatile private var cachedDeadlineMin = DEADLINE_DEFAULT_MIN
    @Volatile private var cachedLengthWallContinues = LENGTH_WALL_DEFAULT
    @Volatile private var cachedEofStubContinues = EOF_STUB_DEFAULT
    @Volatile private var cachedDeterministicEmptyLimit = DET_EMPTY_DEFAULT
    @Volatile private var cachedTransientRetries = TRANSIENT_RETRIES_DEFAULT
    @Volatile private var cachedVerifyNudges = VERIFY_NUDGES_DEFAULT
    @Volatile private var cachedGenerationTimeoutMin = GENERATION_TIMEOUT_DEFAULT_MIN
    @Volatile private var cachedFirstChunkDirectSec = FIRST_CHUNK_DIRECT_DEFAULT_SEC
    @Volatile private var cachedFirstChunkProxySec = FIRST_CHUNK_PROXY_DEFAULT_SEC
    @Volatile private var cachedProviderSlots = PROVIDER_SLOTS_DEFAULT
    @Volatile private var cachedQueueAdmission = QUEUE_ADMISSION_DEFAULT

    // [feat/chat-tuning-panel-b] Group 5 + 6 caches.
    @Volatile private var cachedCompactTailTokens = COMPACT_TAIL_TOKENS_DEFAULT
    @Volatile private var cachedCompactIntervalMin = COMPACT_INTERVAL_DEFAULT_MIN
    @Volatile private var cachedMemoryInjectLines = MEMORY_INJECT_LINES_DEFAULT
    @Volatile private var cachedMemoryRollupKb = MEMORY_ROLLUP_KB_DEFAULT
    @Volatile private var cachedMemorySearchLines = MEMORY_SEARCH_LINES_DEFAULT
    @Volatile private var cachedMemoryLookbackDays = MEMORY_LOOKBACK_DAYS_DEFAULT
    @Volatile private var cachedImagePerImageMb = IMAGE_PER_IMAGE_MB_DEFAULT
    @Volatile private var cachedImageTotalMb = IMAGE_TOTAL_MB_DEFAULT
    @Volatile private var cachedImageRequestMb = IMAGE_REQUEST_MB_DEFAULT
    @Volatile private var cachedImageEdgePx = IMAGE_EDGE_DEFAULT
    @Volatile private var cachedImageJpegQuality = IMAGE_QUALITY_DEFAULT
    @Volatile private var cachedBrowserNavTimeoutSec = BROWSER_NAV_TIMEOUT_DEFAULT_SEC
    @Volatile private var cachedBrowserDomStableSec = BROWSER_DOM_STABLE_DEFAULT_SEC
    @Volatile private var cachedBrowserScreenshotQ = BROWSER_SCREENSHOT_Q_DEFAULT
    @Volatile private var cachedShellOutputKb = SHELL_OUTPUT_KB_DEFAULT
    @Volatile private var cachedShellTimeoutSec = SHELL_TIMEOUT_DEFAULT_SEC
    @Volatile private var primed = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm every cached limit. Call from MinisApp.onCreate. */
    fun prime(context: Context) {
        val p = prefs(context)
        cachedMaxTurns = p.getInt(KEY_MAX_TURNS, TURNS_DEFAULT).coerceIn(TURNS_MIN, TURNS_MAX)
        cachedMaxProviderAttempts = p.getInt(KEY_MAX_PROVIDER_ATTEMPTS, PROVIDER_ATTEMPTS_DEFAULT)
            .coerceIn(PROVIDER_ATTEMPTS_MIN, PROVIDER_ATTEMPTS_MAX)
        cachedMaxToolCalls = p.getInt(KEY_MAX_TOOL_CALLS, TOOL_CALLS_DEFAULT)
            .coerceIn(TOOL_CALLS_MIN, TOOL_CALLS_MAX)
        cachedMaxShellCommands = p.getInt(KEY_MAX_SHELL_COMMANDS, SHELL_COMMANDS_DEFAULT)
            .coerceIn(SHELL_COMMANDS_MIN, SHELL_COMMANDS_MAX)
        cachedMaxCompactionCalls = p.getInt(KEY_MAX_COMPACTION_CALLS, COMPACTION_CALLS_DEFAULT)
            .coerceIn(COMPACTION_CALLS_MIN, COMPACTION_CALLS_MAX)
        cachedMaxConcurrentTools = p.getInt(KEY_MAX_CONCURRENT_TOOLS, CONCURRENT_TOOLS_DEFAULT)
            .coerceIn(CONCURRENT_TOOLS_MIN, CONCURRENT_TOOLS_MAX)
        cachedDeadlineMin = p.getInt(KEY_RUN_DEADLINE_MIN, DEADLINE_DEFAULT_MIN)
            .coerceIn(DEADLINE_MIN_MIN, DEADLINE_MAX_MIN)
        cachedLengthWallContinues = p.getInt(KEY_LENGTH_WALL_CONTINUES, LENGTH_WALL_DEFAULT)
            .coerceIn(LENGTH_WALL_MIN, LENGTH_WALL_MAX)
        cachedEofStubContinues = p.getInt(KEY_EOF_STUB_CONTINUES, EOF_STUB_DEFAULT)
            .coerceIn(EOF_STUB_MIN, EOF_STUB_MAX)
        cachedDeterministicEmptyLimit = p.getInt(KEY_DETERMINISTIC_EMPTY_LIMIT, DET_EMPTY_DEFAULT)
            .coerceIn(DET_EMPTY_MIN, DET_EMPTY_MAX)
        cachedTransientRetries = p.getInt(KEY_TRANSIENT_RETRIES, TRANSIENT_RETRIES_DEFAULT)
            .coerceIn(TRANSIENT_RETRIES_MIN, TRANSIENT_RETRIES_MAX)
        cachedVerifyNudges = p.getInt(KEY_VERIFY_NUDGES, VERIFY_NUDGES_DEFAULT)
            .coerceIn(VERIFY_NUDGES_MIN, VERIFY_NUDGES_MAX)
        cachedGenerationTimeoutMin = p.getInt(KEY_GENERATION_TIMEOUT_MIN, GENERATION_TIMEOUT_DEFAULT_MIN)
            .coerceIn(GENERATION_TIMEOUT_MIN_MIN, GENERATION_TIMEOUT_MAX_MIN)
        cachedFirstChunkDirectSec = p.getInt(KEY_FIRST_CHUNK_DIRECT_SEC, FIRST_CHUNK_DIRECT_DEFAULT_SEC)
            .coerceIn(FIRST_CHUNK_DIRECT_MIN_SEC, FIRST_CHUNK_DIRECT_MAX_SEC)
        cachedFirstChunkProxySec = p.getInt(KEY_FIRST_CHUNK_PROXY_SEC, FIRST_CHUNK_PROXY_DEFAULT_SEC)
            .coerceIn(FIRST_CHUNK_PROXY_MIN_SEC, FIRST_CHUNK_PROXY_MAX_SEC)
        cachedProviderSlots = p.getInt(KEY_PROVIDER_SLOTS, PROVIDER_SLOTS_DEFAULT)
            .coerceIn(PROVIDER_SLOTS_MIN, PROVIDER_SLOTS_MAX)
        cachedQueueAdmission = p.getInt(KEY_QUEUE_ADMISSION, QUEUE_ADMISSION_DEFAULT)
            .coerceIn(QUEUE_ADMISSION_MIN, QUEUE_ADMISSION_MAX)
        // [feat/chat-tuning-panel-b] Group 5 + 6.
        cachedCompactTailTokens = p.getInt(KEY_AUTO_COMPACT_MIN_TAIL_TOKENS, COMPACT_TAIL_TOKENS_DEFAULT)
            .coerceIn(COMPACT_TAIL_TOKENS_MIN, COMPACT_TAIL_TOKENS_MAX)
        cachedCompactIntervalMin = p.getInt(KEY_AUTO_COMPACT_MIN_INTERVAL_MIN, COMPACT_INTERVAL_DEFAULT_MIN)
            .coerceIn(COMPACT_INTERVAL_MIN_MIN, COMPACT_INTERVAL_MAX_MIN)
        cachedMemoryInjectLines = p.getInt(KEY_MEMORY_INJECT_LINES, MEMORY_INJECT_LINES_DEFAULT)
            .coerceIn(MEMORY_INJECT_LINES_MIN, MEMORY_INJECT_LINES_MAX)
        cachedMemoryRollupKb = p.getInt(KEY_MEMORY_ROLLUP_INJECT_KB, MEMORY_ROLLUP_KB_DEFAULT)
            .coerceIn(MEMORY_ROLLUP_KB_MIN, MEMORY_ROLLUP_KB_MAX)
        cachedMemorySearchLines = p.getInt(KEY_MEMORY_SEARCH_LINES, MEMORY_SEARCH_LINES_DEFAULT)
            .coerceIn(MEMORY_SEARCH_LINES_MIN, MEMORY_SEARCH_LINES_MAX)
        cachedMemoryLookbackDays = p.getInt(KEY_MEMORY_LOOKBACK_DAYS, MEMORY_LOOKBACK_DAYS_DEFAULT)
            .coerceIn(MEMORY_LOOKBACK_DAYS_MIN, MEMORY_LOOKBACK_DAYS_MAX)
        cachedImagePerImageMb = p.getInt(KEY_IMAGE_MAX_PER_IMAGE_MB, IMAGE_PER_IMAGE_MB_DEFAULT)
            .coerceIn(IMAGE_PER_IMAGE_MB_MIN, IMAGE_PER_IMAGE_MB_MAX)
        cachedImageTotalMb = p.getInt(KEY_IMAGE_MAX_TOTAL_MB, IMAGE_TOTAL_MB_DEFAULT)
            .coerceIn(IMAGE_TOTAL_MB_MIN, IMAGE_TOTAL_MB_MAX)
        cachedImageRequestMb = p.getInt(KEY_IMAGE_MAX_REQUEST_MB, IMAGE_REQUEST_MB_DEFAULT)
            .coerceIn(IMAGE_REQUEST_MB_MIN, IMAGE_REQUEST_MB_MAX)
        cachedImageEdgePx = p.getInt(KEY_IMAGE_MAX_EDGE_PX, IMAGE_EDGE_DEFAULT)
            .coerceIn(IMAGE_EDGE_MIN, IMAGE_EDGE_MAX)
        cachedImageJpegQuality = p.getInt(KEY_IMAGE_JPEG_QUALITY, IMAGE_QUALITY_DEFAULT)
            .coerceIn(IMAGE_QUALITY_MIN, IMAGE_QUALITY_MAX)
        cachedBrowserNavTimeoutSec = p.getInt(KEY_BROWSER_NAV_TIMEOUT_SEC, BROWSER_NAV_TIMEOUT_DEFAULT_SEC)
            .coerceIn(BROWSER_NAV_TIMEOUT_MIN_SEC, BROWSER_NAV_TIMEOUT_MAX_SEC)
        cachedBrowserDomStableSec = p.getInt(KEY_BROWSER_DOM_STABLE_SEC, BROWSER_DOM_STABLE_DEFAULT_SEC)
            .coerceIn(BROWSER_DOM_STABLE_MIN_SEC, BROWSER_DOM_STABLE_MAX_SEC)
        cachedBrowserScreenshotQ = p.getInt(KEY_BROWSER_SCREENSHOT_QUALITY, BROWSER_SCREENSHOT_Q_DEFAULT)
            .coerceIn(BROWSER_SCREENSHOT_Q_MIN, BROWSER_SCREENSHOT_Q_MAX)
        cachedShellOutputKb = p.getInt(KEY_SHELL_OUTPUT_KB, SHELL_OUTPUT_KB_DEFAULT)
            .coerceIn(SHELL_OUTPUT_KB_MIN, SHELL_OUTPUT_KB_MAX)
        cachedShellTimeoutSec = p.getInt(KEY_SHELL_TIMEOUT_SEC, SHELL_TIMEOUT_DEFAULT_SEC)
            .coerceIn(SHELL_TIMEOUT_MIN_SEC, SHELL_TIMEOUT_MAX_SEC)
        primed = true
    }

    /** Exposed for tests: whether [prime] has run in this process. */
    fun isPrimed(): Boolean = primed

    // ── context-free readers (agent-loop scoped: re-read per run) ────────

    fun maxTurns(): Int = cachedMaxTurns
    fun maxProviderAttempts(): Int = cachedMaxProviderAttempts
    fun maxToolCalls(): Int = cachedMaxToolCalls
    fun maxShellCommands(): Int = cachedMaxShellCommands
    fun maxCompactionCalls(): Int = cachedMaxCompactionCalls
    fun maxConcurrentTools(): Int = cachedMaxConcurrentTools
    fun runDeadlineMinutes(): Int = cachedDeadlineMin
    fun lengthWallContinues(): Int = cachedLengthWallContinues
    fun eofStubContinues(): Int = cachedEofStubContinues
    fun deterministicEmptyLimit(): Int = cachedDeterministicEmptyLimit
    fun transientRetries(): Int = cachedTransientRetries
    fun verifyNudges(): Int = cachedVerifyNudges

    // ── context-free readers (worker-process scoped: read at pool sizing) ─

    fun generationTimeoutMinutes(): Int = cachedGenerationTimeoutMin
    fun firstChunkDirectSec(): Int = cachedFirstChunkDirectSec
    fun firstChunkProxySec(): Int = cachedFirstChunkProxySec
    fun providerSlots(): Int = cachedProviderSlots
    fun queueAdmission(): Int = cachedQueueAdmission

    // ── Group 5: context & memory budget [feat/chat-tuning-panel-b] ──────
    /** Auto-compaction keeps at least this many tail tokens uncompacted. */
    fun autoCompactMinTailTokens(): Int = cachedCompactTailTokens
    /** Minimum interval between auto-compactions, in minutes. */
    fun autoCompactMinIntervalMin(): Int = cachedCompactIntervalMin
    /** Max daily-log lines injected into the system prompt. */
    fun memoryInjectLines(): Int = cachedMemoryInjectLines
    /** Max rollup bytes injected into the system prompt, in KB. */
    fun memoryRollupInjectKb(): Int = cachedMemoryRollupKb
    /** Max matches returned by a single memory search. */
    fun memorySearchLines(): Int = cachedMemorySearchLines
    /** How far back memory search looks, in days. */
    fun memoryLookbackDays(): Int = cachedMemoryLookbackDays

    // ── Group 6: media & tool budgets [feat/chat-tuning-panel-b] ─────────
    /** Per-image byte ceiling before elision, in MB. */
    fun imageMaxPerImageMb(): Int = cachedImagePerImageMb
    /** Per-user-message image byte total, in MB. */
    fun imageMaxTotalMb(): Int = cachedImageTotalMb
    /** Whole-request image byte ceiling, in MB. */
    fun imageMaxRequestMb(): Int = cachedImageRequestMb
    /** Longest edge images are downscaled to, in px. */
    fun imageMaxEdgePx(): Int = cachedImageEdgePx
    /** JPEG re-encode quality for images. */
    fun imageJpegQuality(): Int = cachedImageJpegQuality
    /** Browser page-load timeout, in seconds. */
    fun browserNavTimeoutSec(): Int = cachedBrowserNavTimeoutSec
    /** Browser DOM-stable wait default, in seconds. */
    fun browserDomStableSec(): Int = cachedBrowserDomStableSec
    /** Browser screenshot JPEG quality. */
    fun browserScreenshotQuality(): Int = cachedBrowserScreenshotQ
    /** Shell output capture ceiling, in KB. */
    fun shellOutputKb(): Int = cachedShellOutputKb
    /** Default shell command timeout, in seconds. */
    fun shellTimeoutSec(): Int = cachedShellTimeoutSec

    /**
     * Transient auto-retry delays in seconds, derived from the retry count.
     * Historical fixed sequence was 1/2/4s for 3 retries; the derivation
     * keeps those exact values at the default count and extends the same
     * exponential-ish cadence to other counts (1, 2, 4, 8, 16).
     */
    fun transientRetryDelaysSec(): IntArray =
        IntArray(cachedTransientRetries) { i -> 1 shl i.coerceAtMost(4) }

    /**
     * Persist new values and warm the cache in one call. The dialog saves
     * every group in one shot; unknown/null fields are skipped.
     */
    fun save(
        context: Context,
        maxTurns: Int? = null,
        maxProviderAttempts: Int? = null,
        maxToolCalls: Int? = null,
        maxShellCommands: Int? = null,
        maxCompactionCalls: Int? = null,
        maxConcurrentTools: Int? = null,
        runDeadlineMinutes: Int? = null,
        lengthWallContinues: Int? = null,
        eofStubContinues: Int? = null,
        deterministicEmptyLimit: Int? = null,
        transientRetries: Int? = null,
        verifyNudges: Int? = null,
        generationTimeoutMinutes: Int? = null,
        firstChunkDirectSec: Int? = null,
        firstChunkProxySec: Int? = null,
        providerSlots: Int? = null,
        queueAdmission: Int? = null,
        // [feat/chat-tuning-panel-b] Group 5 + 6.
        autoCompactMinTailTokens: Int? = null,
        autoCompactMinIntervalMin: Int? = null,
        memoryInjectLines: Int? = null,
        memoryRollupInjectKb: Int? = null,
        memorySearchLines: Int? = null,
        memoryLookbackDays: Int? = null,
        imageMaxPerImageMb: Int? = null,
        imageMaxTotalMb: Int? = null,
        imageMaxRequestMb: Int? = null,
        imageMaxEdgePx: Int? = null,
        imageJpegQuality: Int? = null,
        browserNavTimeoutSec: Int? = null,
        browserDomStableSec: Int? = null,
        browserScreenshotQuality: Int? = null,
        shellOutputKb: Int? = null,
        shellTimeoutSec: Int? = null,
    ) {
        val p = prefs(context)
        val e = p.edit()
        maxTurns?.let { cachedMaxTurns = it.coerceIn(TURNS_MIN, TURNS_MAX); e.putInt(KEY_MAX_TURNS, cachedMaxTurns) }
        maxProviderAttempts?.let {
            cachedMaxProviderAttempts = it.coerceIn(PROVIDER_ATTEMPTS_MIN, PROVIDER_ATTEMPTS_MAX)
            e.putInt(KEY_MAX_PROVIDER_ATTEMPTS, cachedMaxProviderAttempts)
        }
        maxToolCalls?.let { cachedMaxToolCalls = it.coerceIn(TOOL_CALLS_MIN, TOOL_CALLS_MAX); e.putInt(KEY_MAX_TOOL_CALLS, cachedMaxToolCalls) }
        maxShellCommands?.let {
            cachedMaxShellCommands = it.coerceIn(SHELL_COMMANDS_MIN, SHELL_COMMANDS_MAX)
            e.putInt(KEY_MAX_SHELL_COMMANDS, cachedMaxShellCommands)
        }
        maxCompactionCalls?.let {
            cachedMaxCompactionCalls = it.coerceIn(COMPACTION_CALLS_MIN, COMPACTION_CALLS_MAX)
            e.putInt(KEY_MAX_COMPACTION_CALLS, cachedMaxCompactionCalls)
        }
        maxConcurrentTools?.let {
            cachedMaxConcurrentTools = it.coerceIn(CONCURRENT_TOOLS_MIN, CONCURRENT_TOOLS_MAX)
            e.putInt(KEY_MAX_CONCURRENT_TOOLS, cachedMaxConcurrentTools)
        }
        runDeadlineMinutes?.let {
            cachedDeadlineMin = it.coerceIn(DEADLINE_MIN_MIN, DEADLINE_MAX_MIN)
            e.putInt(KEY_RUN_DEADLINE_MIN, cachedDeadlineMin)
        }
        lengthWallContinues?.let {
            cachedLengthWallContinues = it.coerceIn(LENGTH_WALL_MIN, LENGTH_WALL_MAX)
            e.putInt(KEY_LENGTH_WALL_CONTINUES, cachedLengthWallContinues)
        }
        eofStubContinues?.let { cachedEofStubContinues = it.coerceIn(EOF_STUB_MIN, EOF_STUB_MAX); e.putInt(KEY_EOF_STUB_CONTINUES, cachedEofStubContinues) }
        deterministicEmptyLimit?.let {
            cachedDeterministicEmptyLimit = it.coerceIn(DET_EMPTY_MIN, DET_EMPTY_MAX)
            e.putInt(KEY_DETERMINISTIC_EMPTY_LIMIT, cachedDeterministicEmptyLimit)
        }
        transientRetries?.let {
            cachedTransientRetries = it.coerceIn(TRANSIENT_RETRIES_MIN, TRANSIENT_RETRIES_MAX)
            e.putInt(KEY_TRANSIENT_RETRIES, cachedTransientRetries)
        }
        verifyNudges?.let { cachedVerifyNudges = it.coerceIn(VERIFY_NUDGES_MIN, VERIFY_NUDGES_MAX); e.putInt(KEY_VERIFY_NUDGES, cachedVerifyNudges) }
        generationTimeoutMinutes?.let {
            cachedGenerationTimeoutMin = it.coerceIn(GENERATION_TIMEOUT_MIN_MIN, GENERATION_TIMEOUT_MAX_MIN)
            e.putInt(KEY_GENERATION_TIMEOUT_MIN, cachedGenerationTimeoutMin)
        }
        firstChunkDirectSec?.let {
            cachedFirstChunkDirectSec = it.coerceIn(FIRST_CHUNK_DIRECT_MIN_SEC, FIRST_CHUNK_DIRECT_MAX_SEC)
            e.putInt(KEY_FIRST_CHUNK_DIRECT_SEC, cachedFirstChunkDirectSec)
        }
        firstChunkProxySec?.let {
            cachedFirstChunkProxySec = it.coerceIn(FIRST_CHUNK_PROXY_MIN_SEC, FIRST_CHUNK_PROXY_MAX_SEC)
            e.putInt(KEY_FIRST_CHUNK_PROXY_SEC, cachedFirstChunkProxySec)
        }
        providerSlots?.let { cachedProviderSlots = it.coerceIn(PROVIDER_SLOTS_MIN, PROVIDER_SLOTS_MAX); e.putInt(KEY_PROVIDER_SLOTS, cachedProviderSlots) }
        queueAdmission?.let {
            cachedQueueAdmission = it.coerceIn(QUEUE_ADMISSION_MIN, QUEUE_ADMISSION_MAX)
            e.putInt(KEY_QUEUE_ADMISSION, cachedQueueAdmission)
        }
        // [feat/chat-tuning-panel-b] Group 5 + 6.
        autoCompactMinTailTokens?.let {
            cachedCompactTailTokens = it.coerceIn(COMPACT_TAIL_TOKENS_MIN, COMPACT_TAIL_TOKENS_MAX)
            e.putInt(KEY_AUTO_COMPACT_MIN_TAIL_TOKENS, cachedCompactTailTokens)
        }
        autoCompactMinIntervalMin?.let {
            cachedCompactIntervalMin = it.coerceIn(COMPACT_INTERVAL_MIN_MIN, COMPACT_INTERVAL_MAX_MIN)
            e.putInt(KEY_AUTO_COMPACT_MIN_INTERVAL_MIN, cachedCompactIntervalMin)
        }
        memoryInjectLines?.let {
            cachedMemoryInjectLines = it.coerceIn(MEMORY_INJECT_LINES_MIN, MEMORY_INJECT_LINES_MAX)
            e.putInt(KEY_MEMORY_INJECT_LINES, cachedMemoryInjectLines)
        }
        memoryRollupInjectKb?.let {
            cachedMemoryRollupKb = it.coerceIn(MEMORY_ROLLUP_KB_MIN, MEMORY_ROLLUP_KB_MAX)
            e.putInt(KEY_MEMORY_ROLLUP_INJECT_KB, cachedMemoryRollupKb)
        }
        memorySearchLines?.let {
            cachedMemorySearchLines = it.coerceIn(MEMORY_SEARCH_LINES_MIN, MEMORY_SEARCH_LINES_MAX)
            e.putInt(KEY_MEMORY_SEARCH_LINES, cachedMemorySearchLines)
        }
        memoryLookbackDays?.let {
            cachedMemoryLookbackDays = it.coerceIn(MEMORY_LOOKBACK_DAYS_MIN, MEMORY_LOOKBACK_DAYS_MAX)
            e.putInt(KEY_MEMORY_LOOKBACK_DAYS, cachedMemoryLookbackDays)
        }
        imageMaxPerImageMb?.let {
            cachedImagePerImageMb = it.coerceIn(IMAGE_PER_IMAGE_MB_MIN, IMAGE_PER_IMAGE_MB_MAX)
            e.putInt(KEY_IMAGE_MAX_PER_IMAGE_MB, cachedImagePerImageMb)
        }
        imageMaxTotalMb?.let {
            cachedImageTotalMb = it.coerceIn(IMAGE_TOTAL_MB_MIN, IMAGE_TOTAL_MB_MAX)
            e.putInt(KEY_IMAGE_MAX_TOTAL_MB, cachedImageTotalMb)
        }
        imageMaxRequestMb?.let {
            cachedImageRequestMb = it.coerceIn(IMAGE_REQUEST_MB_MIN, IMAGE_REQUEST_MB_MAX)
            e.putInt(KEY_IMAGE_MAX_REQUEST_MB, cachedImageRequestMb)
        }
        imageMaxEdgePx?.let {
            cachedImageEdgePx = it.coerceIn(IMAGE_EDGE_MIN, IMAGE_EDGE_MAX)
            e.putInt(KEY_IMAGE_MAX_EDGE_PX, cachedImageEdgePx)
        }
        imageJpegQuality?.let {
            cachedImageJpegQuality = it.coerceIn(IMAGE_QUALITY_MIN, IMAGE_QUALITY_MAX)
            e.putInt(KEY_IMAGE_JPEG_QUALITY, cachedImageJpegQuality)
        }
        browserNavTimeoutSec?.let {
            cachedBrowserNavTimeoutSec = it.coerceIn(BROWSER_NAV_TIMEOUT_MIN_SEC, BROWSER_NAV_TIMEOUT_MAX_SEC)
            e.putInt(KEY_BROWSER_NAV_TIMEOUT_SEC, cachedBrowserNavTimeoutSec)
        }
        browserDomStableSec?.let {
            cachedBrowserDomStableSec = it.coerceIn(BROWSER_DOM_STABLE_MIN_SEC, BROWSER_DOM_STABLE_MAX_SEC)
            e.putInt(KEY_BROWSER_DOM_STABLE_SEC, cachedBrowserDomStableSec)
        }
        browserScreenshotQuality?.let {
            cachedBrowserScreenshotQ = it.coerceIn(BROWSER_SCREENSHOT_Q_MIN, BROWSER_SCREENSHOT_Q_MAX)
            e.putInt(KEY_BROWSER_SCREENSHOT_QUALITY, cachedBrowserScreenshotQ)
        }
        shellOutputKb?.let {
            cachedShellOutputKb = it.coerceIn(SHELL_OUTPUT_KB_MIN, SHELL_OUTPUT_KB_MAX)
            e.putInt(KEY_SHELL_OUTPUT_KB, cachedShellOutputKb)
        }
        shellTimeoutSec?.let {
            cachedShellTimeoutSec = it.coerceIn(SHELL_TIMEOUT_MIN_SEC, SHELL_TIMEOUT_MAX_SEC)
            e.putInt(KEY_SHELL_TIMEOUT_SEC, cachedShellTimeoutSec)
        }
        e.apply()
    }
}
