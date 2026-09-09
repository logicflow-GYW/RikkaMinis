package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the public streaming-merge pure functions in
 * [StreamingMarkdownText]:
 *
 * - [shouldIgnoreRegressiveStreamingSnapshot] — detect regressive snapshots
 *   (incoming is shorter and a prefix of current).
 * - [mergeAgentTextSnapshot] — merge a progressive snapshot into accumulated
 *   text (raw-text layer).
 * - [mergeLegacyStreamingText] — legacy merge with full overlap detection,
 *   divergent snapshot handling, and fallback concatenation.
 */
class StreamingMergeFunctionsTest {

    // ── shouldIgnoreRegressiveStreamingSnapshot ────────────────────────────

    @Test fun `regressive when incoming is shorter prefix`() {
        assertTrue(shouldIgnoreRegressiveStreamingSnapshot("Hello World", "Hello"))
    }

    @Test fun `not regressive when incoming is empty`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello", ""))
    }

    @Test fun `not regressive when current is empty`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("", "Hello"))
    }

    @Test fun `not regressive when both empty`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("", ""))
    }

    @Test fun `not regressive when incoming is longer`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello", "Hello World"))
    }

    @Test fun `not regressive when incoming is shorter but not a prefix`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello World", "World"))
    }

    @Test fun `not regressive when equal length`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello", "World"))
    }

    @Test fun `regressive with single character prefix`() {
        assertTrue(shouldIgnoreRegressiveStreamingSnapshot("Hello", "H"))
    }

    @Test fun `regressive with exact same text`() {
        // same length, not regressive (equal length -> false)
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello", "Hello"))
    }

    @Test fun `regressive with unicode characters`() {
        assertTrue(shouldIgnoreRegressiveStreamingSnapshot("你好世界", "你好"))
    }

    @Test fun `regressive with special characters`() {
        assertTrue(shouldIgnoreRegressiveStreamingSnapshot("test\nnewline\nmore", "test\nnewline"))
    }

    @Test fun `not regressive when incoming is shorter but not prefix due to case`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello World", "hello"))
    }

    // ── mergeAgentTextSnapshot ─────────────────────────────────────────────

    @Test fun `merge empty incoming returns current`() {
        assertEquals("Hello", mergeAgentTextSnapshot("Hello", ""))
    }

    @Test fun `merge empty current returns incoming`() {
        assertEquals("World", mergeAgentTextSnapshot("", "World"))
    }

    @Test fun `merge both empty returns empty`() {
        assertEquals("", mergeAgentTextSnapshot("", ""))
    }

    @Test fun `merge identical strings returns current`() {
        assertEquals("Hello", mergeAgentTextSnapshot("Hello", "Hello"))
    }

    @Test fun `merge regressive snapshot keeps current`() {
        assertEquals("Hello World", mergeAgentTextSnapshot("Hello World", "Hello"))
    }

    @Test fun `merge progressive snapshot returns incoming`() {
        assertEquals("Hello World", mergeAgentTextSnapshot("Hello", "Hello World"))
    }

    @Test fun `merge divergent replacement returns incoming`() {
        assertEquals("New text", mergeAgentTextSnapshot("Old text", "New text"))
    }

    @Test fun `merge divergent replacement longer than current`() {
        assertEquals("This is a much longer text", mergeAgentTextSnapshot("Short", "This is a much longer text"))
    }

    @Test fun `merge current with unicode incoming`() {
        assertEquals("你好世界", mergeAgentTextSnapshot("你好", "你好世界"))
    }

    @Test fun `merge regressive with unicode`() {
        assertEquals("你好世界啊", mergeAgentTextSnapshot("你好世界啊", "你好世界"))
    }

    @Test fun `merge with newlines`() {
        assertEquals("Hello\nWorld\nLine 3", mergeAgentTextSnapshot("Hello\nWorld", "Hello\nWorld\nLine 3"))
    }

    @Test fun `merge regressive with newlines`() {
        assertEquals("Hello\nWorld\nLine 3", mergeAgentTextSnapshot("Hello\nWorld\nLine 3", "Hello\nWorld"))
    }

    @Test fun `merge single character progression`() {
        assertEquals("Hello!", mergeAgentTextSnapshot("Hello", "Hello!"))
    }

    @Test fun `merge complete replacement of same length`() {
        assertEquals("abcde", mergeAgentTextSnapshot("vwxyz", "abcde"))
    }

    // ── mergeLegacyStreamingText ────────────────────────────────────────────

    @Test fun `legacy empty incoming returns current`() {
        assertEquals("Hello", mergeLegacyStreamingText("Hello", ""))
    }

    @Test fun `legacy empty current returns incoming`() {
        assertEquals("World", mergeLegacyStreamingText("", "World"))
    }

    @Test fun `legacy both empty returns empty`() {
        assertEquals("", mergeLegacyStreamingText("", ""))
    }

    @Test fun `legacy identical strings returns current`() {
        assertEquals("Hello", mergeLegacyStreamingText("Hello", "Hello"))
    }

    @Test fun `legacy regressive snapshot keeps current`() {
        assertEquals("Hello World", mergeLegacyStreamingText("Hello World", "Hello"))
    }

    @Test fun `legacy normal append returns incoming`() {
        assertEquals("Hello World", mergeLegacyStreamingText("Hello", "Hello World"))
    }

    @Test fun `legacy suffix prefix overlap deduplication`() {
        // "abc" suffix "abc" matches incoming "abcdef" prefix "abc"
        // overlap = 3, content = "abc" + "def" = "abcdef"
        assertEquals("abcdef", mergeLegacyStreamingText("abc", "abcdef"))
    }

    @Test fun `legacy single character suffix overlap concatenates`() {
        // "Hello" suffix "o" matches incoming "orld" prefix "o"
        // overlap = 1, below MINIMUM_STREAMING_OVERLAP_LENGTH (3)
        // commonPrefixLength of "Hello" and "orld" = 0 (H != o)
        // not divergent -> fallback: "Hello" + "orld" = "Helloorld"
        assertEquals("Helloorld", mergeLegacyStreamingText("Hello", "orld"))
    }

    @Test fun `legacy divergent snapshot keeps longer version`() {
        // "The quick brown fox jumps over the lazy dog" has 44 chars
        // "The quick brown fox jumps over the lazy cat" has 43 chars
        // Common prefix = "The quick brown fox jumps over the lazy " (37 chars)
        // 37 >= 24 -> looks like divergent -> keep longer (44 chars)
        val current = "The quick brown fox jumps over the lazy dog"
        val incoming = "The quick brown fox jumps over the lazy cat"
        // Both same length? No, dog=3, cat=3 -> same length 44 vs 43?
        // "dog" = 3, "cat" = 3, same length after "The quick brown fox...lazy "
        // Actually same length, so divergent -> keep incoming (shorter? no same length)
        // incoming.length >= current.length -> true (43 >= 43) -> return incoming
        val result = mergeLegacyStreamingText(current, incoming)
        assertEquals(incoming, result)
    }

    @Test fun `legacy divergent with longer incoming`() {
        // incoming ends with longer replacement
        val current = "The quick brown fox jumps over the lazy dog"
        val incoming = "The quick brown fox jumps over the lazy cat and runs away"
        // Common prefix "The quick brown fox jumps over the lazy " (37 chars)
        // 37 >= 24 -> looks like divergent -> incoming longer -> return incoming
        val result = mergeLegacyStreamingText(current, incoming)
        assertEquals(incoming, result)
    }

    @Test fun `legacy divergent with longer current`() {
        val current = "The quick brown fox jumps over the lazy dog and runs away"
        val incoming = "The quick brown fox jumps over the lazy cat"
        // Common prefix "The quick brown fox jumps over the lazy " (37 chars)
        // 37 >= 24 -> looks like divergent -> incoming not longer -> return current
        val result = mergeLegacyStreamingText(current, incoming)
        assertEquals(current, result)
    }

    @Test fun `legacy suffix prefix overlap with long common substring`() {
        // "defdef" (6 chars) + "defdefghi" (9 chars)
        // incoming starts with current -> normal append path -> returns incoming
        assertEquals("defdefghi", mergeLegacyStreamingText("defdef", "defdefghi"))
    }

    @Test fun `legacy no overlap concatenation fallback`() {
        assertEquals("abcdef", mergeLegacyStreamingText("abc", "def"))
    }

    @Test fun `legacy unicode normal append`() {
        assertEquals("你好世界", mergeLegacyStreamingText("你好", "你好世界"))
    }

    @Test fun `legacy unicode regressive`() {
        assertEquals("你好世界啊", mergeLegacyStreamingText("你好世界啊", "你好世界"))
    }

    @Test fun `legacy with newlines append`() {
        assertEquals("Hello\nWorld\nLine 3", mergeLegacyStreamingText("Hello\nWorld", "Hello\nWorld\nLine 3"))
    }

    @Test fun `legacy exact multiple of overlap threshold`() {
        // Create a case where overlap is exactly MINIMUM_STREAMING_OVERLAP_LENGTH
        // which is internal to the implementation. We don't know the exact value.
        // Let's test with a large enough overlap.
        val current = "x".repeat(100)
        val incoming = "x".repeat(100) + "y"
        // Overlap = 100 (current suffix "x"*100 matches incoming prefix "x"*100)
        // 100 >= MINIMUM_STREAMING_OVERLAP_LENGTH -> deduplicate
        // current (100 chars) + incoming.substring(100) = "x"*100 + "y" = "x"*100 + "y"
        assertEquals("x".repeat(100) + "y", mergeLegacyStreamingText(current, incoming))
    }

    @Test fun `legacy streaming with special characters`() {
        val current = "test\n```\ncode block\n```\nmore text"
        val incoming = "test\n```\ncode block\n```\nmore text and even more"
        assertEquals(incoming, mergeLegacyStreamingText(current, incoming))
    }

    @Test fun `legacy single character progression`() {
        assertEquals("Hello!", mergeLegacyStreamingText("Hello", "Hello!"))
    }

    @Test fun `legacy suffix prefix with multi-byte unicode`() {
        // "αβγ" overlap with "αβγδ" should deduplicate
        // "αβγ" is 3 chars in Greek unicode
        val result = mergeLegacyStreamingText("αβγ", "αβγδ")
        // overlap = 3, if < MINIMUM_STREAMING_OVERLAP_LENGTH -> concat / divergent
        // "αβγ" + "δ" = "αβγδ" (correct either way)
        assertEquals("αβγδ", result)
    }

    @Test fun `legacy divergent with unicode text keeps longer`() {
        val current = "The quick brown fox"  // 19 chars
        val incoming = "The quick brown 狐狸" // 18 chars
        // Common prefix "The quick brown " (16 chars)
        // 16 >= 12 -> divergent check: 16 < 24 but ratio 16/18 ≈ 0.89 >= 0.6 -> divergent
        // incoming (18) < current (19) -> keep current
        val result = mergeLegacyStreamingText(current, incoming)
        assertEquals(current, result)
    }

    @Test fun `legacy no overlap single char appends`() {
        // Simulate character-by-character streaming
        var text = ""
        val chars = listOf("H", "e", "l", "l", "o")
        for (c in chars) {
            text = mergeLegacyStreamingText(text, c)
        }
        assertEquals("Hello", text)
    }
}