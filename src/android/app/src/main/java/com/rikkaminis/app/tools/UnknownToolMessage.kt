package com.rikkaminis.app.tools

/**
 * [audit-0916] Corrective message for a tool call that names a tool which does
 * not exist (or that this agent is not allowed to use).
 *
 * Why this is not just "Unknown tool: X":
 *
 * The 2026-09-16 log shows one session (provider=OpenAI model=glm-5.3-flash)
 * where the model invented a whole foreign tool-call format and burned EIGHT
 * round-trips cycling through hallucinated names — `bash`, `shell`,
 * `shell_command`, `bash_shell`, `bash_action</arg_key><arg_value>ls /tmp/…`,
 * `<tool_call><tool_call>bash<tool_call>command`. Every one of those turns
 * re-sent the entire conversation (~420k chars in that session), and the reply
 * the model got back — "Unknown tool: bash" — named nothing it could correct
 * TOWARDS, even though the tool list had been declared to the provider
 * (`tools=11`). A rejection that names nothing offers no way back.
 *
 * So the rejection stays a rejection — no fuzzy matching, no guessing at what
 * the model "meant" (see the repo's refuse-instead-of-guess stance in
 * [com.rikkaminis.app.ui.chat.ToolCallResiduePolicy]) — but it now lands AWARE
 * of the real names. The names are sorted so the message is determinstic, which
 * keeps it testable and keeps repeated rejections byte-identical.
 */
object UnknownToolMessage {

    /**
     * Cap for an echoed model-sent name — long enough for a real tool name with
     * markup stuck to it, short enough that a garbage name can't bloat the
     * conversation it is about to be re-sent into.
     */
    const val MAX_NAME_LEN = 64

    /**
     * Make a model-sent tool name safe to echo back inside a tool result.
     *
     * The same log shows names arriving with embedded markup —
     * `shell_execute</arg_value>`, `bash_action</arg_key><arg_value>ls /tmp/…` —
     * one of them carrying a whole command line. Echoing that raw would inject
     * arbitrary text into the tool result the model reads back, so control
     * characters (newlines and tabs among them — both below 0x20) are dropped
     * and the length is capped.
     */
    fun clampsName(raw: String): String =
        raw.filter { it.code >= 0x20 }
            .trim()
            .take(MAX_NAME_LEN)

    /**
     * Build the rejection message for [rawName], listing [availableTools] (the
     * names this agent may actually call) when any are known.
     *
     * [forbidden] selects the sub-agent wording: inside a sub-agent the same
     * rejection covers both "no such tool" and "this tool is not allowed here",
     * and the list it carries is the sub-agent's own filtered set.
     */
    fun message(
        rawName: String,
        availableTools: List<String>,
        forbidden: Boolean = false,
    ): String {
        // [audit-0917] A name made only of control chars clamps to empty and the
        // rejection named nothing - the exact failure this message exists to
        // avoid. Fall back to a placeholder so it stays actionable.
        val name = clampsName(rawName).ifEmpty { "(unreadable name)" }
        val head = if (forbidden) "Error: Unknown or forbidden tool: $name" else "Unknown tool: $name"
        val names = availableTools.filter { it.isNotEmpty() }.sorted()
        if (!names.isNotEmpty()) return "$head."
        return "$head. This agent can use: ${names.joinToString(", ")}."
    }
}
