package com.rikkaminis.app.data

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
        assertEquals(256, p.TURNS_DEFAULT)             // MAX_AGENT_TURNS
        assertEquals(256, p.PROVIDER_ATTEMPTS_DEFAULT) // T7_OBSERVE_MAX_PROVIDER_ATTEMPTS
        assertEquals(256, p.TOOL_CALLS_DEFAULT)        // T7_OBSERVE_MAX_TOOL_CALLS
        assertEquals(256, p.SHELL_COMMANDS_DEFAULT)    // T7_OBSERVE_MAX_SHELL_COMMANDS
        assertEquals(8, p.COMPACTION_CALLS_DEFAULT)    // T7_OBSERVE_MAX_COMPACTION_CALLS
        assertEquals(4, p.CONCURRENT_TOOLS_DEFAULT)    // T7_OBSERVE_MAX_CONCURRENT_TOOLS
        assertEquals(120, p.DEADLINE_DEFAULT_MIN)      // T7_OBSERVE_DEADLINE_MS / 120min
        // Stream recovery consts:
        assertEquals(4, p.LENGTH_WALL_DEFAULT)         // MAX_LENGTH_WALL_TEXT_CONTINUES
        assertEquals(2, p.EOF_STUB_DEFAULT)            // MAX_EOF_STUB_CONTINUES
        assertEquals(2, p.DET_EMPTY_DEFAULT)           // DETERMINISTIC_EMPTY_LIMIT
        assertEquals(3, p.TRANSIENT_RETRIES_DEFAULT)   // AUTO_RETRY_DELAYS_SEC.size
        // [fix/verify-nudges-default-off] No upstream mirror any more: the
        // guard ships OFF (0), and the old VerificationStopPolicy.MAX_VERIFY_NUDGES
        // const was deleted precisely so the default can't re-couple to a
        // policy constant. The cap semantics live in VerificationStopPolicyTest.
        assertEquals(0, p.VERIFY_NUDGES_DEFAULT)
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
        // [feat/chat-tuning-panel-b] Group 5 + 6.
        check(p.COMPACT_TAIL_TOKENS_MIN, p.COMPACT_TAIL_TOKENS_DEFAULT, p.COMPACT_TAIL_TOKENS_MAX, "compactTailTokens")
        check(p.COMPACT_INTERVAL_MIN_MIN, p.COMPACT_INTERVAL_DEFAULT_MIN, p.COMPACT_INTERVAL_MAX_MIN, "compactInterval")
        check(p.MEMORY_INJECT_LINES_MIN, p.MEMORY_INJECT_LINES_DEFAULT, p.MEMORY_INJECT_LINES_MAX, "memoryInjectLines")
        check(p.MEMORY_ROLLUP_KB_MIN, p.MEMORY_ROLLUP_KB_DEFAULT, p.MEMORY_ROLLUP_KB_MAX, "memoryRollupKb")
        check(p.MEMORY_SEARCH_LINES_MIN, p.MEMORY_SEARCH_LINES_DEFAULT, p.MEMORY_SEARCH_LINES_MAX, "memorySearchLines")
        check(p.MEMORY_LOOKBACK_DAYS_MIN, p.MEMORY_LOOKBACK_DAYS_DEFAULT, p.MEMORY_LOOKBACK_DAYS_MAX, "memoryLookbackDays")
        check(p.IMAGE_PER_IMAGE_MB_MIN, p.IMAGE_PER_IMAGE_MB_DEFAULT, p.IMAGE_PER_IMAGE_MB_MAX, "imagePerImageMb")
        check(p.IMAGE_TOTAL_MB_MIN, p.IMAGE_TOTAL_MB_DEFAULT, p.IMAGE_TOTAL_MB_MAX, "imageTotalMb")
        check(p.IMAGE_REQUEST_MB_MIN, p.IMAGE_REQUEST_MB_DEFAULT, p.IMAGE_REQUEST_MB_MAX, "imageRequestMb")
        check(p.IMAGE_EDGE_MIN, p.IMAGE_EDGE_DEFAULT, p.IMAGE_EDGE_MAX, "imageEdge")
        check(p.IMAGE_QUALITY_MIN, p.IMAGE_QUALITY_DEFAULT, p.IMAGE_QUALITY_MAX, "imageQuality")
        check(p.BROWSER_NAV_TIMEOUT_MIN_SEC, p.BROWSER_NAV_TIMEOUT_DEFAULT_SEC, p.BROWSER_NAV_TIMEOUT_MAX_SEC, "browserNavTimeout")
        check(p.BROWSER_DOM_STABLE_MIN_SEC, p.BROWSER_DOM_STABLE_DEFAULT_SEC, p.BROWSER_DOM_STABLE_MAX_SEC, "browserDomStable")
        check(p.BROWSER_SCREENSHOT_Q_MIN, p.BROWSER_SCREENSHOT_Q_DEFAULT, p.BROWSER_SCREENSHOT_Q_MAX, "browserScreenshotQ")
        check(p.SHELL_OUTPUT_KB_MIN, p.SHELL_OUTPUT_KB_DEFAULT, p.SHELL_OUTPUT_KB_MAX, "shellOutputKb")
        check(p.SHELL_TIMEOUT_MIN_SEC, p.SHELL_TIMEOUT_DEFAULT_SEC, p.SHELL_TIMEOUT_MAX_SEC, "shellTimeout")
    }

    @Test
    fun `retry delay derivation matches the legacy 1-2-4 cadence`() {
        // The fixed sequence was intArrayOf(1, 2, 4) for 3 retries. The
        // derivation is deterministic and pure (no state), so it can be
        // asserted WITHOUT priming: it only depends on cachedTransientRetries,
        // which defaults to 3 pre-prime → exactly the legacy array.
        assertArrayEquals(intArrayOf(1, 2, 4), AgentRuntimeLimitsPrefs.transientRetryDelaysSec())
    }

    @Test
    fun `group 5 + 6 defaults match the pre-panel literals`() {
        // [feat/chat-tuning-panel-b] Tripwire: each DEFAULT is the exact
        // literal the consumer previously hard-coded, so an untouched install
        // behaves byte-identically. If a consumer is retuned, change the
        // DEFAULT in AgentRuntimeLimitsPrefs in the same commit — this test
        // failing is the reminder.
        val p = AgentRuntimeLimitsPrefs
        assertEquals(8_000, p.COMPACT_TAIL_TOKENS_DEFAULT)       // ContextCompactor.DEFAULT_AUTO_COMPACT_MIN_TAIL_TOKENS
        assertEquals(5, p.COMPACT_INTERVAL_DEFAULT_MIN)          // ContextCompactor.DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS ÷ 60_000
        assertEquals(200, p.MEMORY_INJECT_LINES_DEFAULT)         // MemoryRepository.MAX_INJECT_LINES
        assertEquals(12, p.MEMORY_ROLLUP_KB_DEFAULT)             // MemoryRepository.MAX_ROLLUP_INJECT_BYTES ÷ 1024
        assertEquals(60, p.MEMORY_SEARCH_LINES_DEFAULT)          // MemoryRepository.MAX_SEARCH_LINES
        assertEquals(30, p.MEMORY_LOOKBACK_DAYS_DEFAULT)         // MemoryRepository.MAX_LOOKBACK_DAYS
        assertEquals(5, p.IMAGE_PER_IMAGE_MB_DEFAULT)            // ImageBudget.MAX_PER_IMAGE_BYTES ÷ 1 MiB
        assertEquals(25, p.IMAGE_TOTAL_MB_DEFAULT)               // ImageBudget.MAX_TOTAL_BYTES ÷ 1 MiB
        assertEquals(25, p.IMAGE_REQUEST_MB_DEFAULT)             // ImageBudget.MAX_REQUEST_BYTES ÷ 1 MiB
        assertEquals(2000, p.IMAGE_EDGE_DEFAULT)                 // ImageBudget.MAX_EDGE_PX
        assertEquals(80, p.IMAGE_QUALITY_DEFAULT)                // ImageBudget.JPEG_QUALITY
        assertEquals(30, p.BROWSER_NAV_TIMEOUT_DEFAULT_SEC)      // BrowserUseManager.NAVIGATION_TIMEOUT_MS ÷ 1000
        assertEquals(5, p.BROWSER_DOM_STABLE_DEFAULT_SEC)        // BrowserUseManager.DEFAULT_DOM_STABLE_TIMEOUT_MS ÷ 1000
        assertEquals(80, p.BROWSER_SCREENSHOT_Q_DEFAULT)         // BrowserUseManager.SCREENSHOT_QUALITY
        assertEquals(128, p.SHELL_OUTPUT_KB_DEFAULT)             // PersistentShell.MAX_OUTPUT_CHARS ÷ 1024
        assertEquals(900, p.SHELL_TIMEOUT_DEFAULT_SEC)           // ChatShellExecution effective default (optInt fallback)
    }

    @Test
    fun `group 5 + 6 unprimed reads fall back to defaults`() {
        // Same contract as the first batch: every context-free reader must
        // return its DEFAULT when prime() has not run in this process (JVM
        // tests never prime). This is what makes the readers safe for
        // engine/worker code that may wake before/without MinisApp.onCreate.
        val p = AgentRuntimeLimitsPrefs
        assertEquals(p.COMPACT_TAIL_TOKENS_DEFAULT, p.autoCompactMinTailTokens())
        assertEquals(p.COMPACT_INTERVAL_DEFAULT_MIN, p.autoCompactMinIntervalMin())
        assertEquals(p.MEMORY_INJECT_LINES_DEFAULT, p.memoryInjectLines())
        assertEquals(p.MEMORY_ROLLUP_KB_DEFAULT, p.memoryRollupInjectKb())
        assertEquals(p.MEMORY_SEARCH_LINES_DEFAULT, p.memorySearchLines())
        assertEquals(p.MEMORY_LOOKBACK_DAYS_DEFAULT, p.memoryLookbackDays())
        assertEquals(p.IMAGE_PER_IMAGE_MB_DEFAULT, p.imageMaxPerImageMb())
        assertEquals(p.IMAGE_TOTAL_MB_DEFAULT, p.imageMaxTotalMb())
        assertEquals(p.IMAGE_REQUEST_MB_DEFAULT, p.imageMaxRequestMb())
        assertEquals(p.IMAGE_EDGE_DEFAULT, p.imageMaxEdgePx())
        assertEquals(p.IMAGE_QUALITY_DEFAULT, p.imageJpegQuality())
        assertEquals(p.BROWSER_NAV_TIMEOUT_DEFAULT_SEC, p.browserNavTimeoutSec())
        assertEquals(p.BROWSER_DOM_STABLE_DEFAULT_SEC, p.browserDomStableSec())
        assertEquals(p.BROWSER_SCREENSHOT_Q_DEFAULT, p.browserScreenshotQuality())
        assertEquals(p.SHELL_OUTPUT_KB_DEFAULT, p.shellOutputKb())
        assertEquals(p.SHELL_TIMEOUT_DEFAULT_SEC, p.shellTimeoutSec())
    }
}
