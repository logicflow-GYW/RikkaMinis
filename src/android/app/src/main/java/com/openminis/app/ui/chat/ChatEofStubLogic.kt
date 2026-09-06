package com.openminis.app.ui.chat

/**
 * [fix/eof-stub-continuation] Pure helpers for the EOF-truncated-stream
 * recovery path in AgentLoopEngine.
 *
 * Background: when a relay drops the SSE connection mid-answer WITHOUT a
 * server finish_reason (EOF / connection reset), the reply is truncated
 * mid-sentence but the content up to the drop is perfectly good. The legacy
 * behavior (T-truncated-stream-retry) DELETED the partial turn and
 * regenerated from scratch — wasting every already-streamed token, and the
 * regeneration frequently re-emitted a different opening (screen-level
 * duplication). A SECOND EOF then broke silently: the user saw a mid-sentence
 * stop with no hint, and had to type "继续" by hand.
 *
 * The Hermes-shaped fix (network-stub continuation, agent/conversation_loop.py):
 * KEEP the partial text as the model's own last turn and append a synthetic
 * user-role <system-reminder> that anchors the exact cut point and orders a
 * seamless continuation. This mirrors the length-wall reminder machinery
 * (lengthWallReminder) — same delivery pattern, stacking guard, and
 * seam-dedup interplay.
 *
 * Both functions are deterministic and side-effect free.
 */

/**
 * Build the `<system-reminder>` continuation instruction injected after an
 * EOF-truncated turn (stream ended with NO finish_reason).
 *
 * Textually distinct from [lengthWallReminder] (output-token wall) so the
 * stacking guard in the engine can tell the two synthetic reminders apart
 * and so logs/DB rows are greppable by failure class. The tail anchor gives
 * the model a concrete "where I left off" marker — same rationale as
 * lengthWallReminder, measurably reduces back-up-and-repeat.
 */
fun eofStubReminder(truncatedTail: String): String =
    "<system-reminder>Your previous response was cut off by a network error mid-stream. " +
        "Continue exactly where you left off, starting from the character after: \"$truncatedTail\". " +
        "Do not restart or repeat prior text. Finish the answer directly.</system-reminder>"

/**
 * True when [text] ends mid-content — i.e. a truncation cut it at an
 * arbitrary character rather than the model finishing a sentence/paragraph.
 *
 * Used ONLY as a diagnostic signal in logs (did the EOF land mid-sentence or
 * at a lucky boundary?): the continuation decision itself is made purely on
 * turnTruncated, never on this. A false negative (text happens to end at a
 * terminator) costs nothing; a false positive (reply legitimately ends with
 * e.g. a URL) never influences behavior because this does not gate anything.
 */
fun looksLikeMidSentenceCut(text: String): Boolean {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty()) return false
    val last = trimmed.last()
    val terminators = "。．！？!?.…」』”）\"'`*_=——-~)]:；;，,]".toSet() + '\n' + '\r'
    return last !in terminators
}
