package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-length-wall-seam-dedup] regression tests.
 *
 * Field symptom (every-session, user-reported): a reply truncated by the
 * output-token wall (finish_reason="length") continued with the model
 * re-emitting a phrase it had already output, and BOTH halves were kept:
 *
 *   `…站在一个` + `，是因为它确实已经站在一个` + `一个比较高的位置了…`
 *
 * mergeLengthWallSeam must trim that seam so no already-output phrase
 * survives twice.
 */
class ChatLengthWallLogicTest {

    // ── lengthWallSeamOverlap ─────────────────────────────────────────────

    @Test fun `overlap detects exact suffix-prefix match`() {
        assertEquals(
            2, // "一个" — the shared boundary chars
            lengthWallSeamOverlap("已经站在一个", "一个比较高的位置了"),
        )
    }

    @Test fun `overlap zero when no shared boundary`() {
        assertEquals(0, lengthWallSeamOverlap("第一句话。", "第二句话。"))
    }

    @Test fun `overlap zero on empty inputs`() {
        assertEquals(0, lengthWallSeamOverlap("", "abc"))
        assertEquals(0, lengthWallSeamOverlap("abc", ""))
    }

    @Test fun `overlap full when continuation restarts entire truncated text`() {
        // Model re-emitted the WHOLE truncated reply as its continuation head.
        val truncated = "完整的一小段被截断"
        assertEquals(
            truncated.length,
            lengthWallSeamOverlap(truncated, truncated + "然后继续"),
        )
    }

    // ── mergeLengthWallSeam ───────────────────────────────────────────────

    @Test fun `field symptom - adjacent seam repetition is trimmed`() {
        // Adjacent seam shape: continuation RESTARTS with the truncated tail
        // (the "致命伤致命" class of duplication, at the length-wall seam):
        val truncated = "你觉得优化空间有限，是因为它确实已经站在一个"
        val continuation = "已经站在一个一个比较高的位置了——这不全是坏事。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        // "已经站在一个已经站在一个" must not survive — the seam dup is trimmed.
        assertFalse(merged.contains("站在一个已经站在一个"))
        assertEquals(
            "你觉得优化空间有限，是因为它确实已经站在一个一个比较高的位置了——这不全是坏事。",
            merged,
        )
    }

    @Test fun `field symptom - back-up repetition is trimmed`() {
        // The exact user-reported shape: the model backs up to an earlier
        // semantic anchor and re-emits the whole subordinate clause. The
        // duplicated clause is a suffix of the truncated text AND a prefix
        // of the continuation, so the seam trim removes it entirely:
        val truncated = "你觉得优化空间有限，是因为它确实已经站在一个"
        val continuation = "，是因为它确实已经站在一个一个比较高的位置了——这不全是坏事。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        assertEquals(
            "你觉得优化空间有限，是因为它确实已经站在一个一个比较高的位置了——这不全是坏事。",
            merged,
        )
        // The subordinate clause survives exactly once.
        assertEquals(1, countOccurrences(merged, "是因为它确实已经站在一个"))
    }

    @Test fun `field symptom 2 - bold marker adjacent duplication is trimmed`() {
        // Adjacent seam: continuation restarts with the truncated tail.
        val truncated = "所以真实结论是：优化空间的性质变了，但**没消失。**"
        val continuation = "**没消失。**后续内容。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        // "**没消失。**" appears exactly once; no orphan "**" residue.
        assertEquals(1, countOccurrences(merged, "**没消失。**"))
        assertEquals(truncated + "后续内容。", merged)
    }

    @Test fun `short overlap below threshold is kept (legitimate join)`() {
        // Overlap "。" (1 char) is legitimate punctuation flow, not duplication.
        val truncated = "第一段结束。"
        val continuation = "，第二段开始。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        assertEquals(truncated + continuation, merged)
    }

    @Test fun `no overlap passes through unchanged`() {
        assertEquals("ABC" + "DEF", mergeLengthWallSeam("ABC", "DEF"))
    }

    @Test fun `empty continuation returns truncated`() {
        assertEquals("abc", mergeLengthWallSeam("abc", ""))
    }

    @Test fun `empty truncated returns continuation`() {
        assertEquals("abc", mergeLengthWallSeam("", "abc"))
    }

    @Test fun `whole-block pure overlap collapses to single copy`() {
        val text = "整段重复的文本"
        assertEquals(text, mergeLengthWallSeam(text, text))
    }

    @Test fun `threshold boundary - exactly 3 chars overlap trims`() {
        // 3-char overlap == LENGTH_WALL_MIN_SEAM_OVERLAP → trims.
        val a = "一二三"
        val b = "一二三四五"
        // suffix of a == prefix of b of length 3: "一二三"
        val merged = mergeLengthWallSeam(a, b)
        assertEquals(a + "四五", merged)
    }

    @Test fun `threshold boundary - 2 chars overlap kept`() {
        // 2-char overlap < 3 threshold → plain concat (no trim).
        val a = "一二"
        val b = "一二三四五"
        // suffix of a of length 2 = "一二" == prefix of b (first 2)
        val merged = mergeLengthWallSeam(a, b)
        assertEquals(a + b, merged)
    }

    @Test fun `field symptom - 4 char phrase repetition is trimmed`() {
        // User-reported "业界几乎" class: a 4-char phrase that the OLD 6-char
        // threshold could not catch. The model backs up and re-emits it.
        val truncated = "……主动走出了一条业界几乎"
        val continuation = "业界几乎没人走的路。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        assertEquals("……主动走出了一条业界几乎没人走的路。", merged)
        assertEquals(1, countOccurrences(merged, "业界几乎"))
    }

    @Test fun `field symptom - 5 char sentence head repetition is trimmed`() {
        // "春天来了。" (5 chars incl. 。) — the model restarts the sentence
        // head it already output. Below the OLD 6 threshold, now caught.
        val truncated = "春天来了。"
        val continuation = "春天来了。风变得温柔起来。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        assertEquals("春天来了。风变得温柔起来。", merged)
        assertEquals(1, countOccurrences(merged, "春天来了。"))
    }

    // ── [T-length-wall-seam-punct] leading-punctuation blind spot ─────────

    @Test fun `leading comma before repeated phrase is trimmed`() {
        // Field symptom: the model re-emits the truncated tail behind a
        // joining comma. The raw scan compares against the comma and missed
        // the 4-char overlap entirely (the "修过但没解决" gap).
        val truncated = "……主动走出了一条业界几乎"
        val continuation = "，主动走出了一条业界几乎没人走的路。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        assertEquals("……主动走出了一条业界几乎没人走的路。", merged)
        assertEquals(1, countOccurrences(merged, "主动走出了一条业界几乎"))
    }

    @Test fun `leading full stop before repeated sentence is trimmed`() {
        val truncated = "春天来了。"
        val continuation = "。春天来了。风变得温柔起来。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        assertEquals("春天来了。风变得温柔起来。", merged)
        assertEquals(1, countOccurrences(merged, "春天来了。"))
    }

    @Test fun `leading punctuation kept on non-repeat join`() {
        // A comma that fronts genuinely NEW text (no overlap) is legitimate
        // sentence flow — it must survive, not be stripped by overreach.
        val truncated = "第一段结束"
        val continuation = "，第二段开始。"
        val merged = mergeLengthWallSeam(truncated, continuation)
        assertEquals("第一段结束，第二段开始。", merged)
    }

    @Test fun `leading punctuation with sub-threshold overlap is kept`() {
        // 2-char overlap behind a comma (< threshold) — plain concat, the
        // comma and the short overlap both survive.
        val truncated = "一二"
        val continuation = "，一二三四五"
        val merged = mergeLengthWallSeam(truncated, continuation)
        assertEquals("一二，一二三四五", merged)
    }

    @Test fun `stripLeadingPunctuation strips full run and stops at text`() {
        assertEquals("内容", stripLeadingPunctuation("，。、内容"))
        assertEquals("内容", stripLeadingPunctuation("内容"))
        assertEquals("", stripLeadingPunctuation("，。、"))
        assertEquals("A。", stripLeadingPunctuation("。A。"))
    }

    // ── lengthWallReminder ────────────────────────────────────────────────

    @Test fun `reminder embeds the truncated tail as anchor`() {
        val reminder = lengthWallReminder("…已经站在一个")
        assertTrue(reminder.contains("cut off mid-sentence"))
        assertTrue(reminder.contains("…已经站在一个"))
        assertTrue(reminder.contains("Do NOT repeat"))
        assertTrue(reminder.startsWith("<system-reminder>"))
        assertTrue(reminder.endsWith("</system-reminder>"))
    }

    @Test fun `reminder with empty tail still valid`() {
        val reminder = lengthWallReminder("")
        assertTrue(reminder.contains("cut off mid-sentence"))
        // No dangling quote content: the anchor shows as "".
        assertTrue(reminder.contains(": \"\""))
    }

    // ── end-to-end turn-fold simulation ───────────────────────────────────

    @Test fun `turn fold simulation - accumulatedText across length wall`() {
        // Simulates the runAgentLoop fold semantics with the fix:
        var accumulatedText = ""
        var lastTurnWasLengthWall = false

        fun fold(turnText: String, finishReason: String) {
            accumulatedText =
                if (lastTurnWasLengthWall && turnText.isNotEmpty()) {
                    mergeLengthWallSeam(accumulatedText, turnText)
                } else {
                    accumulatedText + turnText
                }
            lastTurnWasLengthWall = finishReason == "length" && turnText.isNotEmpty()
        }

        // Turn 1: truncated by the wall, ending mid-sentence.
        fold("你觉得优化空间有限，是因为它确实已经站在一个", "length")
        // Turn 2: ADJACENT restart (model re-emits the truncated tail).
        fold("已经站在一个一个比较高的位置了——这不全是坏事。", "stop")

        assertEquals(
            "你觉得优化空间有限，是因为它确实已经站在一个一个比较高的位置了——这不全是坏事。",
            accumulatedText,
        )
    }

    @Test fun `turn fold simulation - normal tool boundary never trims`() {
        var accumulatedText = ""
        var lastTurnWasLengthWall = false

        fun fold(turnText: String, finishReason: String) {
            accumulatedText =
                if (lastTurnWasLengthWall && turnText.isNotEmpty()) {
                    mergeLengthWallSeam(accumulatedText, turnText)
                } else {
                    accumulatedText + turnText
                }
            lastTurnWasLengthWall = finishReason == "length" && turnText.isNotEmpty()
        }

        // Normal tool round-trip: turn1 ends with tool_use (finish=tool_use),
        // turn2 opens with the SAME phrase deliberately (legitimate recap).
        fold("我先分析一下。", "tool_use")
        fold("我先分析一下。这个问题有三个层面。", "stop")

        // No trim must have happened — the repetition is the model's own
        // legitimate recap after a tool round, not a length-wall seam.
        assertEquals("我先分析一下。我先分析一下。这个问题有三个层面。", accumulatedText)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = haystack.indexOf(needle, idx)
            if (idx < 0) break
            count++
            idx += 1
        }
        return count
    }
}
