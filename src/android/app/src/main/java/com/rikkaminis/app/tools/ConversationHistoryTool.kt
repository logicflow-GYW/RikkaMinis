package com.rikkaminis.app.tools

import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * `conversation_history` — read back the persisted transcript of the CURRENT
 * conversation.
 *
 * Why this exists: compaction is lossy and frequent. Measured on-device
 * 2026-09-14 — a 35-turn stretch of offload attempts reclaimed 19.5% of what it
 * needed while the context climbed monotonically to the 83.6% compact line, so
 * detail the user and the model had already exchanged gets dropped routinely.
 * Nothing the agent has can reach the dropped messages: `memory_get` searches
 * memory files only, and the message rows live in Room, which the shell sandbox
 * cannot address. After a compaction the model cannot answer "what did we
 * decide four turns ago" even though the answer is still on disk.
 *
 * Session binding: the session comes from the caller, never from arguments. The
 * schema deliberately exposes no session parameter (tokens, and it removes the
 * "read someone else's conversation" shape entirely), and a call that passes one
 * anyway is **refused, not silently ignored** — quietly reading the active
 * session for a request that named a different one is exactly the failure a
 * stale session id produces, and it would look like a successful read.
 *
 * Redaction: the executor runs the rendered page through the same
 * `EnvVarRedactor.redactIfEnabled` the shell tool uses, so a secret that was
 * echoed into a message is masked on the way back out.
 *
 * The pure core below (render / query / windowing / paging) is Android-free and
 * unit-tested; the executor takes the row loader as a lambda so the whole tool
 * path is testable without Room.
 */
object ConversationHistoryContract {
    const val NAME = "conversation_history"

    const val DEFAULT_MAX_CHARS = 6000
    const val MIN_MAX_CHARS = 500
    const val MAX_MAX_CHARS = 32000

    const val DEFAULT_MAX_ITEMS = 40
    const val MIN_MAX_ITEMS = 1
    const val MAX_MAX_ITEMS = 200

    /** Head kept when a line is too long to show in full. */
    const val TEXT_PREVIEW_CHARS = 400
    /** Budget for one tool call/result payload inside a line. */
    const val PAYLOAD_PREVIEW_CHARS = 240
    /** Context kept on each side of a query hit. */
    const val QUERY_CONTEXT_CHARS = 160
    /**
     * Ceiling on the text a single message contributes to query matching. Big
     * tool outputs are kept in the DB in full; searching an unbounded 100KB blob
     * buys nothing and makes the tool's memory cost unbounded.
     */
    const val MAX_SEARCHABLE_CHARS_PER_LINE = 40_000

    /** Argument names that name a conversation. Supplying one is refused. */
    val SESSION_ARGUMENT_NAMES = listOf("session", "session_id", "sessionId", "conversation", "conversation_id")
}

/** One persisted message, reduced to what the transcript needs. */
data class TranscriptRow(
    /** Stable DB `sort_order` of the message, as reported to the model.
     *  [T-tools-history-cursor-stable] NOT the list position: a mid-conversation
     *  deletion shifts positions but never sort_order, so cursors persisted in
     *  the model's next turn stay valid across reads. */
    val index: Int,
    /** `user` / `assistant` / anything the DB holds. */
    val role: String,
    val partsJson: String,
)

/**
 * One rendered line. [fullText] is what query matching sees (text parts whole,
 * tool payloads previewed); [display] is what the model gets.
 */
data class TranscriptLine(
    val index: Int,
    val role: String,
    val fullText: String,
    val display: String,
    /** True when a tool payload in this line was cut for display. */
    val payloadTruncated: Boolean = false,
)

data class TranscriptPage(
    val header: String,
    val lines: List<TranscriptLine>,
    /** Index to pass as `cursor` for the previous (older) page, or null. */
    val nextCursor: Int?,
)

/** Parsed + clamped tool arguments. */
data class ConversationHistoryArgs(
    val query: String?,
    val cursor: Int?,
    val maxChars: Int,
    val maxItems: Int,
)

/** Result of parsing: either args or a message to hand back as an error. */
sealed class ConversationHistoryRequest {
    data class Ok(val args: ConversationHistoryArgs) : ConversationHistoryRequest()
    data class Invalid(val message: String) : ConversationHistoryRequest()
}

/** Renders one part JSON array element. Returns null for parts with nothing to say. */
private fun renderPart(part: JSONObject, payloadBudget: Int): Pair<String, Boolean>? {
    val type = part.optString("type")
    val value = part.opt("value")
    return when (type) {
        "text" -> (value as? String)?.takeIf { it.isNotEmpty() }?.let { it to false }
        "mediaRef" -> {
            val name = (value as? JSONObject)?.let {
                it.optString("originalFileName").ifEmpty { it.optString("mimeType") }
            }.orEmpty()
            "[image${if (name.isEmpty()) "" else " $name"}]" to false
        }
        "toolUse" -> {
            val obj = value as? JSONObject ?: return null
            val name = obj.optString("name").ifEmpty { "?" }
            val input = obj.optString("input")
            val (preview, cut) = preview(input, payloadBudget)
            "[tool call $name] $preview" to cut
        }
        "toolResult" -> {
            val obj = value as? JSONObject ?: return null
            val ok = obj.optBoolean("success", true)
            val output = obj.optString("output")
            val (preview, cut) = preview(output, payloadBudget)
            "[tool result ${if (ok) "ok" else "failed"}] $preview" to cut
        }
        else -> null
    }
}

private fun preview(text: String?, budget: Int): Pair<String, Boolean> {
    val s = text?.trim().orEmpty()
    if (s.isEmpty()) return "(empty)" to false
    if (s.length <= budget) return s to false
    return s.take(budget) + " …[+${s.length - budget} chars]" to true
}

/**
 * Turns one stored message into a transcript line. Never throws: a malformed
 * `partsJson` degrades to a placeholder rather than failing the whole read.
 */
fun renderTranscriptLine(row: TranscriptRow): TranscriptLine {
    val sb = StringBuilder()
    var anyPayloadCut = false
    val parts = runCatching { JSONArray(row.partsJson) }.getOrNull()
    if (parts != null) {
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val (text, cut) = renderPart(part, ConversationHistoryContract.PAYLOAD_PREVIEW_CHARS) ?: continue
            if (text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
            if (cut) anyPayloadCut = true
        }
    }
    var full = sb.toString()
    var searchCut = false
    if (full.length > ConversationHistoryContract.MAX_SEARCHABLE_CHARS_PER_LINE) {
        full = full.take(ConversationHistoryContract.MAX_SEARCHABLE_CHARS_PER_LINE)
        searchCut = true
    }
    val display = when {
        full.isBlank() -> "[empty message]"
        full.length <= ConversationHistoryContract.TEXT_PREVIEW_CHARS -> full
        else -> full.take(ConversationHistoryContract.TEXT_PREVIEW_CHARS) +
            " …[+${full.length - ConversationHistoryContract.TEXT_PREVIEW_CHARS} chars]"
    }
    return TranscriptLine(
        index = row.index,
        role = row.role,
        fullText = full,
        display = display,
        payloadTruncated = anyPayloadCut || searchCut,
    )
}

/** Window of [line.fullText] around the first case-insensitive [query] hit. */
fun querySnippet(line: TranscriptLine, query: String): String {
    val hay = line.fullText
    val at = hay.indexOf(query, ignoreCase = true)
    if (at < 0) return line.display
    val ctx = ConversationHistoryContract.QUERY_CONTEXT_CHARS
    val from = (at - ctx).coerceAtLeast(0)
    val to = (at + query.length + ctx).coerceAtMost(hay.length)
    val head = if (from > 0) "…" else ""
    val tail = if (to < hay.length) "…" else ""
    return head + hay.substring(from, to).replace('\n', ' ') + tail
}

private fun Int.clampTo(min: Int, max: Int): Int = coerceIn(min, max)

/**
 * Parses untrusted model arguments. Anything malformed is reported instead of
 * guessed at (a silently clamped `cursor` would page to the wrong place).
 */
fun parseConversationHistoryRequest(argsJson: String?): ConversationHistoryRequest {
    val obj = runCatching { JSONObject(argsJson ?: "{}") }.getOrNull()
        ?: return ConversationHistoryRequest.Invalid("arguments are not a JSON object")
    for (name in ConversationHistoryContract.SESSION_ARGUMENT_NAMES) {
        if (obj.has(name)) {
            return ConversationHistoryRequest.Invalid(
                "this tool always reads the conversation it is called from; " +
                    "drop the '$name' argument (rewriting it is not needed — just re-issue without it)",
            )
        }
    }
    val query = obj.optString("query").trim().takeIf { it.isNotEmpty() }
    val cursor = if (obj.has("cursor") && !obj.isNull("cursor")) {
        val raw = obj.opt("cursor")
        val n = when (raw) {
            // A fractional cursor (1.7) is a model error, not a request to page
            // from 1: truncating it would silently land somewhere arbitrary.
            is Number -> raw.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
        if (n == null || n < 0) return ConversationHistoryRequest.Invalid("cursor must be a non-negative integer")
        n
    } else {
        null
    }
    val maxChars = intArg(obj, "max_chars", ConversationHistoryContract.DEFAULT_MAX_CHARS)
        ?: return ConversationHistoryRequest.Invalid("max_chars must be an integer")
    val maxItems = intArg(obj, "max_items", ConversationHistoryContract.DEFAULT_MAX_ITEMS)
        ?: return ConversationHistoryRequest.Invalid("max_items must be an integer")
    return ConversationHistoryRequest.Ok(
        ConversationHistoryArgs(
            query = query,
            cursor = cursor,
            maxChars = maxChars.clampTo(ConversationHistoryContract.MIN_MAX_CHARS, ConversationHistoryContract.MAX_MAX_CHARS),
            maxItems = maxItems.clampTo(ConversationHistoryContract.MIN_MAX_ITEMS, ConversationHistoryContract.MAX_MAX_ITEMS),
        ),
    )
}

private fun intArg(obj: JSONObject, name: String, default: Int): Int? {
    if (!obj.has(name) || obj.isNull(name)) return default
    val raw = obj.opt(name)
    return when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    }
}

/**
 * Renders a page of the transcript.
 *
 * Paging runs **backwards through time**: with no cursor the newest page comes
 * back (that is what "what did we just say" asks for), and [TranscriptPage.nextCursor]
 * addresses the page before it. Lines inside a page are ordered oldest → newest
 * so the model reads them in conversation order.
 */
fun buildTranscriptPage(
    rows: List<TranscriptRow>,
    args: ConversationHistoryArgs,
    sessionLabel: String,
): TranscriptPage {
    val lines = rows.sortedBy { it.index }.map { renderTranscriptLine(it) }
    val matched = args.query?.let { q -> lines.filter { it.fullText.contains(q, ignoreCase = true) } } ?: lines
    val windowed = args.cursor?.let { c -> matched.filter { it.index < c } } ?: matched

    // Walk backwards from the newest end, taking lines until a budget runs out.
    val page = ArrayList<TranscriptLine>()
    var used = 0
    for (line in windowed.asReversed()) {
        if (page.size >= args.maxItems) break
        val cost = line.display.length + 1
        if (page.isNotEmpty() && used + cost > args.maxChars) break
        used += cost
        page.add(line)
    }
    page.reverse()

    val oldestInPage = page.firstOrNull()?.index
    val nextCursor = if (oldestInPage != null && matched.any { it.index < oldestInPage }) oldestInPage else null

    val sb = StringBuilder()
    sb.append("conversation_history · session ").append(sessionLabel)
        .append(" · ").append(rows.size).append(" messages")
    if (args.query != null) sb.append(" · ").append(matched.size).append(" match \"").append(args.query).append('"')
    when {
        rows.isEmpty() -> sb.append("\nno messages in this conversation yet.")
        matched.isEmpty() -> sb.append("\nno message matches \"").append(args.query).append("\" (")
            .append(rows.size).append(" messages searched).")
        page.isEmpty() -> sb.append("\nno messages older than index ").append(args.cursor)
    }
    if (page.isNotEmpty()) {
        sb.append("\nshowing ").append(page.size).append(" (#").append(page.first().index)
            .append("–#").append(page.last().index).append(", oldest→newest)")
        sb.append(" · ").append(used).append(" of ").append(args.maxChars).append(" chars")
        if (nextCursor != null) {
            sb.append(" · next_cursor=").append(nextCursor).append(" for older messages")
        } else {
            sb.append(" · no older messages")
        }
    }

    return TranscriptPage(
        header = sb.toString(),
        lines = page,
        nextCursor = nextCursor,
    )
}

/**
 * Renders one line for the model: a query hit shows a snippet around the match
 * (the head of a long tool output would otherwise hide the very text searched
 * for), a plain read shows the head.
 */
private fun TranscriptLine.forModel(query: String?): String {
    val body = if (query != null) querySnippet(this, query) else display
    return "[#${index} ${role}] ${body.replace('\n', ' ')}"
}

/**
 * `conversation_history` — reads back the persisted transcript of the session
 * the caller is bound to.
 *
 * [loadRows] is injected (rather than the executor touching Room) so the whole
 * tool path — argument refusal, query, paging, redaction, empty session — is
 * reachable from a JVM test.
 *
 * Refusals are `success = false` with the reason spelled out, never a silent
 * fallback to the active session: a call that named a different conversation
 * and got this one's messages would look like a successful read.
 */
internal suspend fun executeConversationHistoryTool(
    argsJson: String,
    sessionId: String,
    loadRows: suspend (String) -> List<TranscriptRow>,
): ToolExecutionResult {
    val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title") }.getOrDefault("")
    return when (val request = parseConversationHistoryRequest(argsJson)) {
        is ConversationHistoryRequest.Invalid -> ToolExecutionResult(
            output = "Error: ${request.message}",
            success = false,
            toolTitle = toolTitle,
        )
        is ConversationHistoryRequest.Ok -> {
            val rows = runCatching { loadRows(sessionId) }.getOrElse { e ->
                return ToolExecutionResult(
                    output = "Error: could not read this conversation's history: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                    success = false,
                    toolTitle = toolTitle,
                )
            }
            val page = buildTranscriptPage(rows, request.args, sessionLabel = sessionId.take(8))
            val body = renderTranscriptForModel(page, request.args.query)
            // Same redaction the shell tool applies to its output: a secret that
            // was pasted into a message must stay masked here too.
            // redactIfEnabled appends its own reminder when it masks something,
            // so the count is not reported twice.
            val (redacted, _) = com.rikkaminis.app.data.EnvVarRedactor.redactIfEnabled(body)
            ToolExecutionResult(
                output = redacted,
                success = true,
                toolTitle = toolTitle,
            )
        }
    }
}

/** Full rendered tool output for a page. */
fun renderTranscriptForModel(page: TranscriptPage, query: String?): String =
    (listOf(page.header) + page.lines.map { it.forModel(query) }).joinToString("\n")

/** Tool definition, added to the canonical list in `AgentTools`/`AgentTools.kt`. */
fun conversationHistoryDefinition(): AgentToolDefinition = AgentToolDefinition(
    name = ConversationHistoryContract.NAME,
    description = "Read back the previous messages of THIS conversation, including " +
        "turns whose details were dropped from your context by compaction. Use it when the " +
        "user refers to something you no longer have: \"like we discussed\", \"the bug from " +
        "earlier\", \"that command you ran\". Pass `query` to search for a keyword (matches " +
        "inside tool outputs too) or `cursor` to page further back. The conversation is " +
        "chosen by the runtime — there is no session parameter and a call that passes one is " +
        "refused. This reads history; it does not search memory files (use memory_get).",
    parameters = linkedMapOf(
        "tool_title" to AgentToolParam(
            type = "string",
            description = "Short present-tense label for this call, in the user's language " +
                "(e.g. \"remembering earlier turns\", \"查找前面提到的配置\"). Shown in the UI.",
        ),
        "query" to AgentToolParam(
            type = "string",
            description = "Case-insensitive substring to search for across the whole " +
                "conversation. Omit to simply read the most recent messages.",
        ),
        "cursor" to AgentToolParam(
            type = "integer",
            description = "Message index to read backwards from (exclusive): pass the " +
                "next_cursor of a previous call to get older messages.",
        ),
        "max_chars" to AgentToolParam(
            type = "integer",
            description = "Roughly how many characters of transcript to return " +
                "(default ${ConversationHistoryContract.DEFAULT_MAX_CHARS}, " +
                "max ${ConversationHistoryContract.MAX_MAX_CHARS}).",
        ),
        "max_items" to AgentToolParam(
            type = "integer",
            description = "Maximum number of messages to return " +
                "(default ${ConversationHistoryContract.DEFAULT_MAX_ITEMS}, " +
                "max ${ConversationHistoryContract.MAX_MAX_ITEMS}).",
        ),
    ),
    required = listOf("tool_title"),
    propertyOrdering = listOf("tool_title", "query", "cursor", "max_chars", "max_items"),
)
