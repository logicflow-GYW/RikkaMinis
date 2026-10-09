package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-backup-byte-budget] One chat message, budget-packing input. Kept as a
 * plain data class (not MessageEntity) so the packing algorithm is testable
 * on the JVM without Room.
 */
internal data class BudgetChatMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val partsJson: String,
    val createdAt: Long,
    val sortOrder: Int,
    val reasoningContent: String? = null,
)

/**
 * [T-backup-byte-budget] Result of budget-packing chat history.
 *
 * @property sessions kept session JSON objects, in input order.
 * @property messages kept message JSON objects, newest-first packing order.
 * @property sessionsDropped sessions whose metadata no longer fit (plus all
 *   sessions skipped after the budget was exhausted — they are older, since
 *   the DAO returns sessions ordered by updatedAt DESC).
 * @property messagesDropped eligible messages that did not fit.
 */
internal data class BudgetPackResult(
    val sessions: List<JSONObject>,
    val messages: List<JSONObject>,
    val sessionsDropped: Int,
    val messagesDropped: Int,
)

/* ── Budget packing contract ───────────────────────────────────────────
 * [T-backup-byte-budget] Linear byte-budget packing for chat history in a
 * backup document.
 *
 * Design: the serialized non-chat skeleton (config / providers / skills /
 * memory) is measured once; whatever room is left under
 * [ConfigBackup.MAX_PAYLOAD_BYTES] minus [ConfigBackup.SAFETY_MARGIN_BYTES]
 * is handed to chat history. Eligible messages are then packed
 * **newest-first** — sessions arrive ordered by updatedAt DESC (the DAO's
 * contract) and each session's messages arrive newest-first (messagesLast's
 * contract) — until the budget runs out. Granularity is a single message:
 * there is no "days" ladder that would drop 30 days at once when the data
 * is lumpy (a three-day debugging sprint can outweigh a quiet month).
 *
 * Once one message does not fit, packing STOPS entirely rather than skipping
 * it and carrying a hole: everything after the frontier is older, and a
 * backup that silently omits the middle of a conversation restores into a
 * lying transcript. Better to carry a prefix that ends at a clean frontier
 * and report the cut via [BudgetPackResult.messagesDropped].
 *
 * Two drivers share the same frontier step — [sessionMetadataFits] for the
 * metadata charge, [packMessagesIntoBudget] for the messages (eager goes
 * through the [packSessionIntoBudget] convenience wrapper):
 * [packChatHistoryWithBudget] for fully materialized input, and
 * [packChatHistoryWithBudgetLazily] for production export, where message
 * bodies are pulled per session and loading stops at the frontier.
 */

/**
 * [T-backup-lazy-chat-load] Running state of one budget pack. Extracted so the
 * frontier arithmetic has exactly ONE implementation shared by both drivers
 * (the eager list API below and the lazy per-session API used by
 * [ConfigBackup.buildSections]).
 */
internal class ChatBudgetState(
    /** Chars still available for chat. Decremented as elements land. */
    var budgetChars: Long,
    /** Set once an element did not fit: packing STOPS, nothing after the
     *  frontier is considered. */
    var exhausted: Boolean = false,
    /** Eligible messages that did not fit (the frontier message counted once;
     *  older stragglers in the same session are not counted — see below). */
    var messagesDropped: Int = 0,
)

/**
 * Metadata-only frontier step, shared by both drivers so the charge
 * arithmetic stays in ONE place. Charges [sessionJson]'s serialized size
 * against [ChatBudgetState.budgetChars] when it fits; sets
 * [ChatBudgetState.exhausted] and returns false when it does not.
 *
 * [T-backup-lazy-chat-load] The lazy driver MUST run this BEFORE pulling a
 * session's message bodies: [packSessionIntoBudget] takes the body list as
 * a plain parameter, so calling it directly evaluates `messagesAt(index)`
 * at the call site — a session whose metadata was about to be dropped
 * still loaded every message body first (CI run 37883189847 caught exactly
 * that: "no message body may be read expected:<[]> but was:<[0]>").
 */
internal fun sessionMetadataFits(
    state: ChatBudgetState,
    sessionJson: JSONObject,
): Boolean {
    val sessionChars = sessionJson.toString().length
    if (sessionChars.toLong() > state.budgetChars) {
        state.exhausted = true
        return false
    }
    state.budgetChars -= sessionChars
    return true
}

/**
 * Pack a session's newest-first messages into [state], appending to
 * [keptSessions] / [keptMessages]. The session metadata must ALREADY be
 * charged (see [sessionMetadataFits]). Always lands the session: its
 * metadata is tiny and keeping it makes the restore show the session (with
 * whatever prefix of its messages fit) instead of losing the whole
 * conversation silently. Messages after the frontier in THIS session are
 * not counted individually — the frontier message was already counted;
 * the remainder is older history.
 */
internal fun packMessagesIntoBudget(
    state: ChatBudgetState,
    sessionJson: JSONObject,
    messages: List<BudgetChatMessage>,
    keptSessions: MutableList<JSONObject>,
    keptMessages: MutableList<JSONObject>,
    sanitize: (String) -> String?,
    capReasoning: (String?) -> String?,
) {
    for (message in messages) {
        val cleaned = sanitize(message.partsJson)
            ?: "[{\"type\":\"text\",\"value\":\"[media message elided]\"}]"
        val messageJson = JSONObject().apply {
            put("id", message.id)
            put("sessionId", message.sessionId)
            put("role", message.role)
            put("partsJson", cleaned)
            put("createdAt", message.createdAt)
            put("sortOrder", message.sortOrder)
            put("reasoningContent", capReasoning(message.reasoningContent))
        }
        val messageChars = messageJson.toString().length
        if (messageChars.toLong() > state.budgetChars) {
            state.exhausted = true
            state.messagesDropped++
            break
        }
        state.budgetChars -= messageChars
        keptMessages.add(messageJson)
    }
    keptSessions.add(sessionJson)
}

/**
 * Pack ONE session (metadata + its newest-first messages) into [state],
 * appending to [keptSessions] / [keptMessages]. Eager-driver entry point.
 *
 * Returns false when even the session metadata did not fit (the caller counts
 * it as dropped); true when the session landed, whether or not its messages
 * ran into the frontier.
 */
internal fun packSessionIntoBudget(
    state: ChatBudgetState,
    sessionJson: JSONObject,
    messages: List<BudgetChatMessage>,
    keptSessions: MutableList<JSONObject>,
    keptMessages: MutableList<JSONObject>,
    sanitize: (String) -> String?,
    capReasoning: (String?) -> String?,
): Boolean {
    if (!sessionMetadataFits(state, sessionJson)) return false
    packMessagesIntoBudget(
        state, sessionJson, messages, keptSessions, keptMessages,
        sanitize, capReasoning,
    )
    return true
}

private fun newChatBudgetState(
    skeletonChars: Int,
    budgetTotalChars: Long,
): ChatBudgetState {
    val remaining = budgetTotalChars - skeletonChars
    return ChatBudgetState(budgetChars = if (remaining < 0) 0 else remaining)
}

/**
 * [T-backup-byte-budget] Eager driver: every session's messages are already
 * materialized in [sessionsInOrder]. Use this when the bodies are in hand
 * (JVM tests, callers with a tiny corpus). Production export uses
 * [packChatHistoryWithBudgetLazily], which pulls one session at a time —
 * both run the same metadata gate + message-packing step.
 */
internal fun packChatHistoryWithBudget(
    skeletonChars: Int,
    budgetTotalChars: Long,
    sessionsInOrder: List<Pair<JSONObject, List<BudgetChatMessage>>>,
    sanitize: (String) -> String?,
    capReasoning: (String?) -> String?,
): BudgetPackResult {
    val state = newChatBudgetState(skeletonChars, budgetTotalChars)
    val keptSessions = mutableListOf<JSONObject>()
    val keptMessages = mutableListOf<JSONObject>()
    var sessionsDropped = 0

    for ((sessionJson, messages) in sessionsInOrder) {
        if (state.exhausted) {
            sessionsDropped++
            continue
        }
        if (!packSessionIntoBudget(
                state, sessionJson, messages, keptSessions, keptMessages,
                sanitize, capReasoning,
            )
        ) {
            sessionsDropped++
        }
    }

    return BudgetPackResult(
        sessions = keptSessions,
        messages = keptMessages,
        sessionsDropped = sessionsDropped,
        messagesDropped = state.messagesDropped,
    )
}

/**
 * [T-backup-lazy-chat-load] Lazy driver: session metadata arrives up front
 * (cheap — it is one row per session), message bodies are pulled from
 * [messagesAt] one session at a time, and ONLY while the budget is still
 * open. Once the frontier is reached the remaining sessions are counted as
 * dropped without ever being loaded.
 *
 * This is the memory fix for [T-backup-streaming-export]'s blind spot: the
 * streaming writer removed the final document String, but the assembly step
 * still materialized every eligible message of the window (up to
 * MAX_CHAT_MESSAGES_PER_SESSION per session, raw parts_json included — the
 * per-part caps only apply to the OUTPUT) before the budget could trim
 * anything. Peak heap therefore grew with the *whole* window, not with the
 * backup, and once history crossed the line every chat-bearing export died
 * with `Failed to allocate … <1% of heap free after GC` (measured 2026-10-09,
 * both the local file and the WebDAV upload). With bodies pulled per session
 * and an early stop, peak heap is bounded by one session plus the kept
 * (budget-sized) output.
 */
internal suspend fun packChatHistoryWithBudgetLazily(
    skeletonChars: Int,
    budgetTotalChars: Long,
    sessionJsons: List<JSONObject>,
    messagesAt: suspend (Int) -> List<BudgetChatMessage>,
    sanitize: (String) -> String?,
    capReasoning: (String?) -> String?,
): BudgetPackResult {
    val state = newChatBudgetState(skeletonChars, budgetTotalChars)
    val keptSessions = mutableListOf<JSONObject>()
    val keptMessages = mutableListOf<JSONObject>()
    var sessionsDropped = 0

    for (index in sessionJsons.indices) {
        if (state.exhausted) {
            sessionsDropped++
            continue
        }
        // [T-backup-lazy-chat-load] Metadata gate FIRST. packSessionIntoBudget
        // takes the body list as a plain parameter, so routing the lazy driver
        // through it evaluated messagesAt(index) at the call site — a session
        // whose metadata was about to be dropped still loaded every body
        // first (caught by the zero-budget test, CI run 37883189847).
        if (!sessionMetadataFits(state, sessionJsons[index])) {
            sessionsDropped++
            continue
        }
        packMessagesIntoBudget(
            state, sessionJsons[index], messagesAt(index),
            keptSessions, keptMessages, sanitize, capReasoning,
        )
    }

    return BudgetPackResult(
        sessions = keptSessions,
        messages = keptMessages,
        sessionsDropped = sessionsDropped,
        messagesDropped = state.messagesDropped,
    )
}
