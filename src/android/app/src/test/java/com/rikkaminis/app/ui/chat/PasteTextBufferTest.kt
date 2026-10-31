package com.rikkaminis.app.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/paste-fold-marker] Pure half of the long-paste folding — table-driven,
 * no Android, no Compose runtime.
 *
 * The three properties worth defending here are the ones a plausible
 * "simplification" would quietly break:
 *
 *  1. **Expansion is single-pass.** Repeated `replace` calls would expand a
 *     marker that arrived INSIDE the pasted block (a pasted transcript of this
 *     feature being discussed does that), injecting unrelated buffered text.
 *  2. **The fold lands on the INSERTED run, not the field.** Otherwise every
 *     keystroke after one big paste re-folds.
 *  3. **Indices are UTF-16 code units, which is the same space `substring`,
 *     `TextRange` and `TextFieldValue.selection` live in** — so an emoji-heavy
 *     draft cannot have a character split by the marker.
 */
class PasteTextBufferTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun fold(old: String, new: String, ids: (String) -> String): TextFieldValue =
        foldLongPasteIfNeeded(
            old = TextFieldValue(old, TextRange(old.length)),
            new = TextFieldValue(new, TextRange(new.length)),
            stash = ids,
        )

    private fun sequentialStash(): Pair<(String) -> String, MutableList<String>> {
        val stored = mutableListOf<String>()
        val mint: (String) -> String = { text ->
            stored += text
            PastedText.placeholderFor(stored.size)
        }
        return mint to stored
    }

    /** 1000+ short words, ASCII-dominant (the English branch of the threshold). */
    private fun englishWords(count: Int): String = (1..count).joinToString(" ") { "w" }

    private fun cjkChars(count: Int): String = "中".repeat(count)

    private fun assertNoLoneSurrogate(s: String, label: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue(
                    "$label: lone high surrogate at $i (a marker must never split a pair)",
                    i + 1 < s.length && Character.isLowSurrogate(s[i + 1]),
                )
                i += 2
            } else {
                assertFalse("$label: lone low surrogate at $i", Character.isLowSurrogate(c))
                i += 1
            }
        }
    }

    // ── isLongPastedText: the two thresholds ─────────────────────────────────

    @Test
    fun `english threshold is more than 1000 words`() {
        val rows = listOf(
            Triple(999, false, "999 words"),
            Triple(1000, false, "exactly 1000 words — not more than 1000"),
            Triple(1001, true, "1001 words"),
        )
        for ((n, expected, label) in rows) {
            assertEquals("english: $label", expected, isLongPastedText(englishWords(n)))
        }
    }

    @Test
    fun `CJK threshold is more than 1200 characters`() {
        val rows = listOf(
            1199 to false,
            1200 to false,
            1201 to true,
        )
        for ((n, expected) in rows) {
            assertEquals("cjk: $n chars", expected, isLongPastedText(cjkChars(n)))
        }
    }

    @Test
    fun `mixed text takes the CJK branch because ascii letters are not the majority`() {
        // 700 ascii letters + 700 CJK = 1400 units; 700 is NOT > 1400/2.
        val mixed = ("a".repeat(700) + cjkChars(700))
        assertEquals(mixed.length, 1400)
        assertTrue("mixed 1400 chars must fold on the character threshold", isLongPastedText(mixed))
        // Same shape but short in characters: the (huge) word count must not
        // rescue it, because the English branch is not the one that applies.
        val shortMixed = "a".repeat(20) + cjkChars(100)
        assertFalse(isLongPastedText(shortMixed))
    }

    @Test
    fun `empty and blank input never folds`() {
        assertFalse(isLongPastedText(""))
        assertFalse(isLongPastedText("   \n\t "))
    }

    @Test
    fun `non-BMP characters count as two UTF-16 units on the character branch`() {
        // 600 emoji × 2 units = 1200 units → not more than 1200.
        assertFalse(isLongPastedText("😀".repeat(600)))
        // 601 emoji × 2 units = 1202 units → folds.
        assertTrue(isLongPastedText("😀".repeat(601)))
    }

    // ── foldLongPasteIfNeeded ────────────────────────────────────────────────

    @Test
    fun `a long insertion at the end becomes one marker with the caret after it`() {
        val (mint, stored) = sequentialStash()
        val block = cjkChars(1300)
        val out = fold("look at this", "look at this" + block, mint)

        assertEquals(1, stored.size)
        assertEquals(block, stored[0])
        assertEquals("look at this[Pasted#1]", out.text)
        assertEquals(TextRange("look at this[Pasted#1]".length), out.selection)
    }

    @Test
    fun `an insertion in the middle keeps both runs and only replaces the block`() {
        val (mint, stored) = sequentialStash()
        val block = englishWords(1200)
        val out = fold("HEAD|TAIL", "HEAD" + block + "|TAIL", mint)

        assertEquals("HEAD[Pasted#1]|TAIL", out.text)
        assertEquals(TextRange("HEAD[Pasted#1]".length), out.selection)
        assertEquals(block, stored.single())
    }

    @Test
    fun `pasting over a selection folds the replacement and keeps both neighbours`() {
        // old "A<selected>B" → new "A<block>B": the prefix is "A", the suffix is
        // "B", and the selected text is gone from both ends at once.
        val (mint, _) = sequentialStash()
        val out = fold("AselectedB", "A" + cjkChars(1500) + "B", mint)
        assertEquals("A[Pasted#1]B", out.text)
    }

    @Test
    fun `two pastes mint increasing ids and never touch the first marker`() {
        val (mint, stored) = sequentialStash()
        val first = fold("", cjkChars(1300), mint)
        assertEquals("[Pasted#1]", first.text)
        val second = fold(first.text + " and ", first.text + " and " + cjkChars(1400), mint)
        assertEquals("[Pasted#1] and [Pasted#2]", second.text)
        assertEquals(2, stored.size)
        assertTrue(stored[0].length < stored[1].length)
    }

    @Test
    fun `short insertions are returned untouched and stash nothing`() {
        var calls = 0
        val mint: (String) -> String = { calls++; "[Pasted#9]" }
        val cases = listOf(
            "" to "hello",
            "abc" to "abcd",
            "line" to "line\nbreak",
            "x" to "x" + cjkChars(1200), // exactly at the threshold
        )
        for ((old, new) in cases) {
            val out = fold(old, new, mint)
            assertEquals(new, out.text)
        }
        assertEquals("no marker may be minted for a short edit", 0, calls)
    }

    @Test
    fun `deletions and caret-only changes pass through`() {
        val out = fold(cjkChars(2000), cjkChars(1500)) { error("must not stash on delete") }
        assertEquals(cjkChars(1500), out.text)
    }

    @Test
    fun `an edit with equal length but different content does not fold`() {
        // delta == 0 → cheap gate. Multi-character replacement of the same
        // length is not a paste and must not be re-labelled as one.
        val out = fold("aaaa", "bbbb") { error("must not stash on a same-length edit") }
        assertEquals("bbbb", out.text)
    }

    @Test
    fun `a same-length rewrite is left alone even when it is a whole new string`() {
        // delta == 0, so the cheap gate stops it before the threshold is even
        // consulted — a wall of text swapped for another wall of text is an
        // edit, not a paste, and must not be re-labelled as one.
        val old = "abcd".repeat(400)
        val new = cjkChars(1600)
        val out = fold(old, new) { error("must not stash a same-length rewrite") }
        assertEquals(new, out.text)
    }

    // ── surrogate / UTF-16 index safety ──────────────────────────────────────

    @Test
    fun `folding between two astral characters cannot split a surrogate pair`() {
        val (mint, stored) = sequentialStash()
        val prefix = "😀😀"
        val suffix = "🎉🎉"
        val block = cjkChars(1300)
        val out = fold(prefix + suffix, prefix + block + suffix, mint)

        assertEquals("$prefix[Pasted#1]$suffix", out.text)
        assertEquals(block, stored.single())
        // Caret is an index in the SAME space the marker ends at (UTF-16 units).
        val caret = out.selection.start
        assertEquals(prefix.length + "[Pasted#1]".length, caret)
        assertNoLoneSurrogate(out.text, "folded text")
        // And the boundary really is a boundary: everything before the marker is
        // exactly the prefix, in whole code units — no half of an emoji leaks
        // into the marker.
        assertEquals(prefix, out.text.substring(0, caret - "[Pasted#1]".length))
    }

    @Test
    fun `an emoji-dense pasted block survives the round trip through expand`() {
        val (mint, stored) = sequentialStash()
        val block = "😀".repeat(601) // 1202 units → folds
        val folded = fold("a", "a" + block + "b", mint).text
        val (expanded, consumed) = expandPastePlaceholders(
            folded,
            listOf(PastedText(1, stored.single())),
        )
        assertEquals("a" + block + "b", expanded)
        assertEquals(setOf(1), consumed)
        assertNoLoneSurrogate(expanded, "expanded text")
    }

    // ── expandPastePlaceholders ──────────────────────────────────────────────

    @Test
    fun `expansion is single-pass so a marker inside pasted content is not expanded`() {
        val buffer = listOf(
            PastedText(1, "A[Pasted#2]B"),
            PastedText(2, "SHOULD-NOT-APPEAR"),
        )
        val (expanded, consumed) = expandPastePlaceholders("[Pasted#1]", buffer)
        assertEquals("A[Pasted#2]B", expanded)
        assertEquals("only #1 was referenced", setOf(1), consumed)
    }

    @Test
    fun `a pasted block that contains its own marker does not self-expand`() {
        val buffer = listOf(PastedText(1, "x[Pasted#1]y"))
        val (expanded, consumed) = expandPastePlaceholders("[Pasted#1]", buffer)
        assertEquals("x[Pasted#1]y", expanded)
        assertEquals(setOf(1), consumed)
    }

    @Test
    fun `unknown ids stay verbatim and are not reported as consumed`() {
        val buffer = listOf(PastedText(1, "ONE"))
        val (expanded, consumed) = expandPastePlaceholders(
            "a [Pasted#1] b [Pasted#99] c [Pasted#0]",
            buffer,
        )
        assertEquals("a ONE b [Pasted#99] c [Pasted#0]", expanded)
        assertEquals(setOf(1), consumed)
    }

    @Test
    fun `damaged markers are text, never errors`() {
        val buffer = listOf(PastedText(1, "ONE"))
        val rows = listOf(
            "[Pasted#1",
            "[Pasted#]",
            "[Pasted# 1]",
            "[pasted#1]", // lowercase reads as a marker (leniency is deliberate)
            "[Pasted#1]]",
        )
        val expanded = rows.map { expandPastePlaceholders(it, buffer) }
        assertEquals("[Pasted#1", expanded[0].first)
        assertEquals("[Pasted#]", expanded[1].first)
        assertEquals("[Pasted# 1]", expanded[2].first)
        assertEquals("ONE", expanded[3].first)
        assertEquals("ONE]", expanded[4].first)
        assertEquals(setOf(1), expanded[3].second)
    }

    @Test
    fun `every marker is expanded in one pass and all ids are consumed`() {
        val buffer = listOf(PastedText(1, "ONE"), PastedText(2, "TWO"), PastedText(3, "THREE"))
        val (expanded, consumed) = expandPastePlaceholders(
            "[Pasted#1] + [Pasted#2] + [Pasted#2] + [Pasted#3]",
            buffer,
        )
        assertEquals("ONE + TWO + TWO + THREE", expanded)
        assertEquals(setOf(1, 2, 3), consumed)
    }

    @Test
    fun `empty text or empty buffer is an identity with nothing consumed`() {
        assertEquals("" to emptySet<Int>(), expandPastePlaceholders("", listOf(PastedText(1, "X"))))
        assertEquals(
            "[Pasted#1]" to emptySet<Int>(),
            expandPastePlaceholders("[Pasted#1]", emptyList()),
        )
    }

    // ── unexpandedPasteMarkers: the fail-closed guard ────────────────────────

    @Test
    fun `a restored draft marker is reported and a known one is not`() {
        val buffer = listOf(PastedText(2, "TWO"))
        assertEquals(
            listOf("[Pasted#1]"),
            unexpandedPasteMarkers("[Pasted#1] plus [Pasted#2]", buffer),
        )
        assertEquals(
            emptyList<String>(),
            unexpandedPasteMarkers("only [Pasted#2]", buffer),
        )
        assertEquals(emptyList<String>(), unexpandedPasteMarkers("plain text", buffer))
        assertEquals(emptyList<String>(), unexpandedPasteMarkers("", buffer))
    }

    @Test
    fun `the guard de-duplicates and keeps document order`() {
        val markers = unexpandedPasteMarkers(
            "[Pasted#3] a [Pasted#1] b [Pasted#3] c [Pasted#7]",
            emptyList(),
        )
        assertEquals(listOf("[Pasted#3]", "[Pasted#1]", "[Pasted#7]"), markers)
    }

    @Test
    fun `damaged literals are not treated as unexpandable markers`() {
        val text = "[Pasted#1 [Pasted#] [Pasted# 1] plain"
        assertEquals(emptyList<String>(), unexpandedPasteMarkers(text, emptyList()))
    }

    @Test
    fun `an overflowed id is unexpandable rather than an exception`() {
        val markers = unexpandedPasteMarkers("[Pasted#99999999999999999999]", emptyList())
        assertEquals(listOf("[Pasted#99999999999999999999]"), markers)
        assertNull(pasteMarkerId("[Pasted#99999999999999999999]"))
    }

    // ── removePasteMarker / pasteMarkerId ────────────────────────────────────

    @Test
    fun `removing a chip marker is exact so neighbouring ids survive`() {
        val text = "[Pasted#2] keep [Pasted#20] keep [Pasted#2]"
        assertEquals(" keep [Pasted#20] keep ", removePasteMarker(text, 2))
        assertEquals("[Pasted#2] keep [Pasted#20] keep [Pasted#2]", removePasteMarker(text, 3))
        assertEquals("", removePasteMarker("[Pasted#5]", 5))
    }

    @Test
    fun `placeholder ids parse back and junk does not`() {
        assertEquals(42, pasteMarkerId("[Pasted#42]"))
        assertNull(pasteMarkerId("[Pasted#42"))
        assertNull(pasteMarkerId("[Pasted#4x2]"))
        assertNull(pasteMarkerId("Pasted#42"))
        assertNull(pasteMarkerId("[Pasted#]"))
        assertEquals("[Pasted#7]", PastedText(7, "x").placeholder)
        assertEquals("x".length, PastedText(7, "x").charCount)
    }
}
