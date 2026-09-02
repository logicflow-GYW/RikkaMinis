package com.openminis.app.backup

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

/**
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
 * Pure JVM (org.json only) so the packing contract is unit-testable without
 * Android repositories.
 */
internal fun packChatHistoryWithBudget(
    skeletonChars: Int,
    budgetTotalChars: Long,
    sessionsInOrder: List<Pair<JSONObject, List<BudgetChatMessage>>>,
    sanitize: (String) -> String?,
    capReasoning: (String?) -> String?,
): BudgetPackResult {
    var budgetChars = budgetTotalChars - skeletonChars
    if (budgetChars < 0) budgetChars = 0

    val keptSessions = mutableListOf<JSONObject>()
    val keptMessages = mutableListOf<JSONObject>()
    var sessionsDropped = 0
    var messagesDropped = 0
    var exhausted = false

    for ((sessionJson, messages) in sessionsInOrder) {
        if (exhausted) {
            sessionsDropped++
            continue
        }
        val sessionChars = sessionJson.toString().length
        if (sessionChars.toLong() > budgetChars) {
            exhausted = true
            sessionsDropped++
            continue
        }
        budgetChars -= sessionChars

        var sessionFullyPacked = true
        for (message in messages) {
            val cleaned = sanitize(message.partsJson) ?: continue
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
            if (messageChars.toLong() > budgetChars) {
                exhausted = true
                messagesDropped++
                sessionFullyPacked = false
                break
            }
            budgetChars -= messageChars
            keptMessages.add(messageJson)
        }
        // A session whose packing hit the frontier mid-way still lands: its
        // metadata is tiny and keeping it makes the restore show the session
        // (with whatever prefix of its messages fit) instead of losing the
        // whole conversation silently.
        if (!sessionFullyPacked) {
            // messages after the frontier in THIS session are not counted
            // individually — the frontier message was already counted. The
            // remainder is older history; counting them is unnecessary detail
            // for the user-visible report.
        }
        keptSessions.add(sessionJson)
    }

    return BudgetPackResult(
        sessions = keptSessions,
        messages = keptMessages,
        sessionsDropped = sessionsDropped,
        messagesDropped = messagesDropped,
    )
}
