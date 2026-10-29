package com.rikkaminis.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-F F-F-08②] `debug.readFile` page boundaries, head side.
 *
 * R4-R F-10 fixed the TAIL of a page (`utf8CompletePrefixLength` trims a
 * partially-decoded character off the end). The head was left alone: when a
 * caller supplies an `offset` that lands inside a multi-byte character, the
 * window opens on continuation bytes and `String(bytes, UTF_8)` rendered them
 * as U+FFFD — one replacement character per page boundary, and with a `limit`
 * that only covers the fragment the whole `content` was U+FFFD.
 *
 * These run the PRODUCTION decode ([decodeUtf8Page]) and the PRODUCTION
 * validity check ([looksLikeUtf8Text]), so the two cannot disagree about where
 * the text starts.
 */
class DebugReadFileHeadBoundaryTest {

    private val sample = "中文测试😀abc"

    private fun bytes(s: String = sample) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun skipCountsOnlyLeadingContinuationBytes() {
        assertEquals("ascii starts on a lead byte", 0, utf8SkipLeadingContinuation("abc".toByteArray()))
        // 中 = E4 B8 AD. A window opened at B8 sees two continuation bytes.
        val zhong = "中".toByteArray(Charsets.UTF_8)
        assertEquals(2, utf8SkipLeadingContinuation(zhong.copyOfRange(1, zhong.size)))
        assertEquals(1, utf8SkipLeadingContinuation(zhong.copyOfRange(2, zhong.size)))
    }

    @Test
    fun skipIsCappedAtThreeForFourByteSequences() {
        // 😀 = F0 9F 98 80 → three continuation bytes at most.
        val emoji = "😀".toByteArray(Charsets.UTF_8)
        assertEquals(4, emoji.size)
        assertEquals(3, utf8SkipLeadingContinuation(emoji.copyOfRange(1, emoji.size)))
        // A window of nothing but continuation bytes still reports <= 3.
        val allCont = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())
        assertEquals(3, utf8SkipLeadingContinuation(allCont))
    }

    @Test
    fun emptyWindowIsNotAContinuationRun() {
        assertEquals(0, utf8SkipLeadingContinuation(ByteArray(0)))
    }

    @Test
    fun decodeNeverEmitsReplacementCharactersAtAnUnalignedOffset() {
        val all = bytes()
        // Walk every byte offset inside the multi-byte region — the pages that
        // used to answer U+FFFD.
        for (offset in 0 until all.size) {
            val window = all.copyOfRange(offset, all.size)
            val text = decodeUtf8Page(window)
            assertFalse(
                "offset $offset produced U+FFFD: ${text.replace('\uFFFD', '?')}",
                text.contains('\uFFFD'),
            )
        }
    }

    @Test
    fun tinyFragmentWindowDecodesToEmptyInsteadOfReplacementChars() {
        // The reported extreme: `limit` that only covers the tail of one
        // character. It must answer an empty string, never "\uFFFD".
        val all = bytes("中")
        val tailOnly = all.copyOfRange(1, 2)
        assertEquals("", decodeUtf8Page(tailOnly))
        assertEquals("中", decodeUtf8Page(all))
    }

    @Test
    fun alignedPageWalkReconstructsTheFileExactly() {
        // The contract a well-behaved caller lives by: advance by bytesRead.
        val all = bytes("中文测试😀abc")
        val limit = 5
        val out = StringBuilder()
        var offset = 0
        var pages = 0
        while (offset < all.size) {
            val end = minOf(all.size, offset + limit)
            val raw = all.copyOfRange(offset, end)
            // Tail trim first (production helper), then decode (production helper).
            val trimmed = raw.copyOf(utf8CompletePrefixLength(raw))
            out.append(decodeUtf8Page(trimmed))
            offset += trimmed.size
            pages++
            check(pages < 100) { "page walk did not advance" }
        }
        assertEquals(sample, out.toString())
        assertTrue("the walk must produce content", out.isNotEmpty())
    }

    @Test
    fun validityCheckAndDecodeAgreeOnWhereTextStarts() {
        val all = bytes()
        for (offset in 1 until all.size) {
            val window = all.copyOfRange(offset, all.size)
            val looksText = looksLikeUtf8Text(window)
            val decoded = decodeUtf8Page(window)
            if (looksText) {
                assertFalse(
                    "offset $offset: validity said text but the decode answered U+FFFD",
                    decoded.contains('\uFFFD'),
                )
            }
        }
    }

    @Test
    fun binaryWindowsAreStillRefused() {
        // The head skip must not turn NUL-containing bytes into "text".
        assertFalse(looksLikeUtf8Text(byteArrayOf(0x80.toByte(), 0x00, 0x41)))
    }

    @Test
    fun utf8CompletePrefixLengthStillTrimsTheTail() {
        // Regression guard for the neighbouring half of the boundary rule.
        val zhong = "中".toByteArray(Charsets.UTF_8)
        assertEquals(0, utf8CompletePrefixLength(zhong.copyOfRange(0, 2)))
        assertEquals(3, utf8CompletePrefixLength(zhong))
    }

    @Test
    fun decodeUtf8PageIsUsedByTheReadPath() {
        val relative = "src/android/app/src/main/java/com/rikkaminis/app/debug/DebugRPCHandler.kt"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        var text: String? = null
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) { text = f.readText(); break }
            dir = dir.parentFile
        }
        val code = text ?: error("$relative not found from user.dir=${System.getProperty("user.dir")}")
        assertTrue(
            "the text branch must decode through decodeUtf8Page (one head-skip rule)",
            code.contains("put(\"content\", decodeUtf8Page(bytes))"),
        )
        assertTrue(
            "no raw String(bytes, UTF_8) decode may remain on the read path",
            !code.contains("put(\"content\", String(bytes, Charsets.UTF_8))"),
        )
    }
}
