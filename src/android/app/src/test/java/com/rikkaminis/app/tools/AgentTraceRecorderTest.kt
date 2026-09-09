package com.rikkaminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T6: unit tests for [AgentTraceRecorder] — 1.0 事件兼容 + 2.0 预算/终态/资源
 * 证据事件、terminal 去重、写失败保护与并发行完整性。纯 JVM。
 */
class AgentTraceRecorderTest {

    private class Sink {
        val lines = mutableListOf<String>()
        val raw: String get() = lines.joinToString("\n")
    }

    private fun recorder(sink: Sink, clock: () -> Long = { 0L }) =
        AgentTraceRecorder(appendLine = { sink.lines.add(it) }, clock = clock)

    // ══════════════════ 1.0 既有行为（回归） ══════════════════

    @Test
    fun `traceStart writes type session provider and prompt`() {
        val sink = Sink()
        val r = recorder(sink)
        r.traceStart("sess-1", "OpenAIProvider", "hello agent")
        val e = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_TRACE_START, e.getString("type"))
        assertEquals("sess-1", e.getString("session"))
        assertEquals("OpenAIProvider", e.getString("provider"))
        assertEquals("hello agent", e.getString("prompt"))
        assertEquals(0L, e.getLong("ts"))
    }

    @Test
    fun `traceStart truncates long prompt`() {
        val sink = Sink()
        val r = recorder(sink)
        val longPrompt = "x".repeat(AgentTraceRecorder.PROMPT_MAX_LENGTH + 100)
        r.traceStart("s", "P", longPrompt)
        val e = JSONObject(sink.lines[0])
        val stored = e.getString("prompt")
        assertTrue(stored.length <= AgentTraceRecorder.PROMPT_MAX_LENGTH + 1) // + ellipsis
        assertTrue(stored.endsWith("…"))
    }

    @Test
    fun `turnStart and turnEnd carry turn tokens and finish reason`() {
        val sink = Sink()
        val r = recorder(sink)
        r.turnStart(3)
        r.turnEnd(3, tokensIn = 120, tokensOut = 45, finishReason = "stop", durationMs = 500L)
        val start = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_TURN_START, start.getString("type"))
        assertEquals(3, start.getInt("turn"))
        val end = JSONObject(sink.lines[1])
        assertEquals(AgentTraceRecorder.TYPE_TURN_END, end.getString("type"))
        assertEquals(3, end.getInt("turn"))
        assertEquals(120, end.getInt("tokens_in"))
        assertEquals(45, end.getInt("tokens_out"))
        assertEquals("stop", end.getString("finish_reason"))
        assertEquals(500L, end.getLong("duration_ms"))
    }

    @Test
    fun `turnEnd omits absent token fields`() {
        val sink = Sink()
        val r = recorder(sink)
        r.turnEnd(0, tokensIn = null, tokensOut = null, finishReason = null, durationMs = 10L)
        val e = JSONObject(sink.lines[0])
        assertFalse(e.has("tokens_in"))
        assertFalse(e.has("tokens_out"))
        assertFalse(e.has("finish_reason"))
        assertEquals(10L, e.getLong("duration_ms"))
    }

    @Test
    fun `toolCall and toolResult round trip with ids and duration`() {
        val sink = Sink()
        val r = recorder(sink)
        r.toolCall(1, "call_abc", "file_read", """{"path":"/etc/hosts"}""")
        r.toolResult(1, "call_abc", "file_read", success = true, output = "127.0.0.1 localhost", durationMs = 42L)
        val call = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_TOOL_CALL, call.getString("type"))
        assertEquals("call_abc", call.getString("tool_id"))
        assertEquals("file_read", call.getString("tool"))
        assertEquals(1, call.getInt("turn"))
        assertTrue(call.getString("args").contains("/etc/hosts"))
        val result = JSONObject(sink.lines[1])
        assertEquals(AgentTraceRecorder.TYPE_TOOL_RESULT, result.getString("type"))
        assertTrue(result.getBoolean("success"))
        assertEquals(42L, result.getLong("duration_ms"))
        assertTrue(result.getString("output").contains("localhost"))
    }

    @Test
    fun `toolResult with failure records success false`() {
        val sink = Sink()
        val r = recorder(sink)
        r.toolResult(0, "id", "shell_execute", success = false, output = "command not found", durationMs = 7L)
        val e = JSONObject(sink.lines[0])
        assertFalse(e.getBoolean("success"))
    }

    @Test
    fun `args and output are truncated to caps`() {
        val sink = Sink()
        val r = recorder(sink)
        val longArgs = "a".repeat(AgentTraceRecorder.ARGS_MAX_LENGTH + 200)
        val longOutput = "b".repeat(AgentTraceRecorder.OUTPUT_MAX_LENGTH + 200)
        r.toolCall(0, "id", "shell_execute", longArgs)
        r.toolResult(0, "id", "shell_execute", success = true, output = longOutput, durationMs = 1L)
        val call = JSONObject(sink.lines[0])
        assertTrue(call.getString("args").length <= AgentTraceRecorder.ARGS_MAX_LENGTH + 1)
        val result = JSONObject(sink.lines[1])
        assertTrue(result.getString("output").length <= AgentTraceRecorder.OUTPUT_MAX_LENGTH + 1)
    }

    @Test
    fun `error and traceEnd write expected fields`() {
        val sink = Sink()
        val r = recorder(sink)
        r.error(turn = 2, phase = "exception", message = "boom")
        r.traceEnd(normalExit = false, turnCount = 3, durationMs = 9000L, error = "boom")
        val err = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_ERROR, err.getString("type"))
        assertEquals(2, err.getInt("turn"))
        assertEquals("exception", err.getString("phase"))
        val end = JSONObject(sink.lines[1])
        assertEquals(AgentTraceRecorder.TYPE_TRACE_END, end.getString("type"))
        assertFalse(end.getBoolean("normal_exit"))
        assertEquals(3, end.getInt("turns"))
        assertEquals(9000L, end.getLong("duration_ms"))
        assertEquals("boom", end.getString("error"))
    }

    @Test
    fun `clock is used for every event timestamp`() {
        var now = 111L
        val sink = Sink()
        val r = recorder(sink, clock = { now })
        r.turnStart(0)
        now = 222L
        r.turnStart(1)
        assertEquals(111L, JSONObject(sink.lines[0]).getLong("ts"))
        assertEquals(222L, JSONObject(sink.lines[1]).getLong("ts"))
    }

    // ══════════════════ parse / query helpers（回归） ══════════════════

    @Test
    fun `parse returns events in order and skips malformed lines`() {
        val sink = Sink()
        val r = recorder(sink)
        r.turnStart(0)
        r.turnStart(1)
        val malformed = sink.lines.joinToString("\n") + "\nnot-json{{{"
        val events = AgentTraceRecorder.parse(malformed)
        assertEquals(2, events.size)
        assertEquals(0, events[0].getInt("turn"))
        assertEquals(1, events[1].getInt("turn"))
    }

    @Test
    fun `parse handles blank input`() {
        assertTrue(AgentTraceRecorder.parse("").isEmpty())
        assertTrue(AgentTraceRecorder.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun `filterByTool matches only the named tool events`() {
        val sink = Sink()
        val r = recorder(sink)
        r.toolCall(0, "a", "file_read", "{}")
        r.toolResult(0, "a", "file_read", true, "data", 1L)
        r.toolCall(0, "b", "shell_execute", "{}")
        r.toolResult(0, "b", "shell_execute", true, "out", 1L)
        val events = AgentTraceRecorder.parse(sink.raw)
        val fileRead = AgentTraceRecorder.filterByTool(events, "file_read")
        assertEquals(2, fileRead.size)
        assertTrue(fileRead.all { it.getString("tool") == "file_read" })
        assertEquals(0, AgentTraceRecorder.filterByTool(events, "nope").size)
    }

    @Test
    fun `filterErrors finds failed tools and explicit errors only`() {
        val sink = Sink()
        val r = recorder(sink)
        r.toolCall(0, "a", "file_read", "{}")
        r.toolResult(0, "a", "file_read", true, "data", 1L)
        r.toolResult(1, "b", "shell_execute", false, "boom", 1L)
        r.error(1, "exception", "kaboom")
        r.traceEnd(true, 2, 5L, null)
        val events = AgentTraceRecorder.parse(sink.raw)
        val errors = AgentTraceRecorder.filterErrors(events)
        assertEquals(2, errors.size)
        assertEquals(AgentTraceRecorder.TYPE_TOOL_RESULT, errors[0].getString("type"))
        assertFalse(errors[0].getBoolean("success"))
        assertEquals(AgentTraceRecorder.TYPE_ERROR, errors[1].getString("type"))
    }

    @Test
    fun `renderHumanReadable contains the key timeline facts`() {
        val sink = Sink()
        val r = recorder(sink)
        r.traceStart("sess-9", "OpenAIProvider", "summarize this")
        r.turnStart(0)
        r.toolCall(0, "id1", "file_read", """{"path":"/tmp/a.txt"}""")
        r.toolResult(0, "id1", "file_read", true, "hello world", 12L)
        r.turnEnd(0, 100, 25, "stop", 300L)
        r.traceEnd(true, 1, 300L, null)
        val text = AgentTraceRecorder.renderHumanReadable(AgentTraceRecorder.parse(sink.raw))
        assertTrue(text.contains("sess-9"))
        assertTrue(text.contains("OpenAIProvider"))
        assertTrue(text.contains("summarize this"))
        assertTrue(text.contains("turn 0"))
        assertTrue(text.contains("file_read"))
        assertTrue(text.contains("OK"))
        assertTrue(text.contains("12ms"))
        assertTrue(text.contains("in=100"))
        assertTrue(text.contains("out=25"))
        assertTrue(text.contains("exit: normal"))
        assertTrue(text.contains("terminal: Succeeded"))
    }

    // ══════════════════ 2.0 run context ══════════════════

    @Test
    fun `beginRun writes schema version run id session id and budget`() {
        val sink = Sink()
        val r = recorder(sink)
        r.beginRun(
            runId = "r-1",
            sessionId = "s-1",
            provider = "OpenAIProvider",
            prompt = "do a thing",
            providerCount = 2,
            toolCount = 5,
            initialBudgetJson = """{"max_turns":200,"max_provider_attempts":10}""",
        )
        val e = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_TRACE_START, e.getString("type"))
        assertEquals(AgentTraceRecorder.SCHEMA_VERSION_2, e.getString("trace_schema_version"))
        assertEquals("r-1", e.getString("run_id"))
        assertEquals("s-1", e.getString("session_id"))
        assertEquals(2, e.getInt("provider_count"))
        assertEquals(5, e.getInt("tool_count"))
        val budget = e.getJSONObject("initial_budget")
        assertEquals(200, budget.getInt("max_turns"))
        assertEquals(10, budget.getInt("max_provider_attempts"))
        // 1.0 遗留字段保持兼容
        assertEquals("s-1", e.getString("session"))
        assertEquals("OpenAIProvider", e.getString("provider"))
    }

    @Test
    fun `beginRun truncates prompt preview in both fields`() {
        val sink = Sink()
        val r = recorder(sink)
        val longPrompt = "y".repeat(AgentTraceRecorder.PROMPT_MAX_LENGTH + 50)
        r.beginRun("r", "s", "P", longPrompt)
        val e = JSONObject(sink.lines[0])
        assertTrue(e.getString("prompt_preview").length <= AgentTraceRecorder.PROMPT_MAX_LENGTH + 1)
        assertTrue(e.getString("prompt").length <= AgentTraceRecorder.PROMPT_MAX_LENGTH + 1)
        assertFalse(e.getString("prompt_preview").contains("y".repeat(AgentTraceRecorder.PROMPT_MAX_LENGTH + 1)))
    }

    @Test
    fun `events after beginRun carry run context`() {
        val sink = Sink()
        val r = recorder(sink)
        r.beginRun("r-9", "s-9", "P", "hi")
        r.turnStart(0)
        r.toolCall(0, "id", "shell_execute", "{}")
        val start = JSONObject(sink.lines[1])
        assertEquals("r-9", start.getString("run_id"))
        assertEquals("s-9", start.getString("session_id"))
        val call = JSONObject(sink.lines[2])
        assertEquals("r-9", call.getString("run_id"))
        assertEquals("s-9", call.getString("session_id"))
    }

    @Test
    fun `legacyOnePointZero traceStart does not carry run context`() {
        val sink = Sink()
        val r = recorder(sink)
        r.traceStart("sess-a", "P", "hi")
        val e = JSONObject(sink.lines[0])
        assertFalse(e.has("run_id"))
        assertFalse(e.has("trace_schema_version"))
    }

    // ══════════════════ 2.0 新事件 ══════════════════

    @Test
    fun `stateTransition writes from to and reason`() {
        val sink = Sink()
        val r = recorder(sink)
        r.stateTransition("Idle", "Preparing", "run_started")
        val e = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_STATE_TRANSITION, e.getString("type"))
        assertEquals("Idle", e.getString("from"))
        assertEquals("Preparing", e.getString("to"))
        assertEquals("run_started", e.getString("reason"))
    }

    @Test
    fun `budgetConsume writes dimension consumed remaining total and retry flags`() {
        val sink = Sink()
        val r = recorder(sink)
        r.budgetConsume(
            dimension = AgentTraceRecorder.DIMENSION_PROVIDER_ATTEMPTS,
            consumed = 1,
            remaining = 4,
            total = 5,
            isRetry = true,
            isFallback = false,
        )
        val e = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_BUDGET_CONSUME, e.getString("type"))
        assertEquals(AgentTraceRecorder.DIMENSION_PROVIDER_ATTEMPTS, e.getString("dimension"))
        assertEquals(1, e.getInt("consumed"))
        assertEquals(4, e.getInt("remaining"))
        assertEquals(5, e.getInt("total"))
        assertTrue(e.getBoolean("is_retry"))
        assertFalse(e.getBoolean("is_fallback"))
    }

    @Test
    fun `budgetRefuse writes requested remaining and reason`() {
        val sink = Sink()
        val r = recorder(sink)
        r.budgetRefuse(
            dimension = AgentTraceRecorder.DIMENSION_TOOL_CALLS,
            requested = 1,
            remaining = 0,
            reason = AgentTraceRecorder.REFUSE_BUDGET_EXHAUSTED,
        )
        val e = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_BUDGET_REFUSE, e.getString("type"))
        assertEquals(AgentTraceRecorder.DIMENSION_TOOL_CALLS, e.getString("dimension"))
        assertEquals(1, e.getInt("requested"))
        assertEquals(0, e.getInt("remaining"))
        assertEquals(AgentTraceRecorder.REFUSE_BUDGET_EXHAUSTED, e.getString("reason"))
    }

    @Test
    fun `budgetConsume omits absent retry flags`() {
        val sink = Sink()
        val r = recorder(sink)
        r.budgetConsume(AgentTraceRecorder.DIMENSION_TURNS, 1, 199, 200)
        val e = JSONObject(sink.lines[0])
        assertFalse(e.has("is_retry"))
        assertFalse(e.has("is_fallback"))
    }

    @Test
    fun `resource acquire and release round trip with lease token`() {
        val sink = Sink()
        val r = recorder(sink)
        r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "s-1", "lease-1")
        r.resourceRelease(
            AgentTraceRecorder.RESOURCE_SESSION_SLOT,
            "s-1",
            "lease-1",
            releasedBy = AgentTraceRecorder.RELEASED_FINALIZE,
        )
        val a = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_RESOURCE_ACQUIRE, a.getString("type"))
        assertEquals(AgentTraceRecorder.RESOURCE_SESSION_SLOT, a.getString("resource_type"))
        assertEquals("s-1", a.getString("resource_id"))
        assertEquals("lease-1", a.getString("lease_token"))
        val rel = JSONObject(sink.lines[1])
        assertEquals(AgentTraceRecorder.TYPE_RESOURCE_RELEASE, rel.getString("type"))
        assertEquals(AgentTraceRecorder.RELEASED_FINALIZE, rel.getString("released_by"))
        assertEquals("lease-1", rel.getString("lease_token"))
    }

    @Test
    fun `retryDecision writes full decision payload`() {
        val sink = Sink()
        val r = recorder(sink)
        r.retryDecision(
            operationType = "shell_execute",
            operationName = "ls /tmp",
            safetyLevel = AgentTraceRecorder.SAFETY_UNKNOWN,
            outcome = AgentTraceRecorder.OUTCOME_UNKNOWN_RESULT,
            reason = "shell_died_before_result",
            attempt = 2,
            maxAttempts = 2,
            willRetry = false,
        )
        val e = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_RETRY_DECISION, e.getString("type"))
        assertEquals("shell_execute", e.getString("operation_type"))
        assertEquals("ls /tmp", e.getString("operation_name"))
        assertEquals(AgentTraceRecorder.SAFETY_UNKNOWN, e.getString("safety_level"))
        assertEquals(AgentTraceRecorder.OUTCOME_UNKNOWN_RESULT, e.getString("outcome"))
        assertEquals("shell_died_before_result", e.getString("reason"))
        assertEquals(2, e.getInt("attempt"))
        assertEquals(2, e.getInt("max_attempts"))
        assertFalse(e.getBoolean("will_retry"))
    }

    @Test
    fun `persistenceResult writes target success and optional error`() {
        val sink = Sink()
        val r = recorder(sink)
        r.persistenceResult(AgentTraceRecorder.PERSIST_MESSAGE_DB, success = true, durationMs = 45L)
        r.persistenceResult(
            AgentTraceRecorder.PERSIST_CHAT_SESSION,
            success = false,
            errorType = "disk_full",
            durationMs = 200L,
        )
        val ok = JSONObject(sink.lines[0])
        assertEquals(AgentTraceRecorder.TYPE_PERSISTENCE_RESULT, ok.getString("type"))
        assertEquals(AgentTraceRecorder.PERSIST_MESSAGE_DB, ok.getString("target"))
        assertTrue(ok.getBoolean("success"))
        assertFalse(ok.has("error_type"))
        assertEquals(45L, ok.getLong("duration_ms"))
        val fail = JSONObject(sink.lines[1])
        assertFalse(fail.getBoolean("success"))
        assertEquals("disk_full", fail.getString("error_type"))
    }

    // ══════════════════ terminal 事件 ══════════════════

    @Test
    fun `endRun writes full terminal evidence`() {
        val sink = Sink()
        val r = recorder(sink)
        r.beginRun("r-1", "s-1", "P", "hi")
        r.endRun(
            terminalState = "Succeeded",
            terminalReason = "completed_normally",
            durationMs = 15000L,
            totalProviderAttempts = 2,
            totalToolCalls = 5,
            totalShellCommands = 3,
            totalCompactions = 0,
            budgetFinalJson = """{"turnsUsed":3,"providerAttemptsUsed":2}""",
            leasesRemaining = 0,
        )
        val e = JSONObject(sink.lines[1])
        assertEquals(AgentTraceRecorder.TYPE_TRACE_END, e.getString("type"))
        assertEquals("Succeeded", e.getString("terminal_state"))
        assertEquals("completed_normally", e.getString("terminal_reason"))
        assertEquals(15000L, e.getLong("duration_ms"))
        assertEquals(2, e.getInt("total_provider_attempts"))
        assertEquals(5, e.getInt("total_tool_calls"))
        assertEquals(3, e.getInt("total_shell_commands"))
        assertEquals(0, e.getInt("total_compactions"))
        assertEquals(0, e.getInt("leases_remaining"))
        val budget = e.getJSONObject("budget_final_snapshot")
        assertEquals(3, budget.getInt("turnsUsed"))
        assertEquals(2, budget.getInt("providerAttemptsUsed"))
    }

    @Test
    fun `terminal event is written exactly once across repeated endRun`() {
        val sink = Sink()
        val r = recorder(sink)
        r.endRun("Succeeded", "completed_normally", 100L)
        r.endRun("Failed", "internal_error", 200L)
        r.endRun("Cancelled", "user_cancelled", 300L)
        assertEquals(1, sink.lines.size)
        val e = JSONObject(sink.lines[0])
        assertEquals("Succeeded", e.getString("terminal_state"))
        assertEquals(100L, e.getLong("duration_ms"))
        assertTrue(r.isTerminalWritten)
    }

    @Test
    fun `terminal event is written exactly once across traceEnd and endRun`() {
        val sink = Sink()
        val r = recorder(sink)
        r.traceEnd(true, 1, 10L, null)
        r.endRun("Failed", "internal_error", 20L)
        assertEquals(1, sink.lines.size)
        assertEquals("Succeeded", JSONObject(sink.lines[0]).getString("terminal_state"))
    }

    @Test
    fun `legacyOnePointZero traceEnd still sets normal fields and derives terminal state`() {
        val sink = Sink()
        val r = recorder(sink)
        r.traceEnd(normalExit = false, turnCount = 3, durationMs = 9000L, error = "boom")
        val e = JSONObject(sink.lines[0])
        assertEquals("Failed", e.getString("terminal_state"))
        assertEquals(3, e.getInt("turns"))
        assertEquals(9000L, e.getLong("duration_ms"))
        assertEquals("boom", e.getString("error"))
    }

    // ══════════════════ 老记录读取兼容 ══════════════════

    @Test
    fun legacyOnePointZeroRecordParsesAndFallsBack() {
        // 手工构造 1.0 记录：无 schema version、无 run_id、无 terminal_state
        val legacy = """
            {"type":"trace_start","session":"old-s","provider":"P","prompt":"legacy","ts":1000}
            {"type":"turn_start","turn":0,"ts":1001}
            {"type":"trace_end","normal_exit":true,"turns":1,"duration_ms":500,"ts":1500}
        """.trimIndent()
        val events = AgentTraceRecorder.parse(legacy)
        assertEquals(3, events.size)
        val text = AgentTraceRecorder.renderHumanReadable(events)
        assertTrue(text.contains("legacy"))
        assertTrue(text.contains("exit: normal"))
        assertTrue(text.contains("terminal: Succeeded"))
    }

    @Test
    fun `parseWithRunContext fills run id and session id from trace_start`() {
        val raw = """
            {"type":"trace_start","run_id":"r-x","session_id":"s-x","ts":0}
            {"type":"turn_start","turn":0,"ts":1}
        """.trimIndent()
        val events = AgentTraceRecorder.parseWithRunContext(raw)
        assertEquals("r-x", events[1].getString("run_id"))
        assertEquals("s-x", events[1].getString("session_id"))
        // 不覆盖已有值
        val raw2 = """
            {"type":"trace_start","run_id":"r-x","session_id":"s-x","ts":0}
            {"type":"turn_start","turn":0,"run_id":"r-y","ts":1}
        """.trimIndent()
        val events2 = AgentTraceRecorder.parseWithRunContext(raw2)
        assertEquals("r-y", events2[1].getString("run_id"))
    }

    @Test
    fun `round trip of new record preserves all fields`() {
        val sink = Sink()
        val r = recorder(sink)
        r.beginRun("r-1", "s-1", "P", "roundtrip")
        r.stateTransition("Idle", "Preparing", "run_started")
        r.budgetRefuse(AgentTraceRecorder.DIMENSION_TOOL_CALLS, 1, 0, AgentTraceRecorder.REFUSE_BUDGET_EXHAUSTED)
        r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "s-1", "lease-1")
        r.persistenceResult(AgentTraceRecorder.PERSIST_MESSAGE_DB, true)
        r.endRun("Succeeded", "completed_normally", 500L, totalProviderAttempts = 1, totalToolCalls = 0)
        val events = AgentTraceRecorder.parse(sink.raw)
        assertEquals(6, events.size)
        assertEquals(AgentTraceRecorder.TYPE_STATE_TRANSITION, events[1].getString("type"))
        assertEquals(AgentTraceRecorder.TYPE_BUDGET_REFUSE, events[2].getString("type"))
        assertEquals(AgentTraceRecorder.TYPE_RESOURCE_ACQUIRE, events[3].getString("type"))
        assertEquals(AgentTraceRecorder.TYPE_PERSISTENCE_RESULT, events[4].getString("type"))
        assertEquals(AgentTraceRecorder.TYPE_TRACE_END, events[5].getString("type"))
        assertEquals("Succeeded", events[5].getString("terminal_state"))
    }

    // ══════════════════ 审计查询 ══════════════════

    @Test
    fun `auditEvidenceGaps reports missing terminal and unreleased leases`() {
        val sink = Sink()
        val r = recorder(sink)
        r.beginRun("r-1", "s-1", "P", "hi")
        r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "s-1", "lease-1")
        val gaps = AgentTraceRecorder.auditEvidenceGaps(AgentTraceRecorder.parse(sink.raw))
        assertTrue(gaps.any { it.contains("no trace_end") })
        assertTrue(gaps.any { it.contains("lease-1 acquired but never released") })
    }

    @Test
    fun `auditEvidenceGaps empty when run closed cleanly with leases released`() {
        val sink = Sink()
        val r = recorder(sink)
        r.beginRun("r-1", "s-1", "P", "hi")
        r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "s-1", "lease-1")
        r.resourceRelease(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "s-1", "lease-1")
        r.endRun("Succeeded", "completed_normally", 100L, leasesRemaining = 0)
        val gaps = AgentTraceRecorder.auditEvidenceGaps(AgentTraceRecorder.parse(sink.raw))
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `auditEvidenceGaps works on legacy records without crashing`() {
        val legacy = """
            {"type":"trace_start","session":"s","provider":"P","prompt":"p","ts":0}
            {"type":"trace_end","normal_exit":true,"turns":1,"duration_ms":100,"ts":100}
        """.trimIndent()
        val gaps = AgentTraceRecorder.auditEvidenceGaps(AgentTraceRecorder.parse(legacy))
        // 旧记录：无 run_id / 无 terminal_state —— 报告缺口但不崩溃
        assertTrue(gaps.any { it.contains("trace_start missing run_id") })
        assertTrue(gaps.any { it.contains("trace_end missing terminal_state") })
        assertFalse(gaps.any { it.contains("no trace_end") })
    }

    @Test
    fun `terminalLeaseCleanup true when leases_remaining is zero`() {
        val sink = Sink()
        val r = recorder(sink)
        r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "s", "l1")
        r.endRun("Succeeded", "completed_normally", 10L, leasesRemaining = 0)
        assertTrue(AgentTraceRecorder.terminalLeaseCleanup(AgentTraceRecorder.parse(sink.raw)))
    }

    @Test
    fun `terminalLeaseCleanup false when leases remain`() {
        val sink = Sink()
        val r = recorder(sink)
        r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "s", "l1")
        r.endRun("Failed", "internal_error", 10L, leasesRemaining = 1)
        assertFalse(AgentTraceRecorder.terminalLeaseCleanup(AgentTraceRecorder.parse(sink.raw)))
    }

    @Test
    fun `filterBudgetEvents and filterResourceEvents select the right events`() {
        val sink = Sink()
        val r = recorder(sink)
        r.budgetConsume(AgentTraceRecorder.DIMENSION_TURNS, 1, 9, 10)
        r.budgetRefuse(AgentTraceRecorder.DIMENSION_TOOL_CALLS, 1, 0, AgentTraceRecorder.REFUSE_BUDGET_EXHAUSTED)
        r.resourceAcquire(AgentTraceRecorder.RESOURCE_SHELL, "sh", "l1")
        r.turnStart(0)
        val events = AgentTraceRecorder.parse(sink.raw)
        assertEquals(2, AgentTraceRecorder.filterBudgetEvents(events).size)
        assertEquals(1, AgentTraceRecorder.filterResourceEvents(events).size)
    }

    @Test
    fun `legacyTerminalState derives from normal exit flag`() {
        val ok = JSONObject("""{"normal_exit":true}""")
        val bad = JSONObject("""{"normal_exit":false}""")
        assertEquals("Succeeded", AgentTraceRecorder.legacyTerminalState(ok))
        assertEquals("Failed", AgentTraceRecorder.legacyTerminalState(bad))
    }

    // ══════════════════ redaction ══════════════════

    @Test
    fun `trace contains no api key or token values`() {
        val sink = Sink()
        val r = recorder(sink)
        val secret = "sk-9876543210abcdef"
        r.beginRun("r", "s", "P", "use $secret please")
        r.error(0, "provider", "auth failed with $secret")
        r.traceEnd(true, 1, 5L, "secret: $secret")
        val raw = sink.raw
        assertFalse(raw.contains(secret))
        // long secret 被截断
        val longSecret = "sk-" + "x".repeat(500)
        val sink2 = Sink()
        val r2 = recorder(sink2)
        r2.beginRun("r2", "s2", "P", longSecret)
        assertFalse(sink2.raw.contains(longSecret))
        assertEquals(0, r2.sinkFailureCount)
    }

    // ══════════════════ 写失败保护 ══════════════════

    @Test
    fun `sink exception increments failure count and does not propagate`() {
        var calls = 0
        val r = AgentTraceRecorder(appendLine = {
            calls++
            if (calls == 2) throw RuntimeException("disk full")
            // no-op otherwise (simulate a sink that silently drops)
        })
        r.turnStart(0)   // ok
        r.turnStart(1)   // throws → swallowed
        r.turnStart(2)   // ok
        assertEquals(1, r.sinkFailureCount)
        // 后续事件仍可写
        r.turnStart(3)
        assertEquals(1, r.sinkFailureCount)
    }

    @Test
    fun `sink exception during terminal write does not propagate and retries allowed`() {
        var failNext = true
        val r = AgentTraceRecorder(appendLine = {
            if (failNext) {
                failNext = false
                throw RuntimeException("io error")
            }
        })
        r.endRun("Succeeded", "completed_normally", 10L)
        // 第一次终端写失败：吞掉，计数
        assertEquals(1, r.sinkFailureCount)
        // 再次调用（重试路径）：terminal 已标记 wrote → no-op；如果没标记则重试写
        r.endRun("Succeeded", "completed_normally", 10L)
        assertEquals(1, r.sinkFailureCount)
    }

    @Test
    fun `trace sink failure does not change run semantics`() {
        val r = AgentTraceRecorder(appendLine = { throw RuntimeException("never works") })
        // 任何事件方法都不抛异常，调用方主流程不受影响
        r.beginRun("r", "s", "P", "hi")
        r.stateTransition("Idle", "Preparing", "x")
        r.budgetConsume(AgentTraceRecorder.DIMENSION_TURNS, 1, 9, 10)
        r.resourceAcquire(AgentTraceRecorder.RESOURCE_SESSION_SLOT, "s", "l")
        r.endRun("Succeeded", "completed_normally", 1L)
        assertTrue(r.sinkFailureCount > 0)
        assertTrue(r.isTerminalWritten)
    }

    // ══════════════════ 并发行完整性 ══════════════════

    @Test
    fun `concurrent writes never interleave JSONL lines`() {
        val sink = Sink()
        val r = recorder(sink)
        r.beginRun("r-conc", "s-conc", "P", "concurrent")
        val nThreads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(1)
        val done = CountDownLatch(nThreads)
        val errors = AtomicInteger(0)
        repeat(nThreads) { t ->
            pool.execute {
                try {
                    latch.await()
                    repeat(perThread) { i ->
                        r.budgetConsume(AgentTraceRecorder.DIMENSION_TURNS, 1, 199 - (t * perThread + i), 200)
                        r.toolCall(t, "id-$t-$i", "file_read", """{"p":"$t/$i"}""")
                        r.resourceAcquire(AgentTraceRecorder.RESOURCE_TOOL_SLOT, "s", "l-$t-$i")
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        latch.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, errors.get())

        // 每行都是合法 JSON，且无交叉（每次 appendLine 收到完整单行）
        val events = AgentTraceRecorder.parse(sink.raw)
        assertEquals(1 + nThreads * perThread * 3, events.size) // 1 = trace_start
        assertEquals(0, r.sinkFailureCount)
        // 字段完整性抽查：每个合法事件 type 在已知集合内
        val knownTypes = setOf(
            AgentTraceRecorder.TYPE_TRACE_START, AgentTraceRecorder.TYPE_TURN_START,
            AgentTraceRecorder.TYPE_TOOL_CALL, AgentTraceRecorder.TYPE_TOOL_RESULT,
            AgentTraceRecorder.TYPE_TURN_END, AgentTraceRecorder.TYPE_TRACE_END,
            AgentTraceRecorder.TYPE_ERROR, AgentTraceRecorder.TYPE_STATE_TRANSITION,
            AgentTraceRecorder.TYPE_BUDGET_CONSUME, AgentTraceRecorder.TYPE_BUDGET_REFUSE,
            AgentTraceRecorder.TYPE_RESOURCE_ACQUIRE, AgentTraceRecorder.TYPE_RESOURCE_RELEASE,
            AgentTraceRecorder.TYPE_RETRY_DECISION, AgentTraceRecorder.TYPE_PERSISTENCE_RESULT,
        )
        assertTrue(events.all { it.getString("type") in knownTypes })
    }

    @Test
    fun `concurrent terminal writes produce exactly one trace_end`() {
        val sink = Sink()
        val r = recorder(sink)
        val n = 8
        val pool = Executors.newFixedThreadPool(n)
        val latch = CountDownLatch(1)
        val done = CountDownLatch(n)
        repeat(n) {
            pool.execute {
                try {
                    latch.await()
                    r.endRun("Succeeded", "completed_normally", 10L)
                } finally {
                    done.countDown()
                }
            }
        }
        latch.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        val ends = AgentTraceRecorder.parse(sink.raw).filter { it.getString("type") == AgentTraceRecorder.TYPE_TRACE_END }
        assertEquals(1, ends.size)
    }

    @Test
    fun `traceStartKeysDoNotContainSensitiveFields`() {
        // schema 冻结：trace_start 字段集合不含 key/token 类字段
        assertFalse(AgentTraceRecorder.TRACE_START_KEYS_2.any { it.contains("key", ignoreCase = true) || it.contains("token", ignoreCase = true) })
        assertNotNull(AgentTraceRecorder.TRACE_START_KEYS_2)
    }
}