package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/chat-tuning-panel] Tripwire tests for the Chat Tuning defaults.
 *
 * Every DEFAULT below is the exact pre-panel literal that used to be
 * hard-coded at the consumer site, so an untouched install renders
 * byte-identically. If a consumer's literal is ever retuned, retune the
 * DEFAULT in the same commit — a failure here is the reminder that the
 * panel and the code would otherwise silently disagree.
 */
class ChatTuningPrefsTest {

    @Test
    fun `defaults match the pre-panel hard-coded literals`() {
        assertEquals(32, ChatTuningPrefs.SCROLL_NEAR_BOTTOM_DEFAULT)    // ChatScreen, T128 (from 90dp)
        assertEquals(16, ChatTuningPrefs.PREWARM_ROW_LIMIT_DEFAULT)     // ChatScreen cold-open prewarm
        assertEquals(20, ChatTuningPrefs.CODE_PREVIEW_LINES_DEFAULT)    // StreamingMarkdownText fold
        assertEquals(10, ChatTuningPrefs.TABLE_PREVIEW_ROWS_DEFAULT)    // StreamingMarkdownText fold
        assertEquals(24, ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_DEFAULT)  // StreamingMarkdownText BaseLineHeightDefault
        assertEquals(6, ChatTuningPrefs.INPUT_MAX_LINES_DEFAULT)        // ChatInputArea composer maxLines
        assertEquals(120, ChatTuningPrefs.SEND_SWIPE_THRESHOLD_DEFAULT) // ChatInputArea swipe-up-to-send
    }

    @Test
    fun `every default sits inside its declared bounds`() {
        assertBounds(
            ChatTuningPrefs.SCROLL_NEAR_BOTTOM_MIN,
            ChatTuningPrefs.SCROLL_NEAR_BOTTOM_DEFAULT,
            ChatTuningPrefs.SCROLL_NEAR_BOTTOM_MAX,
        )
        assertBounds(
            ChatTuningPrefs.PREWARM_ROW_LIMIT_MIN,
            ChatTuningPrefs.PREWARM_ROW_LIMIT_DEFAULT,
            ChatTuningPrefs.PREWARM_ROW_LIMIT_MAX,
        )
        assertBounds(
            ChatTuningPrefs.CODE_PREVIEW_LINES_MIN,
            ChatTuningPrefs.CODE_PREVIEW_LINES_DEFAULT,
            ChatTuningPrefs.CODE_PREVIEW_LINES_MAX,
        )
        assertBounds(
            ChatTuningPrefs.TABLE_PREVIEW_ROWS_MIN,
            ChatTuningPrefs.TABLE_PREVIEW_ROWS_DEFAULT,
            ChatTuningPrefs.TABLE_PREVIEW_ROWS_MAX,
        )
        assertBounds(
            ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_MIN,
            ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_DEFAULT,
            ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_MAX,
        )
        assertBounds(
            ChatTuningPrefs.INPUT_MAX_LINES_MIN,
            ChatTuningPrefs.INPUT_MAX_LINES_DEFAULT,
            ChatTuningPrefs.INPUT_MAX_LINES_MAX,
        )
        assertBounds(
            ChatTuningPrefs.SEND_SWIPE_THRESHOLD_MIN,
            ChatTuningPrefs.SEND_SWIPE_THRESHOLD_DEFAULT,
            ChatTuningPrefs.SEND_SWIPE_THRESHOLD_MAX,
        )
        // [composer-glass-1115] The composer-glass pair. The switch itself has no
        // bounds; the two numbers it drives do.
        assertBounds(
            ChatTuningPrefs.COMPOSER_GLASS_BLUR_MIN,
            ChatTuningPrefs.COMPOSER_GLASS_BLUR_DEFAULT,
            ChatTuningPrefs.COMPOSER_GLASS_BLUR_MAX,
        )
        assertBounds(
            ChatTuningPrefs.COMPOSER_GLASS_TINT_MIN,
            ChatTuningPrefs.COMPOSER_GLASS_TINT_DEFAULT,
            ChatTuningPrefs.COMPOSER_GLASS_TINT_MAX,
        )
    }

    @Test
    fun `ChatTuningValues defaults mirror the constants`() {
        val v = ChatTuningValues()
        assertEquals(ChatTuningPrefs.SCROLL_NEAR_BOTTOM_DEFAULT, v.scrollNearBottomDp)
        assertEquals(ChatTuningPrefs.PREWARM_ROW_LIMIT_DEFAULT, v.prewarmRowLimit)
        assertEquals(ChatTuningPrefs.CODE_PREVIEW_LINES_DEFAULT, v.codePreviewLines)
        assertEquals(ChatTuningPrefs.TABLE_PREVIEW_ROWS_DEFAULT, v.tablePreviewRows)
        assertEquals(ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_DEFAULT, v.markdownLineHeightSp)
        assertEquals(ChatTuningPrefs.INPUT_MAX_LINES_DEFAULT, v.inputMaxLines)
        assertEquals(ChatTuningPrefs.SEND_SWIPE_THRESHOLD_DEFAULT, v.sendSwipeThresholdDp)
        // [composer-glass-1115] A default-on effect would bill every user for a
        // per-frame recording of the message list; the default pair must also be
        // the values the panel shows, or a user's first open of the switch
        // produces a look nobody chose.
        assertEquals(ChatTuningPrefs.COMPOSER_GLASS_ENABLED_DEFAULT, v.composerGlassEnabled)
        assertEquals(ChatTuningPrefs.COMPOSER_GLASS_BLUR_DEFAULT, v.composerGlassBlurDp)
        assertEquals(ChatTuningPrefs.COMPOSER_GLASS_TINT_DEFAULT, v.composerGlassTintPct)
    }

    @Test
    fun `all keys are distinct and complete`() {
        val keys = listOf(
            ChatTuningPrefs.KEY_SCROLL_NEAR_BOTTOM_DP,
            ChatTuningPrefs.KEY_PREWARM_ROW_LIMIT,
            ChatTuningPrefs.KEY_CODE_PREVIEW_LINES,
            ChatTuningPrefs.KEY_TABLE_PREVIEW_ROWS,
            ChatTuningPrefs.KEY_MARKDOWN_LINE_HEIGHT_SP,
            ChatTuningPrefs.KEY_INPUT_MAX_LINES,
            ChatTuningPrefs.KEY_SEND_SWIPE_THRESHOLD_DP,
            // [composer-glass-1115] Three knobs, three keys. This list is the
            // guard that ALL_KEYS covers every declared key — adding a knob to
            // the object without wiring its key here fails HERE rather than
            // silently missing the ChatScreen listener (the effect would then
            // never appear until the next cold start).
            ChatTuningPrefs.KEY_COMPOSER_GLASS_ENABLED,
            ChatTuningPrefs.KEY_COMPOSER_GLASS_BLUR_DP,
            ChatTuningPrefs.KEY_COMPOSER_GLASS_TINT_PCT,
        )
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.toSet(), ChatTuningPrefs.ALL_KEYS)
        // The prefs file name is part of the storage contract — the panel's
        // change listener and ConfigBuiltins' field registration must agree.
        assertEquals("minis_chat_tuning_prefs", ChatTuningPrefs.PREFS)
    }

    private fun assertBounds(min: Int, default: Int, max: Int) {
        assertTrue("min($min) must be < max($max)", min < max)
        assertTrue("default($default) must sit within [$min, $max]", default in min..max)
    }
}
