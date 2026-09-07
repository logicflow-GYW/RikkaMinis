package com.openminis.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [feat/runtime-limits-panel] JVM tests for the Runtime Limits prefs layer.
 *
 * The full Context-backed round-trip (prime/save/clamp against a
 * SharedPreferences stub) is verified in the sandbox closure before every
 * push (7/7 green there); in the repo build this file pins the
 * Context-free surface — the DEFAULT/bounds contract every consumer
 * (engine, worker, providers, ConfigBuiltins) compiles against, and the
 * retry-delay derivation that replaced the fixed intArrayOf(1, 2, 4).
 *
 * Why this matters: the defaults ARE the previously hard-coded constants.
 * If any default drifts, an untouched install silently changes behavior —
 * this test is the tripwire.
 */
class AgentRuntimeLimitsPrefsTest {

    @Test
    fun `defaults match the previously hard-coded constants`() {
        val p = AgentRuntimeLimitsPrefs
        // AgentLoopEngine / ChatAgentTraceObserver consts:
        assertEquals(200, p.TURNS_DEFAULT)             // MAX_AGENT_TURNS
        assertEquals(128, p.PROVIDER_ATTEMPTS_DEFAULT) // T7_OBSERVE_MAX_PROVIDER_ATTEMPTS
        assertEquals(128, p.TOOL_CALLS_DEFAULT)        // T7_OBSERVE_MAX_TOOL_CALLS
        assertEquals(128, p.SHELL_COMMANDS_DEFAULT)    // T7_OBSERVE_MAX_SHELL_COMMANDS
        assertEquals(8, p.COMPACTION_CALLS_DEFAULT)    // T7_OBSERVE_MAX_COMPACTION_CALLS
        assertEquals(4, p.CONCURRENT_TOOLS_DEFAULT)    // T7_OBSERVE_MAX_CONCURRENT_TOOLS
        assertEquals(60, p.DEADLINE_DEFAULT_MIN)       // T7_OBSERVE_DEADLINE_MS / 60s
        // Stream recovery consts:
        assertEquals(4, p.LENGTH_WALL_DEFAULT)         // MAX_LENGTH_WALL_TEXT_CONTINUES
        assertEquals(2, p.EOF_STUB_DEFAULT)            // MAX_EOF_STUB_CONTINUES
        assertEquals(2, p.DET_EMPTY_DEFAULT)           // DETERMINISTIC_EMPTY_LIMIT
        assertEquals(3, p.TRANSIENT_RETRIES_DEFAULT)   // AUTO_RETRY_DELAYS_SEC.size
        assertEquals(2, p.VERIFY_NUDGES_DEFAULT)       // VerificationStopPolicy.MAX_VERIFY_NUDGES
        // Network / worker consts:
        assertEquals(30, p.GENERATION_TIMEOUT_DEFAULT_MIN) // FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC
        assertEquals(30, p.FIRST_CHUNK_DIRECT_DEFAULT_SEC) // FirstChunkTimeoutPolicy.DIRECT_TIMEOUT_SEC
        assertEquals(45, p.FIRST_CHUNK_PROXY_DEFAULT_SEC)  // FirstChunkTimeoutPolicy.PROXY_TIMEOUT_SEC
        assertEquals(2, p.PROVIDER_SLOTS_DEFAULT)          // ProviderExecSlotPolicy.MAX_CONCURRENT_PROVIDER_RUNS
        assertEquals(6, p.QUEUE_ADMISSION_DEFAULT)         // ProviderExecSlotPolicy.MAX_QUEUED_REQUESTS
    }

    @Test
    fun `unprimed reads fall back to defaults`() {
        // Before MinisApp.onCreate primes the cache (e.g. a JVM unit test
        // path), every reader must see the documented defaults —
        // byte-identical to the pre-panel hard-coded behavior.
        val p = AgentRuntimeLimitsPrefs
        assertEquals(p.TURNS_DEFAULT, p.maxTurns())
        assertEquals(p.PROVIDER_ATTEMPTS_DEFAULT, p.maxProviderAttempts())
        assertEquals(p.TOOL_CALLS_DEFAULT, p.maxToolCalls())
        assertEquals(p.SHELL_COMMANDS_DEFAULT, p.maxShellCommands())
        assertEquals(p.COMPACTION_CALLS_DEFAULT, p.maxCompactionCalls())
        assertEquals(p.CONCURRENT_TOOLS_DEFAULT, p.maxConcurrentTools())
        assertEquals(p.DEADLINE_DEFAULT_MIN, p.runDeadlineMinutes())
        assertEquals(p.LENGTH_WALL_DEFAULT, p.lengthWallContinues())
        assertEquals(p.EOF_STUB_DEFAULT, p.eofStubContinues())
        assertEquals(p.DET_EMPTY_DEFAULT, p.deterministicEmptyLimit())
        assertEquals(p.TRANSIENT_RETRIES_DEFAULT, p.transientRetries())
        assertEquals(p.VERIFY_NUDGES_DEFAULT, p.verifyNudges())
        assertEquals(p.GENERATION_TIMEOUT_DEFAULT_MIN, p.generationTimeoutMinutes())
        assertEquals(p.FIRST_CHUNK_DIRECT_DEFAULT_SEC, p.firstChunkDirectSec())
        assertEquals(p.FIRST_CHUNK_PROXY_DEFAULT_SEC, p.firstChunkProxySec())
        assertEquals(p.PROVIDER_SLOTS_DEFAULT, p.providerSlots())
        assertEquals(p.QUEUE_ADMISSION_DEFAULT, p.queueAdmission())
    }

    @Test
    fun `bounds are sane`() {
        val p = AgentRuntimeLimitsPrefs
        // MIN <= DEFAULT <= MAX for every knob — a broken bound would make
        // the slider render upside-down or the clamp invert values.
        fun check(min: Int, def: Int, max: Int, name: String) {
            assert(min <= def && def <= max) { "$name bounds broken: $min/$def/$max" }
        }
        check(p.TURNS_MIN, p.TURNS_DEFAULT, p.TURNS_MAX, "turns")
        check(p.PROVIDER_ATTEMPTS_MIN, p.PROVIDER_ATTEMPTS_DEFAULT, p.PROVIDER_ATTEMPTS_MAX, "providerAttempts")
        check(p.TOOL_CALLS_MIN, p.TOOL_CALLS_DEFAULT, p.TOOL_CALLS_MAX, "toolCalls")
        check(p.SHELL_COMMANDS_MIN, p.SHELL_COMMANDS_DEFAULT, p.SHELL_COMMANDS_MAX, "shellCommands")
        check(p.COMPACTION_CALLS_MIN, p.COMPACTION_CALLS_DEFAULT, p.COMPACTION_CALLS_MAX, "compactionCalls")
        check(p.CONCURRENT_TOOLS_MIN, p.CONCURRENT_TOOLS_DEFAULT, p.CONCURRENT_TOOLS_MAX, "concurrentTools")
        check(p.DEADLINE_MIN_MIN, p.DEADLINE_DEFAULT_MIN, p.DEADLINE_MAX_MIN, "deadline")
        check(p.LENGTH_WALL_MIN, p.LENGTH_WALL_DEFAULT, p.LENGTH_WALL_MAX, "lengthWall")
        check(p.EOF_STUB_MIN, p.EOF_STUB_DEFAULT, p.EOF_STUB_MAX, "eofStub")
        check(p.DET_EMPTY_MIN, p.DET_EMPTY_DEFAULT, p.DET_EMPTY_MAX, "detEmpty")
        check(p.TRANSIENT_RETRIES_MIN, p.TRANSIENT_RETRIES_DEFAULT, p.TRANSIENT_RETRIES_MAX, "transientRetries")
        check(p.VERIFY_NUDGES_MIN, p.VERIFY_NUDGES_DEFAULT, p.VERIFY_NUDGES_MAX, "verifyNudges")
        check(p.GENERATION_TIMEOUT_MIN_MIN, p.GENERATION_TIMEOUT_DEFAULT_MIN, p.GENERATION_TIMEOUT_MAX_MIN, "generationTimeout")
        check(p.FIRST_CHUNK_DIRECT_MIN_SEC, p.FIRST_CHUNK_DIRECT_DEFAULT_SEC, p.FIRST_CHUNK_DIRECT_MAX_SEC, "firstChunkDirect")
        check(p.FIRST_CHUNK_PROXY_MIN_SEC, p.FIRST_CHUNK_PROXY_DEFAULT_SEC, p.FIRST_CHUNK_PROXY_MAX_SEC, "firstChunkProxy")
        check(p.PROVIDER_SLOTS_MIN, p.PROVIDER_SLOTS_DEFAULT, p.PROVIDER_SLOTS_MAX, "providerSlots")
        check(p.QUEUE_ADMISSION_MIN, p.QUEUE_ADMISSION_DEFAULT, p.QUEUE_ADMISSION_MAX, "queueAdmission")
    }

    @Test
    fun `retry delay derivation matches the legacy 1-2-4 cadence`() {
        // The fixed sequence was intArrayOf(1, 2, 4) for 3 retries. The
        // derivation is deterministic and pure (no state), so it can be
        // asserted WITHOUT priming: it only depends on cachedTransientRetries,
        // which defaults to 3 pre-prime → exactly the legacy array.
        assertArrayEquals(intArrayOf(1, 2, 4), AgentRuntimeLimitsPrefs.transientRetryDelaysSec())
    }
}
