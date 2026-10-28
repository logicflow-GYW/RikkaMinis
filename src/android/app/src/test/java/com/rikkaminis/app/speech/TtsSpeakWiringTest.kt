package com.rikkaminis.app.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1016-tts-voice] Source-shape pins for the two wirings this fix
 * depends on — the parts that are Android-coupled and therefore cannot run
 * under a plain JVM test:
 *
 * 1. **Explicit-language channel**: `TextToSpeechManager.speak` resolves the
 *    utterance language through [TtsLanguagePolicy] with the caller's
 *    `--voice` tag, and `SpeakOffloadHandler` no longer sets the language
 *    itself (the old setLanguage-then-re-detect pair made every tag collapse
 *    to the zh/en guess).
 * 2. **Sanitizer wiring**: `speak` cleans the text before the engine call
 *    (sanitize index strictly before `tts?.speak`), and treats the empty
 *    result as "nothing to speak" instead of voicing the raw text.
 *
 * Same walk-up pattern as MinisMainDocGateTest: the repo files are read as
 * text, so this runs under plain JUnit in CI and in the sandbox device
 * (device runs from the repo root; Gradle runs from the module dir).
 */
class TtsSpeakWiringTest {

    // ── file locators (walk up from user.dir; both cwd shapes supported) ──

    private fun repoFile(vararg candidates: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            val here: File = dir
            for (rel in candidates) {
                val f = File(here, rel)
                if (f.exists()) return f
            }
            dir = here.parentFile
            hops++
        }
        throw IllegalStateException(
            "repo file not found walking up from ${System.getProperty("user.dir")}"
        )
    }

    private fun managerSrc(): String =
        repoFile(
            "src/main/java/com/rikkaminis/app/speech/TextToSpeechManager.kt",
            "src/android/app/src/main/java/com/rikkaminis/app/speech/TextToSpeechManager.kt",
        ).readText()

    private fun handlerSrc(): String =
        repoFile(
            "src/main/java/com/rikkaminis/app/sandbox/offload/SpeakOffloadHandler.kt",
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SpeakOffloadHandler.kt",
        ).readText()

    /** Brace-balanced body of the function whose signature contains [signature]. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "signature not found: $signature" }
        val open = source.indexOf('{', start)
        check(open >= 0) { "no body for: $signature" }
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces for: $signature")
    }

    // ── explicit-language channel (manager side) ─────────────────────────

    @Test
    fun `speak takes the explicit language parameter`() {
        val src = managerSrc()
        val body = functionBody(src, "fun speak(")
        assertTrue(
            "speak() must resolve the utterance language through the policy with the explicit tag",
            body.contains("TtsLanguagePolicy.resolveTargetLocale(explicitLanguage"),
        )
    }

    @Test
    fun `speak no longer self-overwrites the language`() {
        val body = functionBody(managerSrc(), "fun speak(")
        assertFalse(
            "speak() must not call autoDetectAndSetLanguage — that was the unconditional " +
                "over-write that reduced every --voice tag to the zh/en guess",
            body.contains("autoDetectAndSetLanguage("),
        )
    }

    @Test
    fun `speak returns whether an utterance was actually enqueued`() {
        val body = functionBody(managerSrc(), "fun speak(")
        assertTrue(
            "speak() must return true when an utterance was enqueued (the handler's " +
                "nothing_to_speak branch depends on it)",
            body.contains("return true"),
        )
        assertTrue(
            "speak() must return false when there is nothing to speak",
            body.contains("return false"),
        )
    }

    // ── [r3-B11-F07] the engine's return code is the success signal ───────

    /**
     * `TextToSpeech.speak` answers ERROR without enqueueing anything (text over
     * the engine's limit, dead service) and never delivers onDone/onError. The
     * old `speak()` ignored that code: it returned true, set `_isSpeaking =
     * true` forever, and `android-speak` answered `{"status":"speaking"}` for
     * audio that was never played.
     */
    @Test
    fun `speak treats an engine refusal as not spoken`() {
        val body = functionBody(managerSrc(), "fun speak(")
        assertTrue(
            "speak() must compare the engine's return code with TextToSpeech.SUCCESS",
            body.contains("if (result != TextToSpeech.SUCCESS)"),
        )
        assertTrue(
            "a refused utterance carries no callback — the drain session must be rolled back",
            body.contains("drain.invalidate()"),
        )
        assertTrue(
            "the speaking flag must be cleared on the refusal path",
            body.contains("_isSpeaking.value = false"),
        )
    }

    @Test
    fun `speak no longer reports success unconditionally`() {
        val body = functionBody(managerSrc(), "fun speak(")
        assertTrue(
            "the engine call must be bound to a variable so its result can be judged — " +
                "a bare call followed by `_isSpeaking.value = true` is exactly the old bug",
            body.contains("val result = tts?.speak(speakable,"),
        )
        assertFalse(
            "the speaking flag must not be set before the result is checked",
            Regex("tts\\?\\.speak\\(speakable[^\\n]*\\n\\s*_isSpeaking\\.value = true").containsMatchIn(body),
        )
    }

    @Test
    fun `speakQueued rolls back a refused enqueue`() {
        val body = functionBody(managerSrc(), "fun speakQueued(")
        assertTrue(
            "speakQueued() must compare the engine's return code with TextToSpeech.SUCCESS",
            body.contains("if (result != TextToSpeech.SUCCESS)"),
        )
        assertTrue(
            "a refused QUEUE_ADD utterance must be removed from the drain counters",
            body.contains("drain.rejectPending()"),
        )
    }

    // ── sanitizer wiring (manager side) ───────────────────────────────────

    @Test
    fun `speak sanitizes before the engine call`() {
        val body = functionBody(managerSrc(), "fun speak(")
        val sanitize = body.indexOf("VoiceTextSanitizer.sanitize(")
        val engine = body.indexOf("tts?.speak(")
        assertTrue("speak() must call VoiceTextSanitizer.sanitize", sanitize >= 0)
        assertTrue("speak() must enqueue to the engine", engine >= 0)
        assertTrue(
            "sanitize must run BEFORE the tts?.speak engine call (cleanup first, " +
                "then the sanitized text is what gets spoken)",
            sanitize < engine,
        )
    }

    @Test
    fun `empty sanitize result is treated as nothing to speak`() {
        val body = functionBody(managerSrc(), "fun speak(")
        assertTrue(
            "the empty-after-cleanup result must bail before enqueueing (never read " +
                "the raw text instead)",
            body.contains("if (speakable.isBlank()) return false"),
        )
    }

    @Test
    fun `sanitized text is what reaches the engine`() {
        val body = functionBody(managerSrc(), "fun speak(")
        assertTrue(
            "the engine call must speak the sanitized text, not the raw input",
            body.contains("tts?.speak(speakable,"),
        )
    }

    @Test
    fun `streaming path keeps its auto-detection`() {
        val body = functionBody(managerSrc(), "fun speakQueued(")
        assertTrue(
            "speakQueued() must keep autoDetectAndSetLanguage — the streaming path has " +
                "no explicit language to honor (fix constraint)",
            body.contains("autoDetectAndSetLanguage("),
        )
    }

    @Test
    fun `spoken link phrases come from the localized resources`() {
        val src = managerSrc()
        assertTrue(
            "manager must resolve the spoken link phrases via TtsSpokenPhrases",
            src.contains("TtsSpokenPhrases.from(appContext)"),
        )
        assertTrue(src.contains("R.string.tts_spoken_link_to_host"))
        assertTrue(src.contains("R.string.tts_spoken_bare_link"))
    }

    @Test
    fun `effective language tag is observable`() {
        assertTrue(
            "currentLanguageTag() is the honest-voice contract the handler's return " +
                "body depends on",
            managerSrc().contains("fun currentLanguageTag(): String?"),
        )
    }

    // ── handler side: the set-then-overwrite pair is gone ────────────────

    @Test
    fun `handler no longer sets the language itself`() {
        assertFalse(
            "SpeakOffloadHandler must not call tts.setLanguage — speak() resolves the " +
                "language in one place now; the old eager setLanguage paired with " +
                "speak's unconditional re-detection was the override bug",
            handlerSrc().contains("tts.setLanguage("),
        )
    }

    @Test
    fun `handler passes the voice tag as the explicit language`() {
        val src = handlerSrc()
        assertTrue(
            "the speak call must pass explicitLanguage = voiceTag (named channel, " +
                "not the bare speak(text) that self-overwrote)",
            src.contains("tts.speak(text, explicitLanguage = voiceTag)"),
        )
    }

    @Test
    fun `handler reports the effective voice not the requested echo`() {
        val src = handlerSrc()
        assertTrue(
            "the voice field must be built from the effective tag",
            src.contains(".put(\"voice\", effectiveVoice)"),
        )
        assertFalse(
            "the old echo 'voiceTag ?: \"auto\"' reported a refused tag as if chosen",
            src.contains("voiceTag ?: \"auto\""),
        )
    }

    @Test
    fun `nothing speakable returns a structured error`() {
        assertTrue(
            "the handler must report nothing_to_speak instead of pretending \"speaking\"",
            handlerSrc().contains("\"nothing_to_speak\""),
        )
    }

    // ── resources: every locale defines the spoken link phrases ──────────

    @Test
    fun `all locale files define the spoken link phrases`() {
        val locales =
            listOf(
                "values",
                "values-de",
                "values-ja",
                "values-ko",
                "values-ru",
                "values-zh",
                "values-zh-rTW",
            )
        for (loc in locales) {
            val f = repoFile(
                "src/main/res/$loc/strings.xml",
                "src/android/app/src/main/res/$loc/strings.xml",
            )
            val s = f.readText()
            assertTrue("$loc must define tts_spoken_link_to_host", s.contains("tts_spoken_link_to_host"))
            assertTrue("$loc must define tts_spoken_bare_link", s.contains("tts_spoken_bare_link"))
        }
        assertEquals(
            "the spoken link phrases must exist in exactly the 7 locale files",
            7,
            locales.size,
        )
    }
}
