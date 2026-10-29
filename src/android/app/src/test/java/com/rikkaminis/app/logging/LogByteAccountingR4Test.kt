package com.rikkaminis.app.logging

import com.rikkaminis.app.util.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-R4-T F-07 / F-14] Unit anchors for the two pieces of accounting that
 * used to speak a different unit than the code around them.
 *
 * F-07: the debug channel's rotation threshold, its counter seed
 * (`file.length()`) and the FileWriter's encoder are byte-denominated; the
 * per-line increment was `line.length + 1`, i.e. UTF-16 chars. A CJK line
 * occupies 3 bytes per char, so the nominal 5 MB channel rotated at 12-15 MB
 * of real bytes.
 *
 * F-14: `debug.logs.read` handed `limit` straight to a ByteArray allocation
 * (up to the 200 MB storage cap) and read with `readFully`, which turns a
 * concurrent rotate into EOFException -> null -> "-32602 Log file not found".
 *
 * The expected values below are LITERAL on purpose: deriving them from the
 * production constants would let a mutated constant mutate the expectation too
 * (the JVM-apparatus self-reference trap recorded on 2026-09-26).
 */
class LogByteAccountingR4Test {

    @Test
    fun utf8ByteLengthMatchesTheJdkEncoderOnTheEncodingBoundaries() {
        // Every row straddles one of the encoder's branch points.
        val rows = listOf(
            "" to 0L,
            "a" to 1L,
            "\u007F" to 1L,      // last 1-byte code point
            "\u0080" to 2L,      // first 2-byte
            "\u07FF" to 2L,      // last 2-byte
            "\u0800" to 3L,      // first 3-byte
            "\u4E2D" to 3L,      // a CJK char: 1 UTF-16 unit, 3 bytes
            "\uFFFF" to 3L,      // last BMP
            "\uD83D\uDE00" to 4L, // 😀 surrogate pair
        )
        for ((text, expected) in rows) {
            assertEquals(
                "utf8ByteLength(${text.map { it.code }})",
                expected,
                utf8ByteLength(text),
            )
            assertEquals(
                "must match the JDK encoder for ${text.map { it.code }}",
                text.toByteArray(Charsets.UTF_8).size.toLong(),
                utf8ByteLength(text),
            )
        }
    }

    @Test
    fun utf8ByteLengthCountsAnUnpairedSurrogateAsTheEncodersReplacementByte() {
        // The JDK encoder emits '?' (1 byte) for a lone surrogate; the counter
        // has to agree or the file and the counter drift on malformed input.
        assertEquals(1L, utf8ByteLength("\uD800"))
        assertEquals(2L, utf8ByteLength("\uDC00x"))
        assertEquals("\uD800".toByteArray(Charsets.UTF_8).size.toLong(), utf8ByteLength("\uD800"))
    }

    @Test
    fun utf8ByteLengthIsAdditiveOverGeneratedMixedText() {
        val sb = StringBuilder()
        for (cp in intArrayOf(0x41, 0xE9, 0x4E2D, 0x7F, 0x800, 0x1F600, 0x0A)) {
            sb.appendCodePoint(cp)
        }
        val text = sb.toString()
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), utf8ByteLength(text))
    }

    @Test
    fun segmentBoundsClampsAnOversizedLimitToFourMebibytes() {
        assertEquals(4 * 1024 * 1024, MAX_LOG_SEGMENT_BYTES)
        // 50 MB limit over a 50 MB file: the window is the cap, not the file.
        val bounds = segmentBounds(50L * 1024 * 1024, 0L, 50_000_000)
        assertEquals(0L, bounds.start)
        assertEquals(4 * 1024 * 1024, bounds.length)
        assertEquals(4 * 1024 * 1024, segmentBounds(Long.MAX_VALUE, 0L, Int.MAX_VALUE).length)
    }

    @Test
    fun segmentBoundsCollapsesNonsenseInsteadOfThrowing() {
        assertEquals(0, segmentBounds(1_000_000, 0L, -5).length)
        assertEquals(0L, segmentBounds(100, -7L, 10).start)
        assertEquals(100L, segmentBounds(100, 999L, 10).start)
        assertEquals(0, segmentBounds(100, 999L, 10).length)
        assertEquals(0, segmentBounds(-1, 0L, 1024).length)
        assertEquals(300, segmentBounds(300, 0L, 1024).length)
        assertEquals(50, segmentBounds(100, 50L, 1024).length)
    }
}

/**
 * [FIX-R4-T F-08] The degraded store is indistinguishable from the real one to
 * every caller, so it must obey the SharedPreferences contract exactly.
 *
 * Two violations lived in the fallback editor and both are the same confusion
 * between "the key is absent" and "the key reads back empty":
 * `putString(k, null)` stored the null (so `contains(k)` was true while
 * `getString(k)` returned the default), and `remove(k)` left `staged[k]`
 * behind while `commit()` applied removals first — so a `put(...).remove(...)`
 * pair committed as a WRITE and a deleted secret came back.
 */
class InMemoryPrefsContractR4Test {

    @Test
    fun putWithANullValueIsARemoval() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("k", "v").commit()
        assertEquals("v", prefs.getString("k", "dflt"))

        prefs.edit().putString("k", null).commit()
        assertFalse("a null put must not leave a phantom key", prefs.contains("k"))
        assertEquals("dflt", prefs.getString("k", "dflt"))
    }

    @Test
    fun putWithANullSetIsARemoval() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putStringSet("s", mutableSetOf("x")).commit()
        assertTrue(prefs.contains("s"))

        prefs.edit().putStringSet("s", null).commit()
        assertFalse(prefs.contains("s"))
    }

    @Test
    fun laterRemoveCancelsAnEarlierPutInTheSameBatch() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("k", "v").commit()

        prefs.edit().putString("k", "new").remove("k").commit()
        assertFalse("remove() must win over an earlier put()", prefs.contains("k"))
    }

    @Test
    fun laterPutCancelsAnEarlierRemoveInTheSameBatch() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("k", "v").commit()

        prefs.edit().remove("k").putString("k", "new").commit()
        assertTrue(prefs.contains("k"))
        assertEquals("new", prefs.getString("k", "dflt"))
    }

    @Test
    fun clearFollowedByPutKeepsOnlyTheNewKey() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("old", "1").commit()

        prefs.edit().clear().putString("new", "2").commit()
        assertFalse(prefs.contains("old"))
        assertEquals("2", prefs.getString("new", "dflt"))
    }

    @Test
    fun commitStillReportsNotPersisted() {
        // The fallback's whole point: writes never reach disk, and the
        // SharedPreferences contract for that is `false`.
        val prefs = InMemorySharedPreferences()
        assertFalse(prefs.edit().putString("k", "v").commit())
    }
}
