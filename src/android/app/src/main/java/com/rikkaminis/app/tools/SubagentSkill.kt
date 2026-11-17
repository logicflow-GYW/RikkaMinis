package com.rikkaminis.app.tools

import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam

/**
 * Sub-agent dispatch — an APP-LEVEL capability, not a skill feature.
 *
 * [rework/spawn-agent-as-real-session 2026-11-17] Two rewrites in one:
 *
 *  1. A sub-agent is a REAL chat session, not a second agent loop. The old
 *     `runSubagentLoop` had its own filtered tool set (no shell, no browser),
 *     no visible progress, and every main-loop fix had to be duplicated into
 *     it — nine "main loop fixed it, sub-agent still broken" patches piled up.
 *     Dispatch now uses the same path as `minis-sessions-cli send`: a real
 *     session on the real ChatViewModel.
 *  2. It no longer rides on the skill system. The caller passes the complete
 *     task; nothing here requires a skill to be installed, a `subagent: true`
 *     frontmatter declaration, or a per-session skill enable flag. A skill can
 *     still be used as a task template — the caller reads its SKILL.md and puts
 *     the instructions in the task text — but dispatch does not depend on one.
 *
 * [extractFrontmatterBlock] stays because `SkillRepository` uses it to carry
 * frontmatter-only keys through an edit/save round-trip. That is skill
 * plumbing, unrelated to dispatch.
 */
object SubagentSkill {

    const val NAME = "spawn_agent"

    /**
     * How long a synchronous dispatch blocks for the child's final answer
     * before returning a visible timeout (upper bound of
     * `ChatMutationMethods.prompt`'s `waitTimeout`, which clamps at 1800).
     */
    const val WAIT_TIMEOUT_SECONDS = 900

    /**
     * Floor for the budget-derived wait ([effectiveWaitSeconds]).
     *
     * A sub-second remainder must still produce a real wait whose normal
     * timeout envelope comes back, rather than a distinct "0 s" shape the
     * caller has no branch for.
     */
    const val MIN_WAIT_SECONDS = 1

    // ── Budget ───────────────────────────────────────────────────────────

    /**
     * How long this dispatch may block, given what is left of the run's
     * deadline.
     *
     * [INCR-1117 A-F07] [WAIT_TIMEOUT_SECONDS] used to be applied
     * unconditionally, and the run's deadline is only consulted at turn
     * boundaries — so with 5 s of budget left a tool call could block the full
     * 900 s and outlive the run it belongs to. The wait is capped by the
     * remaining budget and floored at [MIN_WAIT_SECONDS].
     *
     * Pure on purpose (no budget object, no clock): the arithmetic is the part
     * that can be wrong, and it is JVM-tested. `null` = no budget attached →
     * the plain cap.
     */
    fun effectiveWaitSeconds(budgetRemainingMs: Long?, capSeconds: Int): Int {
        if (budgetRemainingMs == null) return capSeconds
        val fromBudget = (budgetRemainingMs / 1000L).coerceAtMost(capSeconds.toLong()).toInt()
        return minOf(capSeconds, maxOf(MIN_WAIT_SECONDS, fromBudget))
    }

    // ── Tool definition ──────────────────────────────────────────────────

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Dispatch a sub-agent as a REAL chat session and block until " +
            "it answers. The sub-agent gets the app's normal agent prompt and the " +
            "full tool set (shell_execute, browser_use, file/memory tools), and its " +
            "session appears in the chat list so the user can watch or interrupt it. " +
            "Use it for delegated work that needs tools: hand it a complete, " +
            "self-contained task (goal, inputs/paths, constraints, expected output) — " +
            "it cannot see this conversation. Returns the sub-agent's final text. " +
            "Do NOT dispatch from inside a sub-agent. For fire-and-forget or " +
            "parallel work, use minis-sessions-cli send --async instead.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this sub-agent should do",
            ),
            "task" to AgentToolParam(
                "string",
                "The complete task: goal, inputs/paths, constraints, and the exact " +
                    "output you expect. The sub-agent sees only this text.",
            ),
        ),
        required = listOf("tool_title", "task"),
        propertyOrdering = listOf("tool_title", "task"),
    )

    // ── Prompt building ──────────────────────────────────────────────────

    /**
     * Wrap the caller's task with the dispatch preamble.
     *
     * Pure so the shape is unit-tested. The sub-agent must know two things it
     * cannot infer: (a) its final reply is the ONLY thing the parent sees, and
     * (b) it must not recurse into another dispatch.
     */
    fun buildTaskPrompt(task: String): String = buildString {
        appendLine("You are a sub-agent dispatched for one task in this session.")
        appendLine(
            "Put your final answer in your reply — it is returned verbatim to the " +
                "agent that dispatched you, which cannot see your intermediate " +
                "steps. Make it self-contained."
        )
        appendLine()
        appendLine("## Task")
        appendLine(task.trim())
        appendLine()
        appendLine("## Working agreement")
        appendLine("- Prefer finishing over exploring; state what you verified and what you did not.")
        appendLine("- Do not dispatch another sub-agent and do not create new sessions.")
    }.trimEnd()

    // ── Result mapping ───────────────────────────────────────────────────

    /**
     * Read one reply field without turning a JSON null into the literal
     * `"null"`.
     *
     * [INCR-1117 A-F01] `ChatMutationMethods.prompt` writes
     * `put("responseText", result.responseText ?: JSONObject.NULL)` — the wire
     * shape `minis-sessions-cli send` and the scheduled-task path both read, so
     * the null stays. What differs is how the two `org.json` implementations
     * read it back: JSON-java's `optString(name, fallback)` returns the
     * fallback for `JSONObject.NULL`, Android's returns the literal `"null"`
     * (its `JSON.toString(NULL)` is `"null"`). The tool runs on Android, so a
     * blank answer arrived as `"null"` and [resultOf]'s `ifBlank` guard was
     * dead code: an empty completion reported `null` as the answer and a
     * refusal reported `… (status=…): null`.
     *
     * `isNull` means "null or absent" in BOTH implementations, which is why the
     * resolution belongs on the reader side.
     */
    fun replyText(reply: org.json.JSONObject, key: String = "responseText"): String =
        if (reply.isNull(key)) "" else reply.optString(key, "")

    /**
     * Map a `ChatMutationMethods.prompt` reply onto the caller's tool result.
     *
     * Pure on purpose (no Context / no ViewModel): the four outcomes are the
     * contract the dispatching model reads — a finished answer, an empty
     * answer, a timeout, and a refusal — and each must be distinguishable, or
     * a refusal gets read as an answer (the [deep-audit-1044 A-04] defect the
     * `send` CLI path hit).
     */
    fun resultOf(
        status: String,
        text: String,
        sessionId: String,
        timedOut: Boolean,
        title: String = "Sub-agent",
        timeoutSeconds: Int = WAIT_TIMEOUT_SECONDS,
    ): ToolExecutionResult {
        val where = if (sessionId.isBlank()) "" else "\n\n(session: $sessionId)"
        return when {
            status == "Completed" -> ToolExecutionResult(
                text.ifBlank { "(sub-agent finished without producing text)" } + where,
                true,
                toolTitle = title,
            )
            timedOut -> ToolExecutionResult(
                "Error: sub-agent did not finish within ${timeoutSeconds}s. " +
                    "It may still be running — read it with " +
                    "`minis-sessions-cli messages --id $sessionId`, or continue it with " +
                    "`minis-sessions-cli send --session $sessionId --prompt ...`.$where",
                false, toolTitle = title,
            )
            else -> ToolExecutionResult(
                "Error: sub-agent dispatch was not delivered (status=$status): " +
                    (text.ifBlank { "no reason given" }) + where,
                false, toolTitle = title,
            )
        }
    }

    // ── Skill plumbing (not dispatch) ────────────────────────────────────

    /**
     * The frontmatter block of a SKILL.md document (`---` … `---` inclusive),
     * or "" when the document has no leading frontmatter / no closing marker.
     *
     * Pure string work on purpose: `SkillRepository` (Android-dependent, not
     * JVM-unit-testable) is the production source of the block, and this is
     * the one piece of that hand-off that silently broke before — keeping the
     * extraction here puts it under JVM tests.
     */
    fun extractFrontmatterBlock(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.trimStart().lines()
        if (lines.isEmpty() || !lines[0].trim().startsWith("---")) return ""
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") return lines.subList(0, i + 1).joinToString("\n")
        }
        return ""
    }
}
