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
        e.apply()
    }
}
