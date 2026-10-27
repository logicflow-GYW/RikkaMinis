package com.rikkaminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

/**
 * [fix/da1016-tts-voice] Pins the TTS language decision that used to be an
 * unconditional call inside TextToSpeechManager.speak(): it overwrote
 * whatever language the offload handler had just set from `--voice` with the
 * zh/en binary guess, so `android-speak --voice fr-FR` spoke en-US while the
 * JSON body still echoed "fr-FR".
 *
 * Expectations are literals. The ill-formed-tag arms are pinned against
 * measured OpenJDK 17 behaviour (probe/jvm/LocaleGroundTruth.kt): forLanguageTag
 * never throws for BCP-47 input, ill-formed → language subtag "" (und), which
 * the policy treats as "no usable explicit language" and routes to detection.
 */
class TtsLanguagePolicyTest {

    // ── the bug arm: an explicit tag must not be overwritten ──────────────

    @Test
    fun `explicit zh is not overridden for english text`() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            TtsLanguagePolicy.resolveTargetLocale("zh-CN", "Hello world"),
        )
    }

    @Test
    fun `explicit tag wins against the other script`() {
        assertEquals(
            Locale.US,
            TtsLanguagePolicy.resolveTargetLocale("en-US", "中文文本"),
        )
        assertEquals(
            Locale.FRANCE,
            TtsLanguagePolicy.resolveTargetLocale("fr-FR", "中文也不覆盖"),
        )
    }

    @Test
    fun `explicit tag is normalised by the platform parser`() {
        // Lower-case region subtag normalises to zh-CN (measured).
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            TtsLanguagePolicy.resolveTargetLocale("zh-cn", "Hello world"),
        )
    }

    // ── legacy behaviour: no explicit tag keeps the binary detection ──────

    @Test
    fun `no explicit tag keeps the legacy han detection`() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            TtsLanguagePolicy.resolveTargetLocale(null, "你好世界"),
        )
        assertEquals(Locale.US, TtsLanguagePolicy.resolveTargetLocale(null, "Hello world"))
        assertEquals(Locale.US, TtsLanguagePolicy.resolveTargetLocale(null, ""))
    }

    @Test
    fun `autoDetectLocale is the same binary rule`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, TtsLanguagePolicy.autoDetectLocale("你好"))
        assertEquals(Locale.US, TtsLanguagePolicy.autoDetectLocale("hello"))
        // Extension A block still counts as Han (mirrors the recognizer set).
        assertEquals(Locale.SIMPLIFIED_CHINESE, TtsLanguagePolicy.autoDetectLocale("\u3400"))
    }

    // ── blank / unusable tags still reach the legacy path ────────────────

    @Test
    fun `blank explicit tag is treated as no explicit tag`() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            TtsLanguagePolicy.resolveTargetLocale("", "你好"),
        )
        assertEquals(Locale.US, TtsLanguagePolicy.resolveTargetLocale("   ", "hello"))
    }

    @Test
    fun `ill formed tag never crashes and falls back to detection`() {
        // "!!" → und on this JVM: no exception, no usable language.
        assertEquals(Locale.US, TtsLanguagePolicy.resolveTargetLocale("!!", "hello"))
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            TtsLanguagePolicy.resolveTargetLocale("!!", "你好"),
        )
        // Private-use-only tag: language subtag resolves empty → fallback.
        assertEquals(Locale.US, TtsLanguagePolicy.resolveTargetLocale("x-privat", "hello"))
    }

    @Test
    fun `shape valid unknown code is still handed to the engine`() {
        // "not" parses to a non-empty language subtag, so the policy does not
        // second-guess it — only the engine knows which languages it ships,
        // and it reports refusal through setLanguage's return code.
        val resolved = TtsLanguagePolicy.resolveTargetLocale("not", "你好")
        assertEquals("not", resolved.language)
        assertNotEquals(Locale.SIMPLIFIED_CHINESE, resolved)
    }

    // ── phrase source: the R.string path falls back to English defaults ──

    @Test
    fun `sanitizer defaults are the english link phrases`() {
        // TtsSpokenPhrases.from(null) == these defaults (the Android-coupled
        // from() itself is pinned by TtsSpeakWiringTest's source-shape arms).
        val defaults = VoiceTextSanitizer.LinkPhrases()
        assertEquals("link to %s", defaults.linkToHost)
        assertEquals("link", defaults.bareLink)
    }
}
