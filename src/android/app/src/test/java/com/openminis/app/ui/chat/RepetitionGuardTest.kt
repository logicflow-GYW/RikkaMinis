package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/hermes-tier1] JVM tests for [isRepetitionDominated] — the Hermes
 * `agent/repetition_guard.py` port that aborts length-wall continuation
 * when the model's output is dominated by a degenerate repetition loop.
 *
 * Contract (mirrors the Python original):
 *  - Fragments < 400 chars: never trip (fail-open, legitimately repeated
 *    tokens in short truncations are fine to continue).
 *  - Fast path: one normalized line repeated >= 5 times covering >= 50% of
 *    the fragment.
 *  - General path: a 60+ char window recurring >= max(5, n*0.5/60) times.
 *  - Legitimate content (citations, headings, similar code blocks, logs
 *    with repeated prefixes) must NOT trip.
 */
class RepetitionGuardTest {

    // ── fail-open paths ───────────────────────────────────────────────

    @Test fun `short fragments never trip`() {
        val repeated = "error: connection reset by peer\n".repeat(12) // 372 chars
        assertTrue(repeated.length < REPETITION_MIN_FRAGMENT_LENGTH)
        assertFalse(isRepetitionDominated(repeated))
    }

    @Test fun `empty and plain text never trip`() {
        assertFalse(isRepetitionDominated(""))
        val prose = ("这是一段正常的中文回复，讨论一个技术话题，没有任何退化重复。" +
            "内容持续演进，每句话都带来新的信息，长度超过四百字符的阈值。" +
            "继续补充更多不同的句子，确保文本足够长以进入检测窗口范围。" +
            "模型在正常生成时不会复述同一个短语，这里刻意保持多样性。").repeat(2)
        assertFalse(isRepetitionDominated(prose))
    }

    // ── line-echo fast path ───────────────────────────────────────────

    @Test fun `repeated line covering majority trips`() {
        // 60-char lines repeated 8x: length well past the 400 floor and
        // coverage way over half.
        val line = "===========================================[WARN] loop".padEnd(60, '=')
        val text = line + "\n" + (line + "\n").repeat(7) + "tail"
        assertTrue(text.length >= REPETITION_MIN_FRAGMENT_LENGTH)
        assertTrue(isRepetitionDominated(text))
    }

    @Test fun `repeated line below repeat-count does not trip`() {
        // Same line only 4 times (below MIN_REPEAT_COUNT=5), then genuinely
        // VARIED filler lines. NOTE: the filler must not be a fixed template
        // with only digits changing — template rows share a 60-char window
        // and legitimately trip the window path (that is the guard's
        // documented shape, matching Hermes' Python original).
        val line = " uniquely different content each time grows the reply"
        val filler = (1..9).joinToString("\n") { i ->
            val words = mutableListOf<String>()
            for (w in 0 until 12) {
                words.add("w${w}x${i}${'a' + (w * 7 + i * 3) % 26}${(i * 31 + w * 17) % 997}")
            }
            words.joinToString(" ")
        }
        val text = (line + "\n").repeat(4) + "\n" + filler
        assertTrue(text.length >= REPETITION_MIN_FRAGMENT_LENGTH)
        assertFalse(isRepetitionDominated(text))
    }

    // ── general window path ───────────────────────────────────────────

    @Test fun `repeated 60char window without line alignment trips`() {
        // Build a >400-char fragment whose middle is one 60-char window
        // repeated 6 times with NO newlines (defeats the line fast path).
        val window = "0123456789".repeat(6) // exactly 60 chars
        val text = "prefix-unique-" + window.repeat(6) + "-suffix-unique-tail-padding-pad"
        assertTrue(text.length >= REPETITION_MIN_FRAGMENT_LENGTH)
        assertTrue(isRepetitionDominated(text))
    }

    @Test fun `whitespace-normalized line repeats trip`() {
        // Lines differing only in surrounding whitespace count as the same
        // normalized line (the guard trims before counting).
        val core = "the same diagnostic line emitted over and over again ok"
        val text = ("  $core  \n" + core + "\t\n" + "   $core\n" + "$core   \n" + core + "\n").repeat(1) +
            "x".repeat(120)
        assertTrue(text.length >= REPETITION_MIN_FRAGMENT_LENGTH)
        assertTrue(isRepetitionDominated(text))
    }

    // ── legitimate shapes must not trip ───────────────────────────────

    @Test fun `code with repeated short lines does not trip`() {
        // Real code: many repeated SHORT lines (imports, braces) — each line
        // is far below the 60-char window and the repeat count of any single
        // long line stays low.
        val header = (1..12).joinToString("\n") { "import package.module$it /* $it */" }
        val body = (1..10).joinToString("\n") { i ->
            """
            fun handler$i(x: Int): Int {
                val doubled$i = x * 2 + $i
                return doubled$i * handler${i - 1}(x)
            }
            """.trimIndent()
        }
        val text = "$header\n$body\n${"val closing = listOf(1, 2, 3).map { it * 7 }.sum()"}"
        assertTrue(text.length >= REPETITION_MIN_FRAGMENT_LENGTH)
        assertFalse(isRepetitionDominated(text))
    }

    @Test fun `repeated citation blocks below dominance do not trip`() {
        // Three citations of the same source + VARIED paragraphs. NOTE: the
        // paragraphs must be genuinely varied prose — a shared sentence
        // template with only digits changing shares a 60-char window across
        // rows and legitimately trips the window path (Hermes' Python
        // original behaves identically; that similarity pile-up IS the
        // degenerate shape the guard exists for).
        val citation = "[1] Nous Research. Hermes Agent: repetition_guard.py, lines 1-61. 2026."
        val paragraphs = (1..14).joinToString("\n") { i ->
            val topic = when (i % 7) {
                0 -> "the retrieval pipeline stages and their interaction"
                1 -> "token accounting across retry boundaries"
                2 -> "fallback chain escalation semantics"
                3 -> "the compaction anchor placement rules"
                4 -> "stream throttle tiers and their thresholds"
                5 -> "tool pairing repair on sliced transcripts"
                else -> "the watchdog race-safety protocol"
            } + " — variant $i with unique trailing detail ${(i * 137) % 991}"
            "Section $i covers $topic."
        }
        val text = (citation + "\n").repeat(3) + "\n" + paragraphs
        assertTrue(text.length >= REPETITION_MIN_FRAGMENT_LENGTH)
        assertFalse(isRepetitionDominated(text))
    }

    // ── constant sanity (mirrors Hermes values) ───────────────────────

    @Test fun `constants match hermes originals`() {
        assertEquals(400, REPETITION_MIN_FRAGMENT_LENGTH)
        assertEquals(3, LENGTH_WALL_MIN_SEAM_OVERLAP) // neighbor contract unchanged
    }
}
