package com.rikkaminis.app.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * [feat/paste-fold-marker] A long block of text the user pasted, held out of
 * the composer and represented there by either a `[Pasted#N]` literal
 * ([inlineMarker] = true) or by nothing at all ([inlineMarker] = false).
 *
 * Pasting a wall of text used to dump the whole thing into the input field:
 * the caret fell off the bottom, the field scrolled for pages, and the rest of
 * the draft became invisible. The text now lives here; [expandPastePlaceholders]
 * puts it back at send time, so the model and the stored history both see the
 * full text and nothing about the wire format changes.
 *
 * Two shapes, because the composer has two jobs for a paste:
 *
 *  - **Inline ([inlineMarker] = true)** — the field already held text, so the
 *    paste landed at a caret position the user chose. A `[Pasted#N]` literal
 *    stays in the text to remember that position; deleting the literal is how
 *    the user un-inserts the block.
 *  - **Detached ([inlineMarker] = false)** — the field was EMPTY when the paste
 *    arrived, which is the common case ("paste a report, then say what to do
 *    with it"). There is no position worth remembering, so the composer stays
 *    visually empty and the chip row is the only trace. On send the block is
 *    appended after the typed text, separated by a blank line — the same
 *    "body + material" order as a message with an attachment. Fixing the order
 *    here (rather than guessing a caret) is why this arm needs no marker at
 *    all: a literal in the field the user can neither read nor delete is
 *    exactly the noise this arm removes.
 *
 * MEMORY-ONLY, per ViewModel (i.e. per session). Unlike upstream, our composer
 * text IS persisted ([com.rikkaminis.app.data.ComposerDraftStore]), so the two
 * lifetimes do not match: after process death a restored draft can hold a
 * marker whose text is gone. Persisting the buffer as well would mean a new
 * field across model / entity / snapshot / serialization (the four-way-sync
 * rule) plus a reclamation policy for markers nobody references — so instead
 * the mismatch is handled fail-closed at the composer: [unexpandedPasteMarkers]
 * is non-empty ⇒ the UI disables sending and says why. Nothing is lost and no
 * half-persisted state can exist.
 *
 * A detached block has no such guard (there is no literal to notice), so a
 * process death between the paste and the send drops it silently. Accepted:
 * the window is one draft, and the alternative — writing a literal the user
 * just told us is noise — trades a rare, obvious-in-retrospect loss for an
 * everyday annoyance. Registered in the feature report.
 */
data class PastedText(
    val id: Int,
    val text: String,
    /**
     * `true` ⇒ a `[Pasted#N]` literal sits in the composer and this entry is
     * expanded in place. `false` ⇒ nothing sits in the composer and this entry
     * is appended after the message body on send.
     */
    val inlineMarker: Boolean = true,
) {
    /** Marker written into the composer for this entry. */
    val placeholder: String get() = placeholderFor(id)

    /** Character count, what the chip shows under the id. */
    val charCount: Int get() = text.length

    companion object {
        fun placeholderFor(id: Int): String = "[Pasted#$id]"
    }
}

/**
 * [feat/paste-fold-marker] Matches a placeholder literal.
 *
 * `\d+` only — no whitespace, no sign, no leading-zero special-casing. A
 * damaged marker (`[Pasted#3`, `[Pasted#]`, `[Pasted# 3]`) simply does not
 * match and is left in the text verbatim, which is the required behaviour for
 * "the user hand-edited the marker": never crash, never guess.
 *
 * The leading letter accepts either case. We only ever WRITE `[Pasted#N]`, but
 * a user retyping a marker by hand is far more likely to type it lowercase, and
 * silently failing to expand their text would look like data loss. Reading
 * loosely while writing strictly costs one character in the pattern.
 */
private val PASTE_PLACEHOLDER_REGEX = Regex("""\[[Pp]asted#(\d+)]""")

/**
 * [feat/paste-fold-marker] Replace every `[Pasted#N]` with its buffered text,
 * in ONE pass.
 *
 * Single-pass is the load-bearing property, not an optimisation. If pasted
 * content itself contains something shaped like `[Pasted#2]` — which is exactly
 * what happens when a user pastes a transcript of this feature being discussed
 * — a recursive or repeated-replace implementation would expand that inner
 * literal too, injecting unrelated buffered text into the message. Here the
 * scan reads the ORIGINAL string once and only ever consults it for match
 * positions, so expanded content is never re-scanned.
 *
 * Unknown ids are left verbatim: a hand-typed `[Pasted#99]` is just text, and a
 * send must never fail because of one. That also covers the entry the user
 * deleted from the buffer but left the marker for (the composer-side guard in
 * [unexpandedPasteMarkers] is what stops that one from being sent silently).
 *
 * Detached entries ([PastedText.inlineMarker] = false) are not consulted here
 * at all — they have no literal to match, and [appendUnlinkedPastes] is what
 * puts them back.
 *
 * @return the expanded text plus the ids that were actually consumed, so the
 *   caller can drop exactly those entries and leave orphans alone.
 */
fun expandPastePlaceholders(
    text: String,
    buffer: List<PastedText>,
): Pair<String, Set<Int>> {
    if (text.isEmpty() || buffer.isEmpty()) return text to emptySet()
    if (!text.contains('#')) return text to emptySet()
    val byId = buffer.filter { it.inlineMarker }.associateBy { it.id }
    if (byId.isEmpty()) return text to emptySet()
    val consumed = mutableSetOf<Int>()
    val out = StringBuilder(text.length)
    var cursor = 0
    for (match in PASTE_PLACEHOLDER_REGEX.findAll(text)) {
        val id = match.groupValues[1].toIntOrNull()
        val entry = id?.let { byId[it] } ?: continue // unknown / overflowed → leave verbatim
        out.append(text, cursor, match.range.first)
        out.append(entry.text)
        cursor = match.range.last + 1
        consumed += entry.id
    }
    if (consumed.isEmpty()) return text to emptySet()
    out.append(text, cursor, text.length)
    return out.toString() to consumed
}

/**
 * [feat/paste-fold-marker] Append every detached block to [text], in buffer
 * order, separated by one blank line (none when the body is empty).
 *
 * Buffer order is paste order: [PastedText] rows are appended when pasted and
 * only ever filtered, never reordered, so the first block the user pasted into
 * an empty field reads first. Do NOT sort by id: [nextFreePasteId] recycles the
 * smallest free id, so "delete #1, paste again" mints #1 again and an id sort
 * would move the newest block in front of the older ones (fix/composer-paste-
 * lifecycle — audit finding P1-A).
 *
 * The body — whatever the user typed afterwards — always comes
 * first, matching the "body, then material" shape of a message that carries
 * attachments. This is a fixed rule by design: a detached block has no caret
 * to remember, and inventing one (e.g. "insert at the offset where the paste
 * started") would silently move the user's own words when they edit above it.
 */
fun appendUnlinkedPastes(text: String, buffer: List<PastedText>): String {
    val detached = buffer.filter { !it.inlineMarker }
    if (detached.isEmpty()) return text
    val out = StringBuilder(text)
    for (entry in detached) {
        if (out.isNotEmpty()) out.append("\n\n")
        out.append(entry.text)
    }
    return out.toString()
}

/**
 * [feat/paste-fold-marker] The marker literals in [text] that this session
 * cannot expand, in document order, de-duplicated.
 *
 * This is the fail-closed half of the design. Upstream needs nothing like it
 * because its composer and its buffer share one lifetime; ours do not (the
 * draft is persisted, the buffer is not), so a restored draft can legitimately
 * hold a marker whose text died with the process. Sending that verbatim would
 * ship a `[Pasted#1]` literal to the model in place of the user's paragraphs —
 * silent, unnoticed data loss — so the composer refuses to send while this
 * returns anything.
 *
 * Consequence, accepted deliberately: a marker the user typed BY HAND in the
 * exact minted shape (`[Pasted#\d+]`, capital P) also blocks the send, because
 * after a restart nothing distinguishes it from a restored one. Expansion
 * itself stays lenient (unknown ids survive as text), so the headless / debug
 * RPC paths — which never touch this guard — keep upstream's behaviour. The
 * notice tells the user to delete the marker, which is one tap in the same
 * field they are looking at.
 */
fun unexpandedPasteMarkers(
    text: String,
    buffer: List<PastedText>,
): List<String> {
    if (text.isEmpty() || !text.contains('#')) return emptyList()
    val known = buffer.mapTo(HashSet()) { it.id }
    val out = LinkedHashSet<String>()
    for (match in PASTE_PLACEHOLDER_REGEX.findAll(text)) {
        val id = match.groupValues[1].toIntOrNull()
        if (id == null || id !in known) out += match.value
    }
    return out.toList()
}

/**
 * [feat/paste-fold-marker] Every id mentioned by a marker literal in [text].
 *
 * Id allocation consults this so a freshly minted id can never collide with a
 * literal that is still on screen — including the orphan of a restored draft,
 * whose buffer row died with the process. Reusing that id would make the stale
 * literal expand to the NEW block: content the user never placed there.
 */
fun pasteMarkerIdsIn(text: String): Set<Int> {
    if (text.isEmpty() || !text.contains('#')) return emptySet()
    return PASTE_PLACEHOLDER_REGEX.findAll(text)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .toSet()
}

/**
 * [feat/paste-fold-marker] Smallest positive id that is not in [used].
 *
 * Reuse, not monotonic growth: ids exist only to pair a marker with its block,
 * so an id that no buffer row and no on-screen literal references is free.
 * Growing forever made "delete #1, paste again → #2" look like the app was
 * counting the user's pastes, which is exactly the kind of bookkeeping that
 * leaks into the UI for no benefit.
 *
 * Collisions with a marker still in the text (an orphan from a restored draft)
 * are avoided by the caller including [pasteMarkerIdsIn] in [used].
 */
fun nextFreePasteId(used: Set<Int>): Int {
    var id = 1
    while (id in used) id++
    return id
}

/**
 * [feat/paste-fold-marker] Drop every occurrence of [id]'s marker from [text].
 *
 * Called from the chip's delete button, in the same edit that drops the buffer
 * entry, so the text and the buffer can never disagree. Exact-literal match:
 * deleting `#2` cannot disturb `#20`, because the trailing `]` makes it exact.
 *
 * A detached entry has no literal, so this is a no-op for it — the caller can
 * run the same path for both kinds without branching.
 */
fun removePasteMarker(text: String, id: Int): String =
    if (text.isEmpty() || !text.contains('#')) text
    else text.replace(PastedText.placeholderFor(id), "")

/**
 * [feat/paste-fold-marker] The id carried by [marker], or null when [marker] is
 * not a single well-formed literal.
 *
 * Used when adopting a restored draft: the ids found there must never be minted
 * again this session, or a fresh paste would take over an old marker and the
 * stale literal would silently expand to the WRONG text.
 */
fun pasteMarkerId(marker: String): Int? {
    val match = PASTE_PLACEHOLDER_REGEX.matchEntire(marker) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/**
 * [feat/paste-fold-marker] True when [inserted] is long enough to fold into a
 * placeholder.
 *
 * Mirrors the iOS implementation exactly, including the language split: English
 * prose carries far more characters per unit of meaning than CJK, so a single
 * character threshold would either fold short English paragraphs or let long
 * Chinese ones through. ASCII letters > half the length is the same
 * "English-dominant" test iOS uses.
 *
 *   English-dominant → more than 1000 whitespace-separated words
 *   otherwise (CJK / mixed) → more than 1200 characters
 *
 * Length is counted in UTF-16 code units (`String.length`), matching the index
 * space [foldLongPasteIfNeeded] slices in — see the surrogate note there.
 */
fun isLongPastedText(inserted: String): Boolean {
    if (inserted.isEmpty()) return false
    val asciiLetters = inserted.count { it in 'A'..'Z' || it in 'a'..'z' }
    val englishDominant = asciiLetters > inserted.length / 2
    return if (englishDominant) {
        inserted.split(' ', '\n', '\t', '\r').count { it.isNotEmpty() } > 1000
    } else {
        inserted.length > 1200
    }
}

/**
 * [feat/paste-fold-marker] If [new] differs from [old] by one large inserted
 * run, hand that run to [stash] and replace it with whatever marker comes back.
 *
 * Returns [new] unchanged when the edit is not a big insertion, so ordinary
 * typing, deleting and IME composition flow through untouched.
 *
 * Two shapes, chosen by whether the field already held text:
 *
 *  - **Field empty** → `stash(inserted, inlineMarker = false)` and the block
 *    leaves the text entirely; the composer stays empty. Null marker is the
 *    signal for that arm (a detached paste has nothing to write).
 *  - **Field non-empty** → `stash(inserted, inlineMarker = true)` and the
 *    returned `[Pasted#N]` literal takes the run's place, remembering the
 *    caret position the user pasted into.
 *
 * How the inserted span is located: common prefix from the left, common suffix
 * from the right, and whatever remains in the middle is what changed. That
 * handles a paste at any caret position — start, middle, end — and, unlike a
 * naive "the new text ends with the old text" check, it also handles pasting
 * over a selection (the replaced text disappears from both ends at once).
 *
 * Why length delta rather than the raw new length: the test must fire on the
 * INSERTED run, not the field's total size. Otherwise, once the field already
 * held a long block, every subsequent keystroke would re-trigger.
 *
 * Surrogates: every index here is a UTF-16 code unit (exactly what
 * [TextFieldValue.selection] and `String.substring` use), so an inserted run
 * that starts or ends in the middle of a surrogate PAIR can never be produced
 * by the prefix/suffix walk — a lone high surrogate differing from a lone low
 * one is still a `Char` comparison, and the run boundary is a position inside
 * the field, not inside the buffer. The folded marker therefore never splits a
 * character; the commit path additionally sanitizes unpaired surrogates
 * ([com.rikkaminis.app.util.Utf16Sanitizer]) once the text reaches the ViewModel.
 *
 * Deliberate non-goals:
 *  - It cannot distinguish a clipboard paste from an IME committing a large
 *    block (some voice keyboards do this). That is fine: a wall of text is
 *    worth folding whichever way it arrived.
 *  - A multi-run edit (rare: some autocorrect implementations) falls back to
 *    treating the whole middle as one span, which is still correct — the user
 *    sees one marker (or one chip) for one large change.
 */
fun foldLongPasteIfNeeded(
    old: TextFieldValue,
    new: TextFieldValue,
    stash: (text: String, inlineMarker: Boolean) -> String?,
): TextFieldValue {
    val oldText = old.text
    val newText = new.text
    val delta = newText.length - oldText.length
    // Cheap gate first: a deletion or a small edit can never be a long paste,
    // and this is the branch nearly every keystroke takes.
    if (delta <= 0) return new

    var prefix = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

    var suffix = 0
    val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
    while (
        suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++

    val insertedStart = prefix
    val insertedEnd = newText.length - suffix
    if (insertedEnd <= insertedStart) return new
    val inserted = newText.substring(insertedStart, insertedEnd)
    if (!isLongPastedText(inserted)) return new

    // An empty field is the "paste the material, then say what to do with it"
    // case: there is no position worth remembering, so the block stays out of
    // the text and the composer reads as empty.
    val allowInlineMarker = oldText.isNotEmpty()
    val marker = stash(inserted, allowInlineMarker)
    if (marker == null) {
        // Detached: the inserted run never reaches the field.
        val caret = minOf(insertedStart, oldText.length)
        return TextFieldValue(text = oldText, selection = TextRange(caret))
    }
    val folded = newText.substring(0, insertedStart) + marker + newText.substring(insertedEnd)
    // Caret goes just after the marker — where the user's next keystroke
    // belongs, and what they would expect had they typed the marker by hand.
    val caret = insertedStart + marker.length
    return TextFieldValue(
        text = folded,
        selection = TextRange(caret),
    )
}
