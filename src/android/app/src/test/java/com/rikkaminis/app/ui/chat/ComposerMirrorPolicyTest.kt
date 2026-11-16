package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [voice-ime-1116] Behavioural tests for [shouldMirrorComposerText].
 *
 * The composer keeps two copies of the text (the field's `TextFieldValue` and
 * the ViewModel's `inputText`) and a mirror effect that copies the ViewModel
 * back into the field. The old predicate was `field != vm`, which cannot tell
 * "an external write happened" from "our own debounced write is still in
 * flight". Voice dictation is exactly the second case, and the mirror used to
 * roll the field back mid-dictation — an IME whose composing region is wiped
 * ends its recording session (on-device: "it just stops mid-sentence").
 *
 * Every expectation below is a literal; nothing is derived from the function
 * under test.
 */
class ComposerMirrorPolicyTest {

    @Test
    fun `own debounced lag is never mirrored`() {
        // We wrote "今天下午三点" into the ViewModel; a new dictation burst has
        // already moved the field past it. Mirroring here would delete it.
        assertFalse(
            shouldMirrorComposerText(
                vmText = "今天下午三点",
                selfWrittenText = "今天下午三点",
                fieldText = "今天下午三点开会",
                imeDictating = { false },
            ),
        )
    }

    @Test
    fun `external write is mirrored when the field has not caught up`() {
        // A draft restore / edit prefill / refused-send put new text in the
        // ViewModel that this composer never wrote.
        assertTrue(
            shouldMirrorComposerText(
                vmText = "恢复的草稿正文",
                selfWrittenText = "旧内容",
                fieldText = "",
                imeDictating = { false },
            ),
        )
    }

    @Test
    fun `nothing is mirrored when both copies already agree`() {
        assertFalse(
            shouldMirrorComposerText(
                vmText = "same",
                selfWrittenText = "",
                fieldText = "same",
                imeDictating = { false },
            ),
        )
    }

    @Test
    fun `a dictating IME blocks the mirror even for an external write`() {
        assertFalse(
            shouldMirrorComposerText(
                vmText = "外部写入",
                selfWrittenText = "",
                fieldText = "正在说的话",
                imeDictating = { true },
            ),
        )
    }

    @Test
    fun `the dictation probe is never consulted for our own writes`() {
        var consulted = false
        val mirrored = shouldMirrorComposerText(
            vmText = "abc",
            selfWrittenText = "abc",
            fieldText = "abcdef",
            imeDictating = {
                consulted = true
                true
            },
        )
        assertFalse(mirrored)
        assertFalse(
            "the cheap string tests must short-circuit before the AudioManager binder call",
            consulted,
        )
    }

    @Test
    fun `the dictation probe is never consulted when the field already matches`() {
        var consulted = false
        val mirrored = shouldMirrorComposerText(
            vmText = "abc",
            selfWrittenText = "",
            fieldText = "abc",
            imeDictating = {
                consulted = true
                true
            },
        )
        assertFalse(mirrored)
        assertFalse(consulted)
    }
}
