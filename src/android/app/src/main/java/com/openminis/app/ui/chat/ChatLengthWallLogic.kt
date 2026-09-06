package com.openminis.app.ui.chat

import kotlin.math.ceil

/**
 * Pure helpers for the [T-length-wall-continue] path in ChatViewModel.runAgentLoop.
 *
 * Background: when `finish_reason == "length"`, the loop keeps the truncated
 * partial reply in agentHistory/accumulatedText and issues a fresh API call so
 * the model "picks up where it cut off". Two field-observed failure modes of
 * that design live here, as pure functions (FE-4 route-A style: extract the
 * logic, keep it JVM-testable, never touch UI state):
 *
 *  1. **Seam duplication** — models frequently do NOT resume at the exact cut
 *     point; they back up to an earlier semantic anchor and re-emit a phrase
 *     they already produced. Both halves are kept, so the visible reply ends
 *     up with a mid-sentence repetition like:
 *       `…已经站在一个，是因为它确实已经站在一个一个比较高的位置了…`
 *     [mergeLengthWallSeam] detects the overlap (longest suffix of the
 *     truncated text that is also a prefix of the continuation) and trims it
 *     from the continuation before it is folded into accumulatedText.
 *
 *  2. **No continuation instruction** — the follow-up call presents the
 *     truncated text as the model's own partial reply with no instruction, so
 *     the model treats it as context and rewrites freely. [lengthWallReminder]
 *     builds the `<system-reminder>` user message (same pattern as resume()'s
 *     stop-continue reminder) that tells the model to continue from the last
 *     character and never repeat already-output text.
 *
 * Both functions are deterministic and side-effect free.
 */

/** Minimum seam-overlap length (chars) considered a real duplication.
 *  Shorter overlaps are legitimate language patterns (e.g. "。" + "，")
 *  and must not be trimmed. Mirrors [MINIMUM_STREAMING_OVERLAP_LENGTH] in
 *  StreamingMarkdownText.kt (=3). The original value of 6 was sized for
 *  conservative joins but field data (user-reported "业界几乎" 4-char and
 *  "春天来了。" 5-char repeats, tokenrhythm deepseek/glm/kimi relay) showed
 *  models back up and re-emit SHORT phrases that 6 could not catch — so 3
 *  aligns with the streaming dedup threshold and still leaves 1-2 char
 *  punctuation joins untouched. */
const val LENGTH_WALL_MIN_SEAM_OVERLAP = 3

/** Hard cap for the overlap scan (performance: the scan is O(n*m) worst case
 *  via indexOf; capped so a pathological megabyte turn cannot stall the loop). */
private const val LENGTH_WALL_SEAM_SCAN_CAP = 8192

/**
 * Longest suffix of [truncated] that is also a prefix of [continuation].
 *
 * Returns the overlap length in characters, 0 when there is none. The scan is
 * capped at [LENGTH_WALL_SEAM_SCAN_CAP] characters from each end.
 *
 * Same algorithm family as StreamingMarkdownText's private
 * longestSuffixPrefixOverlap, lifted here as a public top-level pure function
 * so the length-wall path (and its tests) can use it without touching the
 * composable file's visibility surface.
 */
fun lengthWallSeamOverlap(truncated: String, continuation: String): Int {
    if (truncated.isEmpty() || continuation.isEmpty()) return 0
    val maxOverlap = minOf(
        truncated.length,
        continuation.length,
        LENGTH_WALL_SEAM_SCAN_CAP,
    )
    // Walk from the longest candidate down; the first hit is the answer.
    var length = maxOverlap
    while (length > 0) {
        if (continuation.startsWith(truncated.substring(truncated.length - length))) {
            return length
        }
        length--
    }
    return 0
}

/**
 * [T-length-wall-seam-punct] Full/half-width punctuation + whitespace that
 * can open a length-wall continuation as "restart the sentence" residue.
 * Models frequently back up to an earlier anchor and re-emit a phrase they
 * already produced, prefixed with a joining comma / full stop — e.g. the
 * field symptom "…业界几乎，主动走出了一条业界几乎…". That leading mark
 * sits BEFORE the repeated phrase, so the raw suffix-prefix scan
 * ([lengthWallSeamOverlap]) compares against a continuation whose head is
 * punctuation and collapses to 0, leaving the duplication intact.
 * Stripping the leading run first lets the scan find the real overlap.
 */
private val LEADING_PUNCTUATION_CHARS = setOf(
    '，', '。', '、', '；', '：', '！', '？', '…', '—', '·', '～',
    '「', '」', '『', '』', '（', '）', '【', '】', '《', '》',
    '“', '”', '‘', '’', '"', '\'',
    '.', ',', ';', ':', '!', '?', '(', ')', '[', ']', '<', '>', '-',
    ' ', '\t', '\n', '\r',
)

/**
 * Strip the leading run of punctuation/whitespace from [text] (the
 * continuation head). Returns the remainder after the run; the empty string
 * when [text] is entirely punctuation. Leaves [text] untouched when it opens
 * with a non-punctuation character (zero-allocation fast path).
 */
fun stripLeadingPunctuation(text: String): String {
    var i = 0
    while (i < text.length && text[i] in LEADING_PUNCTUATION_CHARS) i++
    return if (i == 0) text else text.substring(i)
}

/**
 * Merge a length-wall continuation into the truncated text, trimming any
 * seam overlap so no already-output phrase survives twice.
 *
 * Contract:
 *  - Overlap >= [LENGTH_WALL_MIN_SEAM_OVERLAP] chars → trim it from the head
 *    of [continuation] and concatenate. The truncated text is NEVER modified
 *    (it is already in agentHistory and on screen).
 *  - Overlap below the threshold → plain concatenation (the join is assumed
 *    to be legitimate language, not duplication).
 *  - [continuation] empty → return [truncated] unchanged.
 *
 * @return the merged text to use as the accumulated reply text.
 */
fun mergeLengthWallSeam(truncated: String, continuation: String): String {
    if (continuation.isEmpty()) return truncated
    if (truncated.isEmpty()) return continuation
    // [T-length-wall-seam-punct] Strip the continuation's leading
    // punctuation/whitespace run BEFORE the overlap scan: models that back
    // up to an earlier anchor re-emit the repeated phrase behind a joining
    // mark (e.g. "…业界几乎，主动走出了一条业界几乎…"), and a raw scan
    // compares against that mark and misses the real overlap entirely. The
    // stripped head is used ONLY for overlap detection; the cut is then
    // applied to the ORIGINAL continuation (leading marks + overlap) so the
    // mark is removed exactly when it fronts a duplicated phrase and kept
    // otherwise (it is legitimate sentence flow on a non-repeat join).
    val stripped = stripLeadingPunctuation(continuation)
    if (stripped.isEmpty()) return truncated + continuation
    val overlap = lengthWallSeamOverlap(truncated, stripped)
    if (overlap >= LENGTH_WALL_MIN_SEAM_OVERLAP) {
        val leadingMarks = continuation.length - stripped.length
        val cut = leadingMarks + overlap
        return truncated + continuation.substring(cut)
    }
    return truncated + continuation
}

/**
 * Build the `<system-reminder>` continuation instruction injected as a
 * synthetic USER message after a truncated (finish_reason="length") turn.
 *
 * Same delivery pattern as resume()'s stop-continue reminder: a user-role
 * message whose single Text part carries the reminder. The reminder tail
 * includes the last few characters of the truncated reply so the model has a
 * concrete anchor of "where I left off" — this measurably reduces the
 * back-up-and-repeat behavior (see test file for the regression cases).
 *
 * The text is intentionally English: system-reminder payloads elsewhere in
 * this codebase are English, and models follow them most reliably in English.
 */
fun lengthWallReminder(truncatedTail: String): String =
    "<system-reminder>Your previous reply was cut off mid-sentence by the output token limit. " +
        "Continue the reply starting from the exact character after: \"$truncatedTail\". " +
        "Do NOT repeat any text you have already output — do not restart the sentence, " +
        "do not re-emit the phrase before the cut point. Continue seamlessly as if writing " +
        "one continuous reply.</system-reminder>"

/** Fragment length below which the repetition check doesn't run: short
 *  truncations trivially contain repeated tokens and are legitimately
 *  continued. Mirrors Hermes repetition_guard.MIN_FRAGMENT_LENGTH. */
const val REPETITION_MIN_FRAGMENT_LENGTH = 400

/** Exact-repeat window; far beyond ordinary phrasing reuse (citations,
 *  headings, similar code). Mirrors Hermes `_REPEAT_WINDOW` (60). */
private const val REPETITION_REPEAT_WINDOW = 60

/** A window repeating at least this often is a repetition signal even for
 *  short fragments. Mirrors Hermes `_MIN_REPEAT_COUNT` (5). */
private const val REPETITION_MIN_REPEAT_COUNT = 5

/** "Repetition-dominated" = repeated windows cover at least this fraction of
 *  the fragment. Mirrors Hermes `_DOMINANCE_RATIO` (0.5). */
private const val REPETITION_DOMINANCE_RATIO = 0.5

/**
 * [feat/hermes-tier1] True when [text] shows the signature of a model
 * repetition loop: a single 60+ char substring recurring often enough to
 * cover at least half the fragment (or one normalized line repeated enough
 * to cover half of it). Ported from Hermes `agent/repetition_guard.py`
 * (Nous Research) — a model in a degenerate repetition loop can spend its
 * ENTIRE output budget echoing one fragment, and continuing such a fragment
 * via the length-wall path only stitches more repeated text into the reply.
 *
 * Deliberately conservative and fail-open: short fragments, non-repeating
 * text, and any unusual-but-legitimate shape (citations, headings, code)
 * return false so continuation proceeds as before. mergeLengthWallSeam
 * stays as the belt-and-braces for smaller repeats; this guard only stops
 * the WHOLESALE loops that no seam trim can rescue.
 *
 * Deterministic and side-effect free (FE-4 route-A pure function).
 */
fun isRepetitionDominated(text: String): Boolean {
    val n = text.length
    if (n < REPETITION_MIN_FRAGMENT_LENGTH) return false

    // Fast path: one normalized line duplicated enough to cover half the
    // fragment — the common echo shape.
    val lineCounts = HashMap<String, Int>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        lineCounts[line] = (lineCounts[line] ?: 0) + 1
    }
    for ((line, count) in lineCounts) {
        if (count >= REPETITION_MIN_REPEAT_COUNT &&
            count.toLong() * line.length >= n.toLong() * REPETITION_DOMINANCE_RATIO
        ) {
            return true
        }
    }

    // General path: fixed-size windows sliding one char at a time, catching
    // loops that don't align to line boundaries. A window must appear
    // `needed` times to cover >= DOMINANCE_RATIO (and >= MIN_REPEAT_COUNT).
    val window = REPETITION_REPEAT_WINDOW
    val needed = maxOf(
        REPETITION_MIN_REPEAT_COUNT,
        ceil(n * REPETITION_DOMINANCE_RATIO / window).toInt(),
    )
    val windowCounts = HashMap<String, Int>()
    var i = 0
    while (i + window <= n) {
        val key = text.substring(i, i + window)
        val next = (windowCounts[key] ?: 0) + 1
        if (next >= needed) return true
        windowCounts[key] = next
        i++
    }
    return false
}
