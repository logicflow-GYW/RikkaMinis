package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/**
 * [feat/compact-pin-v0-1005] Compact pin v0 — user verbatim text is pinned
 * out of the LLM rewrite path.
 *
 * Experiment D (compact-exp-1004 `REPORT_DE.md`) proved "covering decay":
 * a ground-truth constraint stated once in R1 is 0/10 recalled in R2 —
 * later same-topic turns overwrite it (recency wins), and no summary-side
 * prompt clause (MUST PRESERVE / SUPERSEDED) changes that, because every
 * summary pass rewrites the whole text through the LLM. Pinning turns
 * "user said" from probabilistic retention into deterministic retention:
 *
 *  1. [extractPinnedUserMessages] mechanically extracts verbatim user text
 *     from the folded region (same range as the fold — not full history);
 *  2. the pinned payload NEVER enters an LLM call: the rewrite chain strips
 *     any existing pinned block from the summary before building the LLM
 *     input ([stripPinnedSection]) and re-appends after the LLM returns
 *     ([appendPinnedSection]);
 *  3. the block is embedded INSIDE the summary string (zero DB/marker
 *     migration) so it rides the existing `<context-summary>` injection
 *     (effectiveAgentHistory) untouched — the model sees the verbatim user
 *     text every turn.
 *
 * Upgrade trigger (task brief §2): if round-trip tests catch the embedded
 * block being rewritten by a later LLM pass and stripping proves
 * unreliable, the block moves to a dedicated DB column — that is a separate
 * task; this file stays the single home of the pin format until then.
 */

/** Token cap for the pinned payload ([ContextCompactor.estimateTokens] same口径). */
const val PINNED_USER_MESSAGES_TOKEN_LIMIT: Long = 8000L

private const val PINNED_OPEN = "<pinned-user-messages>"
private const val PINNED_CLOSE = "</pinned-user-messages>"

// Non-greedy so a hallucinated second CLOSE inside the block cannot swallow
// beyond the first real block; [\s\S] spans newlines (JS-style dot would not).
// Group 1 = the carried payload (header + pinned user messages).
private val PINNED_BLOCK_REGEX =
    Regex("<pinned-user-messages>([\\s\\S]*?)</pinned-user-messages>")

private const val PINNED_HEADER =
    "Verbatim user messages from the earlier (compacted) conversation — " +
        "carried through unchanged, never rewritten by any summary pass."

/**
 * Extract verbatim user text from the folded region [messages], in
 * chronological order.
 *
 * Included: USER messages' [LLMMessage.content] plus any structured
 * [AgentContentPart.Text] parts (both are user-authored text, mirroring
 * what [com.rikkaminis.app.ui.chat.buildConversationTextForSummary] renders
 * as user text), de-duplicated WITHIN each message so the double-filled
 * (content == Text part) shape pins once — see the collection comment below.
 * Excluded: assistant messages and tool-result carriers
 * (USER messages whose contentParts are all ToolResult) — tools/assistant
 * content is the summarizer's job, pinning is only for what the user said.
 *
 * Token cap: [tokenLimit] via [ContextCompactor.estimateTokens]. Under the
 * cap the full set is returned. Over the cap, the most recent messages that
 * fit are kept plus — always — the earliest message (task brief §2
 * 「最近 N 条 + 最早 1 条」): the earliest user message most likely carries
 * the original task statement, which is exactly what the decay experiment
 * showed gets overwritten. A single message larger than the whole cap is
 * still pinned alone (it cannot be split; verbatim is the point).
 *
 * Empty result → callers must leave the summary untouched (byte-identical).
 */
fun extractPinnedUserMessages(
    messages: List<LLMMessage>,
    tokenLimit: Long = PINNED_USER_MESSAGES_TOKEN_LIMIT,
): List<String> {
    val texts = ArrayList<String>()
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        if (msg.contentParts.isNotEmpty() && msg.contentParts.all { it is AgentContentPart.ToolResult }) {
            continue
        }
        // [fix/da1018-compact-pin] Same-message de-duplication. Every USER
        // message in this app is DOUBLE-FILLED: the main send path puts the
        // trimmed text in BOTH `content` and the first Text part
        // (ChatViewModel:3713-3714), and every engine-side reminder mirrors it
        // (AgentLoopEngine:2205/2713/2780/2844/3105, ChatToolExecutors:413/444,
        // EmptyTurnRecovery:90, SanitizeAgentHistory:136, ChatCompactionLogic
        // :497). Collecting both copies pinned each user line twice, which (a)
        // ate the [PINNED_USER_MESSAGES_TOKEN_LIMIT] budget at 2× the real
        // content, so the over-cap trim dropped older real messages early, and
        // (b) duplicated the text inside the summary string that rides every
        // request. Both sources are still read — parts carry text `content`
        // does not (per-image captions, `[image …]` placeholders, the
        // attached-files XML) — only EXACT duplicates within one message
        // collapse. Insertion order is preserved (content first, then parts),
        // and duplicates ACROSS messages are deliberately left alone: the pin
        // stays a faithful chronological record, not a global de-dup set. This
        // is the third site of the same family: [ContextEstimator
        // .estimateMessageTokensFull] and [buildConversationTextForSummary]
        // were fixed earlier (audit-1015-bug01b).
        val seenInMessage = LinkedHashSet<String>()
        if (msg.content.isNotBlank()) seenInMessage.add(msg.content)
        for (part in msg.contentParts) {
            if (part is AgentContentPart.Text && part.text.isNotBlank()) seenInMessage.add(part.text)
        }
        texts.addAll(seenInMessage)
    }
    if (texts.isEmpty()) return emptyList()

    val total = texts.sumOf { ContextCompactor.estimateTokens(it) }
    if (total <= tokenLimit) return texts

    // Over the cap: walk from the newest, keep what fits.
    val recent = ArrayList<String>()
    var used = 0L
    for (i in texts.indices.reversed()) {
        val t = ContextCompactor.estimateTokens(texts[i])
        if (used + t > tokenLimit) break
        recent.add(0, texts[i])
        used += t
    }
    // Earliest message is pinned unconditionally (see KDoc).
    if (recent.none { it == texts.first() }) {
        recent.add(0, texts.first())
    }
    return recent
}

/**
 * Raw inner text of an existing `<pinned-user-messages>` block inside
 * [summary], or null when there is none.
 *
 * Returns the carried payload verbatim (header + previously pinned user
 * messages), NOT parsed back into individual messages — multi-round
 * rewriting carries the block as opaque text; only the append side knows
 * the message separator. Edge newlines around the payload are trimmed so
 * repeated strip→append cycles cannot accumulate separator drift.
 */
fun pinnedSectionInner(summary: String?): String? {
    if (summary.isNullOrEmpty()) return null
    return PINNED_BLOCK_REGEX.find(summary)?.groupValues?.get(1)?.trim('\n')
}

/**
 * Remove any `<pinned-user-messages>` block from [summary].
 *
 * Idempotent: a summary with no block is returned byte-identical (the
 * rewrite chain relies on this — no-pin sessions must keep today's
 * behavior exactly). When a block is stripped, the `\n\n` separator the
 * append added is dropped with it so the base the LLM sees is the same
 * text it would have seen before pinning existed.
 */
fun stripPinnedSection(summary: String?): String? {
    if (summary.isNullOrEmpty()) return summary
    val stripped = PINNED_BLOCK_REGEX.replace(summary, "")
    if (stripped == summary) return summary // no block — byte-identical no-op
    return stripped.trimEnd()
}

/**
 * [fix/r5-c1-compact-F01] True when [summary] carries REAL summary text —
 * i.e. something survives stripping the pinned block.
 *
 * The pinned block is appended AFTER the LLM returns ([appendPinnedSection]),
 * so a "did the summarizer produce anything at all?" test placed after that
 * append can never fire while the fold region holds a single user message:
 * the block alone makes the string non-empty. Callers that read "empty" as
 * "nothing was summarised, therefore nothing may be folded" must ask THIS
 * instead of `String.isEmpty()` — otherwise an empty provider answer (content
 * filter, refusal, thinking-only reply) is persisted as a marker with no body,
 * and `effectiveAgentHistory` then trades the entire folded range for verbatim
 * quotes of the user's own words.
 *
 * Block only → false. One real sentence + block → true. Idempotent by
 * construction (delegates to [stripPinnedSection]).
 */
fun summaryHasBody(summary: String?): Boolean = !stripPinnedSection(summary).isNullOrBlank()

/**
 * A carried-vs-fresh payload pair that fits [PINNED_USER_MESSAGES_TOKEN_LIMIT].
 * See [fitPinnedPayload].
 */
internal data class FittedPinnedPayload(val carriedInner: String?, val pinned: List<String>)

/**
 * Split an opaque carried payload into its header chunk (only when the block
 * still starts with [PINNED_HEADER]) and its blank-line-separated entries.
 *
 * The block format is `header\n\nentry\n\nentry…` (see
 * [appendPinnedSection]); splitting on the "\n\n" separator is a *trimming
 * granularity* heuristic, not a parse: an entry that itself contains a blank
 * line (a pasted log, a markdown list) shows up as several chunks. That is
 * deliberate and safe — every chunk is a contiguous slice of the original
 * text, so dropping leading chunks can shorten the OLDEST kept message but
 * can never reorder, duplicate or mangle anything.
 */
internal fun splitCarriedInner(inner: String?): Pair<String?, List<String>> {
    if (inner.isNullOrBlank()) return null to emptyList()
    val chunks = inner.trim('\n').split("\n\n").filter { it.isNotBlank() }
    if (chunks.isEmpty()) return null to emptyList()
    return if (chunks.first() == PINNED_HEADER) chunks.first() to chunks.drop(1) else null to chunks
}

/**
 * Enforce [PINNED_USER_MESSAGES_TOKEN_LIMIT] over carried + fresh pins
 * **combined**.
 *
 * [fix/r3-b11-compact-F03] The budget used to exist only on the fresh side
 * ([extractPinnedUserMessages]) while [appendPinnedSection] glued
 * `carriedInner + pinned` together with no cap at all. Every compact round
 * therefore grew the block by up to another full budget — `N × 8_000` tokens
 * after N rounds, plus the single-oversized-message exemption — and the block
 * rides EVERY request inside `<context-summary>`, so a long session marched
 * itself into `PressureLevel.EXHAUSTED` with the pinned payload alone.
 *
 * Allocation over the merged chronological list (fresh pins are newer than
 * anything carried) has TWO exemptions and fills the rest newest-first:
 *
 *  1. the NEWEST entry is always kept — dropping it would delete the user's
 *     actual last paste;
 *  2. the OLDEST entry is kept whenever it still fits next to (1) —
 *     [fix/r5-c1-compact-C01] the previous pure newest-first walk dropped it on
 *     EVERY over-cap fold, which silently inverted the feature: the pin exists
 *     precisely so the original task statement survives (see the decay
 *     experiment in this file's header), and `extractPinnedUserMessages`
 *     already promises "recent N + earliest 1 unconditionally". A trimmer that
 *     honours the first half of that promise and eats the second half is the
 *     regression this rule closes;
 *  3. the middle is filled newest-first (recency wins, the middle growth is
 *     what gets dropped).
 *
 * The oldest entry is exempt from the TRIM, not from the CEILING: an oldest
 * entry that cannot fit beside the newest one yields, so the hard ceiling
 * stays `max(tokenLimit, newest entry)` rather than `tokenLimit`.
 *
 * Under budget the inputs are returned byte-identical (today's behaviour, and
 * the reason the multi-round "carried survives verbatim" tests keep passing).
 * [PINNED_HEADER] is re-attached when any carried entry survives, and dropped
 * with the whole carried payload otherwise ([appendPinnedSection] re-adds it).
 */
internal fun fitPinnedPayload(
    carriedInner: String?,
    pinned: List<String>,
    tokenLimit: Long = PINNED_USER_MESSAGES_TOKEN_LIMIT,
): FittedPinnedPayload {
    if (tokenLimit <= 0) return FittedPinnedPayload(null, emptyList())
    val fresh = pinned.filter { it.isNotBlank() }
    val (header, carriedEntries) = splitCarriedInner(carriedInner)
    if (carriedEntries.isEmpty() && fresh.isEmpty()) {
        return FittedPinnedPayload(carriedInner, pinned)
    }
    val entries = carriedEntries + fresh
    val costs = entries.map { ContextCompactor.estimateTokens(it) }
    if (costs.sum() <= tokenLimit) {
        // Under budget — hand back exactly what we were given so a no-pin or
        // small-pin session keeps producing byte-identical summaries.
        return FittedPinnedPayload(carriedInner, pinned)
    }
    // [fix/r5-c1-compact-C01] Two exemptions, then newest-first fill of the
    // middle. The pre-fix loop carried a single `keptAny` exemption (only the
    // newest entry), so the earliest pin — the one `extractPinnedUserMessages`
    // re-adds unconditionally, and the reason this whole pin exists — was the
    // FIRST thing the trim threw away on every over-cap fold.
    val keep = BooleanArray(entries.size)
    val newest = entries.lastIndex
    keep[newest] = true
    var used = costs[newest]
    val oldest = 0
    if (oldest != newest && used + costs[oldest] <= tokenLimit) {
        keep[oldest] = true
        used += costs[oldest]
    }
    var i = newest - 1
    while (i > oldest) {
        if (used + costs[i] > tokenLimit) break
        keep[i] = true
        used += costs[i]
        i--
    }
    val keptCarried = carriedEntries.filterIndexed { idx, _ -> keep[idx] }
    val keptFresh = fresh.filterIndexed { idx, _ -> keep[carriedEntries.size + idx] }
    val newCarried = if (keptCarried.isEmpty()) {
        null
    } else {
        (listOfNotNull(header) + keptCarried).joinToString("\n\n")
    }
    // Fitted payload must stay monotone: what the caller hands back next round
    // is exactly what the ceiling sees, so "trim never regrows" is testable
    // end-to-end (CompactPinBudgetTest).
    return FittedPinnedPayload(newCarried, keptFresh)
}

/**
 * Append a `<pinned-user-messages>` block carrying [pinned] (this fold's
 * verbatim user text) plus [carriedInner] (the previous round's payload,
 * opaque). Order: carried first (oldest first), then the new messages.
 *
 * The merged payload is fitted to [PINNED_USER_MESSAGES_TOKEN_LIMIT] by
 * [fitPinnedPayload] BEFORE it is written, so the block cannot grow without
 * bound across rounds (see that function for the allocation rule).
 *
 * Idempotent: appending the exact block that is already present is a
 * no-op, and a defensive [stripPinnedSection] guarantees a summary can
 * never hold two blocks. Empty [pinned] + null/blank [carriedInner] →
 * [summary] returned byte-identical (the empty-pin contract).
 */
fun appendPinnedSection(
    summary: String,
    pinned: List<String>,
    carriedInner: String? = null,
): String {
    val fitted = fitPinnedPayload(carriedInner, pinned)
    val parts = ArrayList<String>()
    if (!fitted.carriedInner.isNullOrBlank()) parts.add(fitted.carriedInner)
    for (p in fitted.pinned) {
        if (p.isNotBlank()) parts.add(p)
    }
    if (parts.isEmpty()) return summary

    // carriedInner is a previously built payload and already contains the
    // header line — prepend the header ONLY when there is no carried text,
    // so multi-round cycles never duplicate it and the carried payload
    // always stays at the top of the block.
    // The header decision must follow the FITTED payload, not the raw
    // argument: when the budget drops the whole carried payload, the block
    // still needs its header line back or the model sees bare quoted text
    // with no idea where it came from.
    val inner = if (fitted.carriedInner.isNullOrBlank()) {
        (listOf(PINNED_HEADER) + parts).joinToString("\n\n")
    } else {
        parts.joinToString("\n\n")
    }
    val block = PINNED_OPEN + "\n" + inner + "\n" + PINNED_CLOSE
    if (summary.contains(block)) return summary // idempotent
    val base = stripPinnedSection(summary) // defensive: never two blocks
    return if (base.isNullOrBlank()) block else base + "\n\n" + block
}
