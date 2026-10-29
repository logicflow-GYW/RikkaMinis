package com.rikkaminis.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.rikkaminis.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages Android TextToSpeech engine for reading assistant responses aloud.
 * Supports en-US and zh-CN with automatic language detection, plus an
 * explicit-language channel and spoken-text cleanup ([speak]).
 */
class TextToSpeechManager : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeech"
        // [T-android-tts-intranumber-guard] The sentence-boundary set moved to
        // SpeechSentenceSplitter.SENTENCE_ENDERS — a single source of truth
        // shared by every TTS path, so they can't drift.
    }

    private var tts: TextToSpeech? = null
    var isInitialized = false
        private set

    // [fix/da1016-tts-voice] Mirror of the language the engine is currently
    // set to — only tags the engine ACCEPTED are recorded (a rejected
    // setLanguage leaves the engine on its previous language), so
    // [currentLanguageTag] never reports a language the engine refused.
    // @Volatile: written from the main thread (speak / setLanguage) and read
    // back by the offload handler thread for its return body.
    @Volatile
    private var currentLocaleTag: String? = null

    // [fix/da1016-tts-voice] Application context for the spoken link phrases
    // ([TtsSpokenPhrases]). Application-scoped, so holding it cannot leak an
    // Activity.
    private var appContext: Context? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // [fix/audit-b20 / T8-L5] Queue-drain bookkeeping. The pause/resume layer
    // (isPaused / togglePause / pausedAtIndex) had no caller anywhere — Android
    // TTS has no native pause and no UI ever exposed one — so it is gone. The
    // drain tracker is what keeps `isSpeaking` accurate: utterances handed to
    // the engine, and utterances that finished (done or error).
    // [sweep1014-B20b] Session epoch: speak()/stop() start a new epoch and
    // every utterance id carries its epoch prefix, so callbacks from a flushed
    // session can never corrupt the current one. The two plain Int counters
    // also moved into the tracker as atomics — they were written on the main
    // thread (speak) and read/written on the Binder thread (onDone/onError),
    // with no visibility guarantee.
    private val drain = TtsDrainTracker()

    /**
     * Rolling buffer of partial-streaming text that hasn't hit a sentence
     * boundary yet. Cleared by [flush] or [stop]. See [appendText].
     */
    private val sentenceBuffer = StringBuilder()

    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 3.0f)
            tts?.setSpeechRate(field)
        }

    var speechPitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    var speechVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /**
     * Initializes the TTS engine. Must be called before speaking.
     *
     * [T-android-tts-init-leak] Re-init is defensive: shutdown the previous
     * engine first so a repeated [init] doesn't leak the prior [TextToSpeech]
     * instance (which holds native audio resources until shutdown).
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        tts?.let { old -> old.shutdown() }
        tts = null
        isInitialized = false
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.apply {
                setSpeechRate(speechRate)
                setPitch(speechPitch)
                setOnUtteranceProgressListener(createProgressListener())
            }
            // [fix/da1016-tts-voice] Route the default language through the
            // single chokepoint so the mirror knows the engine's state from
            // the start — a --voice tag the engine later rejects then reports
            // the language that will actually be spoken, not "unknown".
            applyLanguage(Locale.US)
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /**
     * Speaks text immediately, interrupting any ongoing speech (QUEUE_FLUSH).
     *
     * [fix/da1016-tts-voice] **Text cleanup first**: the text is sanitized
     * before the engine reads it — markdown syntax, bare URLs and
     * non-speakable glyphs are stripped or rewritten as link phrases
     * ([VoiceTextSanitizer], now wired from the R.string source its own KDoc
     * describes). When nothing speakable remains (e.g. a message that was
     * only a fenced code block) nothing is voiced and the result is `false`;
     * callers must not fall back to reading the raw text. Language detection
     * runs on the sanitized text — what is actually spoken.
     *
     * [fix/da1016-tts-voice] **Explicit language**: [explicitLanguage] is a
     * caller-pinned BCP-47 tag (android-speak `--voice`) and wins over
     * auto-detection, which only runs when it is null/blank. The previous
     * unconditional detection here overwrote whatever the offload handler had
     * just set from `--voice`, collapsing every tag to the zh/en binary
     * guess. The streaming path ([appendText] / [flush] → [speakQueued]) keeps
     * per-slice auto-detection: it has no explicit language to honor.
     *
     * [r3-B11-F07] @return true only when the engine ACCEPTED the utterance
     *   (`TextToSpeech.speak` == SUCCESS). An engine refusal (text over the
     *   engine's length limit, or a dead service) returns false: it delivers no
     *   callback, so reporting it as enqueued left `isSpeaking` stuck true and
     *   told `android-speak` the text was "speaking" when nothing played.
     */
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        if (!isInitialized || text.isBlank()) return false

        // [T-android-voice-text-sanitizer] Empty after cleanup = nothing to
        // speak — leave the engine (and any ongoing session) untouched.
        val speakable = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext))
        if (speakable.isBlank()) return false

        // [sweep1014-B20b] Bump the epoch BEFORE enqueueing: the utterance the
        // engine flushes still delivers onDone/onError asynchronously — with a
        // new epoch those stale callbacks are ignored instead of corrupting
        // this session's fresh counters.
        val sessionEpoch = drain.newSession()
        sentenceBuffer.setLength(0)

        applyLanguage(TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, speakable))

        val params = buildSpeechParams()
        // [r3-B11-F07] The engine's return code is the ONLY signal that nothing
        // will be spoken: `speak` answers ERROR (-1) when it refuses the text
        // (over getMaxSpeechInputLength(), or a service that died) and then
        // never delivers onDone/onError, so the old unconditional
        // `_isSpeaking = true` left the flag stuck true AND let android-speak
        // report {"status":"speaking"} for audio that never played.
        val result = tts?.speak(speakable, TextToSpeech.QUEUE_FLUSH, params, drain.utteranceId(sessionEpoch))
            ?: TextToSpeech.ERROR
        if (result != TextToSpeech.SUCCESS) {
            // No callback will arrive for this utterance: roll the session
            // counters back so the drain tracker can't wait on it forever.
            drain.invalidate()
            _isSpeaking.value = false
            Log.e(TAG, "engine refused utterance (result=$result, chars=${speakable.length})")
            return false
        }
        _isSpeaking.value = true
        return true
    }

    /**
     * Adds text to the speech queue without interrupting (QUEUE_ADD).
     *
     * [fix/da1016-tts-voice] The streaming path ([appendText] / [flush]) keeps
     * its per-slice auto-detection — there is no explicit language to honor
     * there — and is intentionally NOT sanitized: cleaning slice by slice
     * would break cross-slice link rewriting. [speak] is the sanitized
     * read-aloud entry.
     */
    fun speakQueued(text: String): Boolean {
        if (!isInitialized || text.isBlank()) return false

        val utteranceId = drain.utteranceId(drain.enqueue())
        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [r3-B11-F07] Same contract as [speak]: a refused utterance never
        // delivers a callback, so the enqueue it just registered has to be
        // rolled back or `isSpeaking` waits on it for the rest of the process.
        val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            ?: TextToSpeech.ERROR
        if (result != TextToSpeech.SUCCESS) {
            if (drain.rejectPending()) _isSpeaking.value = false
            Log.e(TAG, "engine refused queued utterance (result=$result, chars=${text.length})")
            return false
        }
        _isSpeaking.value = true
        return true
    }

    /**
     * Feed a chunk of streaming AI output. Any complete sentences the chunk
     * produces (boundary characters: `。！？.!?\n` — same set as iOS
     * `extractNewSentences` in AIChatViewModel) are immediately enqueued for
     * TTS via [speakQueued], so the first sentence starts speaking the moment
     * its terminator arrives instead of waiting for the full response.
     *
     * Incomplete tail text stays in [sentenceBuffer] until the next call, or
     * until [flush] runs at stream end.
     *
     * Safe to call before [init]/onInit — chunks are still split; the TTS
     * engine simply drops them if not yet ready (speakQueued guards).
     */
    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        val sentences = extractCompleteSentences(sentenceBuffer)
        if (sentences.isEmpty()) return
        for (sentence in sentences) {
            speakQueued(sentence)
        }
    }

    /**
     * Emit the remaining buffered tail (a sentence fragment with no trailing
     * punctuation) as a final utterance. Call at the end of a streaming
     * response. Mirrors iOS's post-stream "flush remaining speech" logic.
     */
    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) {
            speakQueued(tail)
        }
    }

    /**
     * [T-android-tts-intranumber-guard] Delegates to the shared
     * [SpeechSentenceSplitter] so this path and the read-aloud UI path apply the
     * same terminator set AND the same intra-number guards ("3.14" is never cut
     * into "3." / "14").
     * "3." + "14").
     */
    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    /**
     * Stops all speech immediately.
     */
    fun stop() {
        tts?.stop()
        // [sweep1014-B20b] Invalidate every in-flight callback: utterances the
        // engine flushes still deliver onDone/onError asynchronously — without
        // the epoch bump a stale callback would push the next session's
        // counters (isSpeaking dropped to false the moment a fresh speak()
        // started).
        drain.invalidate()
        sentenceBuffer.setLength(0)
        _isSpeaking.value = false
    }

    /**
     * [fix/audit-b20 / T8-L5] Advances the drain counter and clears the
     * speaking flag once the queue empties. Called from both onDone and the
     * error overloads so a failed utterance still counts as finished — without
     * that, `isSpeaking` stayed true after an error.
     *
     * [sweep1014-B20b] Only callbacks belonging to the CURRENT session count:
     * a flushed utterance's late onDone/onError must not advance the new
     * session's counters (it extinguished a fresh speak() the moment it
     * started). Stale/unknown ids are side-effect free no-ops.
     */
    private fun onUtteranceFinished(utteranceId: String?) {
        if (drain.onFinished(utteranceId)) {
            _isSpeaking.value = false
        }
    }

    /**
     * Sets the TTS language explicitly.
     */
    fun setLanguage(locale: Locale) {
        if (!isInitialized) return
        applyLanguage(locale)
    }

    /**
     * The language tag the engine is currently set to, or null when unknown.
     * Only tags the engine ACCEPTED are recorded ([currentLocaleTag]), so a
     * caller reporting the "effective voice" never echoes back a language the
     * engine refused. [fix/da1016-tts-voice]
     */
    fun currentLanguageTag(): String? = currentLocaleTag

    /**
     * [fix/da1016-tts-voice] Single language chokepoint: every language change
     * (default on init, auto-detection, explicit `--voice`) goes through here,
     * so the [currentLocaleTag] mirror can't drift from the engine state.
     */
    private fun applyLanguage(locale: Locale) {
        val engine = tts ?: return
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // The engine keeps its previous language — leave the mirror alone
            // so currentLanguageTag() reports what will actually be spoken.
            Log.w(TAG, "Language not supported: ${locale.toLanguageTag()}")
        } else {
            currentLocaleTag = locale.toLanguageTag()
            Log.d(TAG, "Language set to: ${locale.toLanguageTag()}")
        }
    }

    /**
     * Cleans up the TTS engine. Must be called when no longer needed.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        currentLocaleTag = null
        Log.d(TAG, "TTS shut down")
    }

    /**
     * Detects if text contains Han characters and sets language accordingly.
     *
     * [fix/da1016-tts-voice] Delegates to the shared [TtsLanguagePolicy] so
     * the read-aloud path ([speak], explicit tag wins) and this streaming
     * path share one decision source.
     */
    private fun autoDetectAndSetLanguage(text: String) {
        applyLanguage(TtsLanguagePolicy.autoDetectLocale(text))
    }

    private fun buildSpeechParams(): android.os.Bundle {
        return android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume)
        }
    }

    private fun createProgressListener(): UtteranceProgressListener =
        object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                // [sweep1014-B20b] A stale onStart from a flushed session must
                // not re-light the flag after stop().
                if (drain.isCurrent(utteranceId)) {
                    _isSpeaking.value = true
                }
            }

            override fun onDone(utteranceId: String?) {
                onUtteranceFinished(utteranceId)
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                onUtteranceFinished(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error for utterance $utteranceId, code: $errorCode")
                onUtteranceFinished(utteranceId)
            }
        }
}

/**
 * [sweep1014-B20b] Session-scoped drain tracker for the TTS queue. speak()
 * (QUEUE_FLUSH) and stop() start a new epoch; every utterance id carries its
 * epoch prefix, so progress callbacks from a flushed session are recognized
 * as stale and ignored — they used to push the fresh session's counters and
 * extinguish `isSpeaking` the moment a new speak() started.
 *
 * Pure JVM-testable: no Android dependencies.
 */
internal class TtsDrainTracker {

    private val epoch = AtomicLong(0)
    private val queued = AtomicInteger(0)
    private val completed = AtomicInteger(0)

    /**
     * Starts a new session (speak / QUEUE_FLUSH): resets the drain counters
     * for the fresh queue and returns the session's epoch.
     */
    fun newSession(): Long {
        val e = epoch.incrementAndGet()
        queued.set(1)
        completed.set(0)
        return e
    }

    /** Adds one utterance to the CURRENT session (speakQueued / QUEUE_ADD). */
    fun enqueue(): Long {
        queued.incrementAndGet()
        return epoch.get()
    }

    /** Invalidates every in-flight callback (stop / shutdown). */
    fun invalidate() {
        epoch.incrementAndGet()
        queued.set(0)
        completed.set(0)
    }

    /**
     * [r3-B11-F07] Undo one enqueued utterance that the engine REFUSED
     * (`TextToSpeech.speak` returned ERROR): no `onDone`/`onError` will ever
     * arrive for it, so leaving the counter up made the queue look permanently
     * unfinished — `isSpeaking` stayed true until an unrelated `stop()`.
     *
     * Returns true when the queue is now drained, so the caller can clear the
     * speaking flag. Floors at 0, so a duplicate rollback is harmless.
     */
    fun rejectPending(): Boolean {
        while (true) {
            val current = queued.get()
            val next = if (current > 0) current - 1 else 0
            if (queued.compareAndSet(current, next)) return completed.get() >= next
        }
    }

    fun utteranceId(sessionEpoch: Long): String = "$PREFIX$sessionEpoch-${UUID.randomUUID()}"

    /** True when [utteranceId] belongs to the current session. */
    fun isCurrent(utteranceId: String?): Boolean =
        sessionEpochOf(utteranceId) == epoch.get()

    /**
     * Counts a finished (done or error) utterance. Returns true only when the
     * callback belongs to the CURRENT session and the queue has drained —
     * stale callbacks from a flushed session return false without touching
     * the counters.
     */
    fun onFinished(utteranceId: String?): Boolean {
        if (sessionEpochOf(utteranceId) != epoch.get()) return false
        return completed.incrementAndGet() >= queued.get()
    }

    companion object {
        private const val PREFIX = "tts-"

        fun sessionEpochOf(utteranceId: String?): Long? =
            utteranceId
                ?.takeIf { it.startsWith(PREFIX) }
                ?.removePrefix(PREFIX)
                ?.substringBefore('-')
                ?.toLongOrNull()
    }
}

/**
 * [fix/da1016-tts-voice] Pure language decision for one spoken utterance.
 *
 * [T-android-speech-asr] The Han regex mirrors
 * SpeechRecognizer.DEFAULT_LANGUAGES: Simplified Chinese plus the Extension A
 * block, so text the recognizer could be asked to transcribe is also
 * recognized here as Chinese.
 *
 * The auto-detect path stays binary (Chinese vs. "other"), exactly as the
 * legacy inline implementation. The decision lives here because it used to
 * run unconditionally inside [TextToSpeechManager.speak] — overwriting the
 * language SpeakOffloadHandler had just set from `--voice`, so every explicit
 * tag collapsed to the zh/en guess while the tool still reported it as the
 * chosen voice.
 *
 * Pure JVM (Locale + Regex only): the sandbox device and the CI suite test
 * the same decision.
 */
internal object TtsLanguagePolicy {

    private val HAN_REGEX = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")

    /**
     * The language to use for [text]: a caller-pinned BCP-47 tag
     * ([explicitTag]) wins over auto-detection — explicit zh on English text
     * must not be overridden. A tag the platform cannot turn into a usable
     * language (blank, or one whose language subtag resolves empty — "und",
     * "x-privateuse") falls back to [autoDetectLocale] instead of handing the
     * engine a language it will refuse anyway. Shape-valid but unknown codes
     * still go through: only the engine knows what it supports, and it says
     * so with its own return code ([TextToSpeech.setLanguage]).
     */
    fun resolveTargetLocale(explicitTag: String?, text: String): Locale {
        if (!explicitTag.isNullOrBlank()) {
            val locale = forLanguageTagSafe(explicitTag)
            if (locale.language.isNotEmpty()) return locale
        }
        return autoDetectLocale(text)
    }

    /**
     * Legacy binary auto-detection, verbatim: Han characters → zh-CN,
     * everything else → en-US.
     */
    fun autoDetectLocale(text: String): Locale =
        if (HAN_REGEX.containsMatchIn(text)) Locale.SIMPLIFIED_CHINESE else Locale.US

    /**
     * [Locale.forLanguageTag] never throws for BCP-47 input (ill-formed tags
     * resolve to "und" — measured on this JVM, see the fix's probe), so there
     * is no crash path to hide here; the catch is belt-and-braces for exotic
     * runtimes and keeps the "unusable → auto-detect" outcome either way.
     */
    private fun forLanguageTagSafe(tag: String): Locale =
        try {
            Locale.forLanguageTag(tag)
        } catch (_: RuntimeException) {
            Locale("")
        }
}

/**
 * [fix/da1016-tts-voice] The spoken link phrases [VoiceTextSanitizer]
 * rewrites bare URLs into, resolved from localized resources so they follow
 * the device locale — the R.string source the sanitizer's own KDoc describes
 * (iOS hardcodes the Chinese phrases; here they are translatable). English
 * defaults when no Context is available yet.
 */
internal object TtsSpokenPhrases {

    fun from(context: Context?): VoiceTextSanitizer.LinkPhrases =
        if (context == null) {
            VoiceTextSanitizer.LinkPhrases()
        } else {
            VoiceTextSanitizer.LinkPhrases(
                linkToHost = context.getString(R.string.tts_spoken_link_to_host),
                bareLink = context.getString(R.string.tts_spoken_bare_link),
            )
        }
}
