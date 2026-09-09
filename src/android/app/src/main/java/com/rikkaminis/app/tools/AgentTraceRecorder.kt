package com.rikkaminis.app.tools

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T6: Agent execution tracing — structured JSONL trace of one `runAgentLoop`
 * invocation. Schema 2.0: 在 1.0（T9）基础上扩展"预算与终态证据"事件。
 *
 * 2.0 新增（全部可选，向后兼容 1.0）：
 *   - `trace_start` 增加 run_id / session_id / schema 版本 / initial budget 快照；
 *   - `state_transition`（AgentRunPhase 迁移，T5 状态机）；
 *   - `budget_consume` / `budget_refuse`（T2 AgentExecutionBudget 消耗与拒绝）；
 *   - `resource_acquire` / `resource_release`（T1 session slot lease）；
 *   - `retry_decision`（T3 RetryPolicy 决策结果）；
 *   - `persistence_result`（终态落库等必需持久化结果）；
 *   - `trace_end` 增加 terminal state / terminal reason / 汇总计数 / budget 终态快照。
 *
 * 契约（docs/stability/trace-schema.md）：
 *   - 行写入委托给注入的 [appendLine]，recorder 本身纯 JVM；
 *   - **写失败不能阻断主执行**：appendLine 抛异常被捕获并计数（[sinkFailureCount]），
 *     调用方不感知（T7 用该计数做降级日志）；
 *   - **terminal event 去重**：`trace_end` 每个 run 至多写一次，重复调用 no-op；
 *   - **线程安全**：所有写入经内部锁串行化，并发调用不会产生交叉 JSONL；
 *   - 不写 API key / token / 完整 prompt / 完整文件内容（截断 cap 不变 + 2.0 字段
 *     全部为结构化计数与枚举，天然不携带正文）。
 *
 * 宿主（ChatViewModel，T7 接入）负责把行路由到
 * `workspace/.traces/agent-<ts>.jsonl`，并把 T1/T2/T3/T5 的模型映射为本类参数
 * （runId 来自 T1 SessionSlotController.newRunId()，phase 来自 T5 AgentRunPhase 等）。
 */
class AgentTraceRecorder(
    /** Persists one JSON line (no trailing newline). */
    private val appendLine: (line: String) -> Unit,
    /** Clock in epoch-millis (default: System.currentTimeMillis). */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ── event types ────────────────────────────────────────────────────────
    companion object {
        const val TYPE_TRACE_START = "trace_start"
        const val TYPE_TURN_START = "turn_start"
        const val TYPE_TOOL_CALL = "tool_call"
        const val TYPE_TOOL_RESULT = "tool_result"
        const val TYPE_TURN_END = "turn_end"
        const val TYPE_TRACE_END = "trace_end"
        const val TYPE_ERROR = "error"

        // T6: schema 2.0 新增事件类型
        const val TYPE_STATE_TRANSITION = "state_transition"
        const val TYPE_BUDGET_CONSUME = "budget_consume"
        const val TYPE_BUDGET_REFUSE = "budget_refuse"
        const val TYPE_RESOURCE_ACQUIRE = "resource_acquire"
        const val TYPE_RESOURCE_RELEASE = "resource_release"
        const val TYPE_RETRY_DECISION = "retry_decision"
        const val TYPE_PERSISTENCE_RESULT = "persistence_result"

        /** Trace schema 版本标记，写入 trace_start。旧记录无此字段 → 视为 1.0。 */
        const val SCHEMA_VERSION_2 = "2.0"

        /** trace_start 允许的字段键（供 redaction 校验/测试使用）。 */
        val TRACE_START_KEYS_2 = setOf(
            "type", "ts", "trace_schema_version", "run_id", "session_id",
            "created_at_epoch_ms", "prompt_preview", "provider_count", "tool_count",
            "initial_budget",
            // 1.0 遗留字段（保持输出兼容）
            "session", "provider", "prompt",
        )

        // ── truncation caps ────────────────────────────────────────────────
        /** Cap on the user prompt stored in trace_start. */
        const val PROMPT_MAX_LENGTH = 300
        /** Cap on tool args JSON stored per tool_call. */
        const val ARGS_MAX_LENGTH = 500
        /** Cap on tool output stored per tool_result. */
        const val OUTPUT_MAX_LENGTH = 1500
        /** Cap on error message stored per error / trace_end. */
        const val ERROR_MAX_LENGTH = 500

        // ── schema 2.0 枚举值（与 T2/T3/T5 模型 name 的映射由 T7 负责）──────
        // budget dimension 枚举
        const val DIMENSION_TURNS = "turns"
        const val DIMENSION_PROVIDER_ATTEMPTS = "provider_attempts"
        const val DIMENSION_TOOL_CALLS = "tool_calls"
        const val DIMENSION_SHELL_COMMANDS = "shell_commands"
        const val DIMENSION_COMPACTION_CALLS = "compaction_calls"
        const val DIMENSION_CONCURRENT_TOOLS = "concurrent_tools"
        const val DIMENSION_ESTIMATED_TOKENS = "estimated_tokens"

        // budget refuse reason 枚举
        const val REFUSE_BUDGET_EXHAUSTED = "budget_exhausted"
        const val REFUSE_DEADLINE_REACHED = "deadline_reached"
        const val REFUSE_CHILD_QUOTA_EXCEEDED = "child_quota_exceeded"
        const val REFUSE_NOT_RESERVABLE = "not_reservable"

        // resource type 枚举
        const val RESOURCE_SESSION_SLOT = "session_slot"
        const val RESOURCE_SHELL = "shell"
        const val RESOURCE_TOOL_SLOT = "tool_slot"
        const val RESOURCE_WEBVIEW = "webview"
        const val RESOURCE_TEMP_FILE = "temp_file"

        // resource release reason 枚举
        const val RELEASED_NORMAL = "normal"
        const val RELEASED_CANCEL = "cancel"
        const val RELEASED_FINALIZE = "finalize"
        const val RELEASED_ERROR = "error"
        const val RELEASED_TIMEOUT = "timeout"
        const val RELEASED_RECOVERY = "recovery"

        // retry safety level 枚举（对应 T3 RetrySafety）
        const val SAFETY_READ_ONLY = "READ_ONLY"
        const val SAFETY_IDEMPOTENT_WRITE = "IDEMPOTENT_WRITE"
        const val SAFETY_NON_IDEMPOTENT_WRITE = "NON_IDEMPOTENT_WRITE"
        const val SAFETY_UNKNOWN = "UNKNOWN"

        // retry outcome 枚举（对应 T3 RetryOutcome）
        const val OUTCOME_SAFE_TO_RETRY = "SafeToRetry"
        const val OUTCOME_MUST_VERIFY_FIRST = "MustVerifyFirst"
        const val OUTCOME_UNKNOWN_RESULT = "OutcomeUnknown"
        const val OUTCOME_DO_NOT_RETRY = "DoNotRetry"

        // persistence target 枚举
        const val PERSIST_MESSAGE_DB = "message_db"
        const val PERSIST_CHAT_SESSION = "chat_session"
        const val PERSIST_TRACE_FILE = "trace_file"
        const val PERSIST_COMPACT_MARKER = "compact_marker"
        const val PERSIST_BUDGET_SNAPSHOT = "budget_snapshot"

        // ── query helpers (pure, no I/O) ───────────────────────────────────
        /**
         * 敏感值脱敏：把常见凭证形状掩码为 `***`（sk- / ghp_ / Bearer / key= 等）。
         * 调用于所有进入 trace 的自由文本（prompt/error/args/output 截断前）。
         * 不是安全边界 —— 调用方仍应避免把 secret 传给 trace；这是最后一层兜底。
         */
        fun redactSecrets(s: String): String {
            var out = s
            out = out.replace(Regex("""sk-[A-Za-z0-9_-]{8,}"""), "sk-***")
            out = out.replace(Regex("""ghp_[A-Za-z0-9]{20,}"""), "ghp_***")
            out = out.replace(Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{10,}"""), "Bearer ***")
            out = out.replace(
                Regex("""(?i)(api[_-]?key|token|secret|password)(["']?\s*[:=]\s*["']?)[A-Za-z0-9_./+-]{8,}"""),
                "$1$2***",
            )
            return out
        }

        /**
         * 解析 + 脱敏 + 截断：所有进入 trace 的自由文本都走这里。
         * 先脱敏再截断，避免 secret 被截到正则长度以下而漏网。
         */
        fun sanitize(s: String, max: Int): String {
            val redacted = redactSecrets(s)
            return if (redacted.length <= max) redacted else redacted.take(max) + "…"
        }

        /**
         * Parse a raw JSONL trace into events. Malformed lines are skipped
         * (a partial write must never break a query), and the surviving
         * events keep their original order.
         */
        fun parse(raw: String): List<JSONObject> {
            if (raw.isBlank()) return emptyList()
            val out = ArrayList<JSONObject>()
            raw.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEach
                runCatching { JSONObject(t) }
                    .onSuccess { obj -> if (obj.has("type")) out.add(obj) }
            }
            return out
        }

        /**
         * 2.0 兼容读取：解析后为每个事件填充 run id / session id。
         * 规则（schema §4）：事件自身缺 run_id 时，取 trace_start 的 run_id；
         * 无 trace_start 则留空字符串。不修改事件顺序与其它字段。
         */
        fun parseWithRunContext(raw: String): List<JSONObject> {
            val events = parse(raw)
            var runId = ""
            var sessionId = ""
            events.forEach { e ->
                when (e.optString("type")) {
                    TYPE_TRACE_START -> {
                        runId = e.optString("run_id", runId)
                        sessionId = e.optString("session_id", sessionId)
                    }
                    else -> {
                        // 只补空，不覆盖已有值
                        if (!e.has("run_id")) e.put("run_id", runId)
                        if (!e.has("session_id")) e.put("session_id", sessionId)
                    }
                }
            }
            return events
        }

        /** Keep only tool_call/tool_result events for [toolName]. */
        fun filterByTool(events: List<JSONObject>, toolName: String): List<JSONObject> =
            events.filter { e ->
                e.optString("tool") == toolName &&
                    (e.optString("type") == TYPE_TOOL_CALL || e.optString("type") == TYPE_TOOL_RESULT)
            }

        /** Keep only error-signalling events: explicit [TYPE_ERROR] or failed tool results. */
        fun filterErrors(events: List<JSONObject>): List<JSONObject> =
            events.filter { e ->
                when (e.optString("type")) {
                    TYPE_ERROR -> true
                    TYPE_TOOL_RESULT -> !e.optBoolean("success", true)
                    else -> false
                }
            }

        /** Keep only budget-related events (consume/refuse). */
        fun filterBudgetEvents(events: List<JSONObject>): List<JSONObject> =
            events.filter { e ->
                e.optString("type") == TYPE_BUDGET_CONSUME || e.optString("type") == TYPE_BUDGET_REFUSE
            }

        /** Keep only resource lease events (acquire/release). */
        fun filterResourceEvents(events: List<JSONObject>): List<JSONObject> =
            events.filter { e ->
                e.optString("type") == TYPE_RESOURCE_ACQUIRE || e.optString("type") == TYPE_RESOURCE_RELEASE
            }

        /**
         * 证明性审计查询：仅凭 trace 回答"这轮为什么结束 + 资源是否释放"。
         * 返回缺失项列表（空 = 证据完整）。基于 1.0 旧记录也能工作（部分字段缺失时
         * 只报告缺失，不抛异常）。
         */
        fun auditEvidenceGaps(events: List<JSONObject>): List<String> {
            val gaps = mutableListOf<String>()
            val starts = events.filter { it.optString("type") == TYPE_TRACE_START }
            if (starts.isEmpty()) {
                gaps += "no trace_start"
            } else {
                if (starts.first().optString("run_id").isEmpty()) gaps += "trace_start missing run_id"
            }
            val ends = events.filter { it.optString("type") == TYPE_TRACE_END }
            if (ends.isEmpty()) {
                gaps += "no trace_end (run did not close)"
            } else {
                val end = ends.last()
                if (end.optString("terminal_state").isEmpty()) gaps += "trace_end missing terminal_state"
                if (!end.has("duration_ms")) gaps += "trace_end missing duration_ms"
            }
            val acquires = events.filter { it.optString("type") == TYPE_RESOURCE_ACQUIRE }
            val releases = events.filter { it.optString("type") == TYPE_RESOURCE_RELEASE }
            // lease 平衡检查：每个 acquire 的 lease_token 必须有对应 release（或终态声明 leases_remaining=0）
            acquires.forEach { a ->
                val lease = a.optString("lease_token")
                val rel = releases.any { r -> r.optString("lease_token") == lease }
                if (!rel) gaps += "lease $lease acquired but never released"
            }
            return gaps
        }

        /**
         * 终态审计：是否写入了 terminal event，且（若有 budget/slot 维度）
         * 终态是否声明资源释放完。
         */
        fun terminalLeaseCleanup(events: List<JSONObject>): Boolean {
            val ends = events.filter { it.optString("type") == TYPE_TRACE_END }
            if (ends.isEmpty()) return false
            val end = ends.last()
            val leases = end.optInt("leases_remaining", -1)
            if (leases >= 0) return leases == 0
            // 没有 leases_remaining 字段：退回 lease 平衡检查
            val acquires = events.filter { it.optString("type") == TYPE_RESOURCE_ACQUIRE }
            val releases = events.filter { it.optString("type") == TYPE_RESOURCE_RELEASE }
            if (acquires.isEmpty()) return true // 无资源获取，视为干净
            return acquires.all { a ->
                releases.any { r -> r.optString("lease_token") == a.optString("lease_token") }
            }
        }

        /** 从 1.0 旧记录的 trace_end 派生 terminal_state（供渲染/审计，不写回）。 */
        fun legacyTerminalState(end: JSONObject): String =
            if (end.optBoolean("normal_exit", true)) "Succeeded" else "Failed"

        /** Render a human-readable timeline (for 复盘 / export / sharing). */
        fun renderHumanReadable(events: List<JSONObject>): String {
            val sb = StringBuilder()
            var traceStartTs = 0L
            var normalExit = true
            events.forEach { e ->
                when (e.optString("type")) {
                    TYPE_TRACE_START -> {
                        traceStartTs = e.optLong("ts")
                        sb.appendLine("# Agent Trace")
                        sb.appendLine("session: ${e.optString("session").ifBlank { e.optString("session_id") }}  provider: ${e.optString("provider")}")
                        val ver = e.optString("trace_schema_version").takeIf { it.isNotEmpty() }
                        if (ver != null) sb.appendLine("schema: $ver  run: ${e.optString("run_id")}")
                        e.optString("prompt").takeIf { it.isNotEmpty() }?.let {
                            sb.appendLine("prompt: $it")
                        }
                        sb.appendLine()
                    }
                    TYPE_STATE_TRANSITION -> {
                        sb.appendLine("⇄ ${e.optString("from")} → ${e.optString("to")}  (${e.optString("reason")})")
                    }
                    TYPE_BUDGET_CONSUME -> {
                        sb.appendLine("  budget ${e.optString("dimension")} -${e.optInt("consumed", 1)} (${e.optInt("remaining", -1)}/${e.optInt("total", -1)} left)")
                    }
                    TYPE_BUDGET_REFUSE -> {
                        sb.appendLine("  ✗ budget ${e.optString("dimension")} refuse (+${e.optInt("requested", 1)}): ${e.optString("reason")}")
                    }
                    TYPE_RESOURCE_ACQUIRE -> {
                        sb.appendLine("  🔒 ${e.optString("resource_type")} ${e.optString("resource_id")} lease=${e.optString("lease_token")}")
                    }
                    TYPE_RESOURCE_RELEASE -> {
                        sb.appendLine("  🔓 ${e.optString("resource_type")} ${e.optString("resource_id")} (${e.optString("released_by", "normal")})")
                    }
                    TYPE_RETRY_DECISION -> {
                        sb.appendLine("  ↻ retry ${e.optString("operation_type")} [${e.optString("safety_level")}] → ${e.optString("outcome")} (${e.optString("reason")}) will_retry=${e.optBoolean("will_retry", false)}")
                    }
                    TYPE_PERSISTENCE_RESULT -> {
                        sb.appendLine("  💾 ${e.optString("target")} ${if (e.optBoolean("success", true)) "OK" else "FAIL"}")
                    }
                    TYPE_TURN_START -> {
                        sb.appendLine("## turn ${e.optInt("turn", -1)}  (${fmtTime(e.optLong("ts"))})")
                    }
                    TYPE_TOOL_CALL -> {
                        sb.appendLine("  → ${e.optString("tool")}  ${e.optString("tool_id")}")
                        e.optString("args").takeIf { it.isNotEmpty() }?.let {
                            sb.appendLine("      args: $it")
                        }
                    }
                    TYPE_TOOL_RESULT -> {
                        val ok = e.optBoolean("success", true)
                        val dur = e.optLong("duration_ms", -1)
                        sb.appendLine(
                            "  ← ${e.optString("tool")} ${if (ok) "OK" else "FAIL"} " +
                                (if (dur >= 0) "(${dur}ms)" else "") +
                                " [${e.optString("output").length} chars]"
                        )
                    }
                    TYPE_TURN_END -> {
                        val parts = buildList {
                            e.optInt("tokens_in", -1).takeIf { it >= 0 }?.let { add("in=$it") }
                            e.optInt("tokens_out", -1).takeIf { it >= 0 }?.let { add("out=$it") }
                            e.optString("finish_reason").takeIf { it.isNotEmpty() }?.let { add("finish=$it") }
                            e.optLong("duration_ms", -1).takeIf { it >= 0 }?.let { add("${it}ms") }
                        }
                        sb.appendLine("  end turn ${e.optInt("turn", -1)}  ${parts.joinToString("  ")}")
                    }
                    TYPE_ERROR -> {
                        sb.appendLine("  ⚠ error (${e.optString("phase")}): ${e.optString("message")}")
                    }
                    TYPE_TRACE_END -> {
                        normalExit = e.optBoolean("normal_exit", true)
                        val dur = e.optLong("duration_ms", -1)
                        sb.appendLine()
                        val terminal = e.optString("terminal_state").ifEmpty { legacyTerminalState(e) }
                        val reason = e.optString("terminal_reason").ifEmpty { "" }
                        sb.appendLine(
                            "turns: ${e.optInt("turns", -1)}  " +
                                (if (dur >= 0) "duration: ${dur}ms  " else "") +
                                (if (normalExit) "exit: normal" else "exit: MAX_AGENT_TURNS/error") +
                                "  terminal: $terminal" +
                                (if (reason.isNotEmpty()) " ($reason)" else "")
                        )
                        e.optString("error").takeIf { it.isNotEmpty() }?.let {
                            sb.appendLine("error: $it")
                        }
                    }
                }
            }
            if (traceStartTs > 0) {
                sb.appendLine()
                sb.appendLine("trace started: ${fmtTime(traceStartTs)}")
            }
            return sb.toString()
        }

        private fun fmtTime(ms: Long): String {
            if (ms <= 0) return "?"
            val df = SimpleDateFormat("HH:mm:ss", Locale.US)
            df.timeZone = TimeZone.getDefault()
            return df.format(Date(ms))
        }
    }

    // ── instance state (2.0 run context) ──────────────────────────────────
    private val writeLock = Any()
    private var activeRunId: String = ""
    private var activeSessionId: String = ""
    private var terminalWritten = false
    private var failedWriteCount = 0

    /** appendLine 抛异常被吞掉的次数（写失败不阻断主执行，计数供降级日志）。 */
    val sinkFailureCount: Int get() = synchronized(writeLock) { failedWriteCount }

    /** 当前 run 是否已写过 terminal 事件（去重依据）。 */
    val isTerminalWritten: Boolean get() = synchronized(writeLock) { terminalWritten }

    // ── public API (1.0 兼容，字段不变) ───────────────────────────────────
    /** Run-level header: one per runAgentLoop invocation. */
    fun traceStart(sessionId: String, provider: String, prompt: String) {
        write(TYPE_TRACE_START) {
            put("session", sessionId)
            put("provider", provider)
            put("prompt", sanitize(prompt, PROMPT_MAX_LENGTH))
        }
    }

    // ── public API (2.0 run context) ──────────────────────────────────────
    /**
     * 2.0 run 头：绑定 runId/sessionId + schema 版本 + initial budget 快照。
     * 之后本 recorder 的所有事件都自动携带该 run 上下文（无需重复传参）。
     * 重复调用会覆盖上下文并重新开启 terminal 去重窗口。
     */
    fun beginRun(
        runId: String,
        sessionId: String,
        provider: String,
        prompt: String,
        providerCount: Int = 0,
        toolCount: Int = 0,
        initialBudgetJson: String? = null,
    ) {
        synchronized(writeLock) {
            activeRunId = runId
            activeSessionId = sessionId
            terminalWritten = false
        }
        write(TYPE_TRACE_START) {
            put("trace_schema_version", SCHEMA_VERSION_2)
            put("run_id", runId)
            put("session_id", sessionId)
            put("created_at_epoch_ms", clock())
            put("prompt_preview", sanitize(prompt, PROMPT_MAX_LENGTH))
            put("provider_count", providerCount)
            put("tool_count", toolCount)
            initialBudgetJson?.let { put("initial_budget", JSONObject(it)) }
            // 1.0 遗留字段，保持旧解析器兼容
            put("session", sessionId)
            put("provider", provider)
            put("prompt", sanitize(prompt, PROMPT_MAX_LENGTH))
        }
    }

    /** AgentRunPhase 迁移（T5 状态机）。from/to 为 schema 枚举字符串（如 "CallingModel"）。 */
    fun stateTransition(from: String, to: String, reason: String?) {
        write(TYPE_STATE_TRANSITION) {
            put("from", from)
            put("to", to)
            reason?.takeIf { it.isNotEmpty() }?.let { put("reason", it) }
        }
    }

    /** 预算消耗（T2 consume）。dimension 见 [DIMENSION_*]。 */
    fun budgetConsume(
        dimension: String,
        consumed: Int,
        remaining: Int,
        total: Int,
        isRetry: Boolean? = null,
        isFallback: Boolean? = null,
    ) {
        write(TYPE_BUDGET_CONSUME) {
            put("dimension", dimension)
            put("consumed", consumed)
            put("remaining", remaining)
            put("total", total)
            isRetry?.let { put("is_retry", it) }
            isFallback?.let { put("is_fallback", it) }
        }
    }

    /** 预算拒绝（T2 Denied）。reason 见 [REFUSE_*]。 */
    fun budgetRefuse(
        dimension: String,
        requested: Int,
        remaining: Int,
        reason: String,
    ) {
        write(TYPE_BUDGET_REFUSE) {
            put("dimension", dimension)
            put("requested", requested)
            put("remaining", remaining)
            put("reason", reason)
        }
    }

    /** 资源获取（T1 lease）。resourceType 见 [RESOURCE_*]。 */
    fun resourceAcquire(
        resourceType: String,
        resourceId: String,
        leaseToken: String,
    ) {
        write(TYPE_RESOURCE_ACQUIRE) {
            put("resource_type", resourceType)
            put("resource_id", resourceId)
            put("lease_token", leaseToken)
        }
    }

    /** 资源释放。releasedBy 见 [RELEASED_*]。 */
    fun resourceRelease(
        resourceType: String,
        resourceId: String,
        leaseToken: String,
        releasedBy: String = RELEASED_NORMAL,
    ) {
        write(TYPE_RESOURCE_RELEASE) {
            put("resource_type", resourceType)
            put("resource_id", resourceId)
            put("lease_token", leaseToken)
            put("released_by", releasedBy)
        }
    }

    /** 自动重试决策（T3）。outcome 见 [OUTCOME_*]，safetyLevel 见 [SAFETY_*]。 */
    fun retryDecision(
        operationType: String,
        operationName: String? = null,
        safetyLevel: String? = null,
        outcome: String? = null,
        reason: String? = null,
        attempt: Int? = null,
        maxAttempts: Int? = null,
        willRetry: Boolean? = null,
    ) {
        write(TYPE_RETRY_DECISION) {
            put("operation_type", operationType)
            operationName?.takeIf { it.isNotEmpty() }?.let { put("operation_name", it) }
            safetyLevel?.let { put("safety_level", it) }
            outcome?.let { put("outcome", it) }
            reason?.takeIf { it.isNotEmpty() }?.let { put("reason", it) }
            attempt?.let { put("attempt", it) }
            maxAttempts?.let { put("max_attempts", it) }
            willRetry?.let { put("will_retry", it) }
        }
    }

    /** 必需持久化结果。target 见 [PERSIST_*]。 */
    fun persistenceResult(
        target: String,
        success: Boolean,
        errorType: String? = null,
        durationMs: Long? = null,
    ) {
        write(TYPE_PERSISTENCE_RESULT) {
            put("target", target)
            put("success", success)
            errorType?.takeIf { it.isNotEmpty() }?.let { put("error_type", it) }
            durationMs?.let { put("duration_ms", it) }
        }
    }

    // ── public API (1.0 遗留方法) ─────────────────────────────────────────
    /** Start of one agent-loop iteration. */
    fun turnStart(turn: Int) {
        write(TYPE_TURN_START) { put("turn", turn) }
    }

    /** A tool call was requested by the model (arguments truncated). */
    fun toolCall(turn: Int, toolId: String, name: String, argsJson: String) {
        write(TYPE_TOOL_CALL) {
            put("turn", turn)
            put("tool_id", toolId)
            put("tool", name)
            put("args", sanitize(argsJson, ARGS_MAX_LENGTH))
        }
    }

    /** Outcome of one tool execution (output truncated). */
    fun toolResult(
        turn: Int,
        toolId: String,
        name: String,
        success: Boolean,
        output: String,
        durationMs: Long,
    ) {
        write(TYPE_TOOL_RESULT) {
            put("turn", turn)
            put("tool_id", toolId)
            put("tool", name)
            put("success", success)
            put("duration_ms", durationMs)
            put("output", sanitize(output, OUTPUT_MAX_LENGTH))
        }
    }

    /** End of one agent-loop iteration: token usage + finish reason + elapsed. */
    fun turnEnd(
        turn: Int,
        tokensIn: Int?,
        tokensOut: Int?,
        finishReason: String?,
        durationMs: Long,
    ) {
        write(TYPE_TURN_END) {
            put("turn", turn)
            tokensIn?.let { put("tokens_in", it) }
            tokensOut?.let { put("tokens_out", it) }
            finishReason?.takeIf { it.isNotEmpty() }?.let { put("finish_reason", it) }
            put("duration_ms", durationMs)
        }
    }

    /** An error surfaced anywhere in the loop (phase = where it happened). */
    fun error(turn: Int?, phase: String, message: String) {
        write(TYPE_ERROR) {
            turn?.let { put("turn", it) }
            put("phase", phase)
            put("message", sanitize(message, ERROR_MAX_LENGTH))
        }
    }

    /** 1.0 run footer: how the loop exited. 与 [endRun] 共享 terminal 去重。 */
    fun traceEnd(normalExit: Boolean, turnCount: Int, durationMs: Long, error: String?) {
        writeTerminalOnce {
            put("normal_exit", normalExit)
            put("turns", turnCount)
            put("duration_ms", durationMs)
            error?.takeIf { it.isNotEmpty() }?.let { put("error", sanitize(it, ERROR_MAX_LENGTH)) }
            // 2.0 字段（1.0 调用时也补上，便于统一审计）
            put("terminal_state", if (normalExit) "Succeeded" else "Failed")
            put("terminal_reason", if (normalExit) "completed_normally" else "internal_error")
        }
    }

    /**
     * 2.0 run footer：完整终态证据。terminalState/terminalReason 为 schema 枚举
     * （对应 T5 AgentTerminal/AgentTerminalReason 的映射字符串）。
     * 去重：每个 run 至多落一条 trace_end；重复调用 no-op。
     */
    fun endRun(
        terminalState: String,
        terminalReason: String? = null,
        durationMs: Long,
        totalProviderAttempts: Int? = null,
        totalToolCalls: Int? = null,
        totalShellCommands: Int? = null,
        totalCompactions: Int? = null,
        budgetFinalJson: String? = null,
        leasesRemaining: Int? = null,
        error: String? = null,
    ) {
        writeTerminalOnce {
            put("terminal_state", terminalState)
            terminalReason?.takeIf { it.isNotEmpty() }?.let { put("terminal_reason", it) }
            put("duration_ms", durationMs)
            totalProviderAttempts?.let { put("total_provider_attempts", it) }
            totalToolCalls?.let { put("total_tool_calls", it) }
            totalShellCommands?.let { put("total_shell_commands", it) }
            totalCompactions?.let { put("total_compactions", it) }
            budgetFinalJson?.let { put("budget_final_snapshot", JSONObject(it)) }
            leasesRemaining?.let { put("leases_remaining", it) }
            error?.takeIf { it.isNotEmpty() }?.let { put("error", sanitize(it, ERROR_MAX_LENGTH)) }
        }
    }

    // ── internal ───────────────────────────────────────────────────────────
    /**
     * 序列化并写入一行。线程安全：全部写入经 [writeLock] 串行化，
     * 并发调用不会把两个事件交错进同一行。
     * 写失败（appendLine 抛异常）被吞掉并计数，绝不向上传播 ——
     * trace 是旁路证据，不能改变 Run 终态。
     */
    private fun write(type: String, block: JSONObject.() -> Unit) {
        val line = buildLine(type, block)
        synchronized(writeLock) {
            runCatching { appendLine(line) }
                .onFailure { failedWriteCount++ }
        }
    }

    /** terminal 专用的写路径：去重 + 写失败保护。 */
    private fun writeTerminalOnce(block: JSONObject.() -> Unit) {
        synchronized(writeLock) {
            if (terminalWritten) return
            terminalWritten = true
            val line = buildLine(TYPE_TRACE_END, block)
            runCatching { appendLine(line) }
                .onFailure { failedWriteCount++ }
        }
    }

    private fun buildLine(type: String, block: JSONObject.() -> Unit): String {
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("ts", clock())
        if (activeRunId.isNotEmpty() && !type.equals(TYPE_TRACE_START)) obj.put("run_id", activeRunId)
        if (activeSessionId.isNotEmpty() && !type.equals(TYPE_TRACE_START)) obj.put("session_id", activeSessionId)
        obj.block()
        return obj.toString()
    }

    private fun truncate(s: String, max: Int): String =
        sanitize(s, max)
}