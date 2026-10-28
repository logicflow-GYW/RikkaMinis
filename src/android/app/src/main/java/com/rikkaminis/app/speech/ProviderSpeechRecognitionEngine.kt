package com.rikkaminis.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.provider.voice.VoiceInputRequest
import com.rikkaminis.app.provider.voice.VoiceProvider
import com.rikkaminis.app.provider.voice.VoiceProviderException
import com.rikkaminis.app.provider.voice.VoiceProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * [T-android-provider-voice] Provider-backed transcription engine — the
 * formerly-stubbed Phase 2. Captures PCM16 mono 16 kHz via [AudioRecord],
 * wraps the take in a WAV container on stop, and ships it to the VoiceProvider
 * resolved from the Voice Input group (VoiceProviderFactory + the instance's
 * stored API key). Mirrors the iOS provider ASR path (VoiceProvider.transcribe:
 * dedicated Whisper-style endpoint, vendor adapters, or chat-based ASR for
 * audio chat models).
 *
 * No partial results: cloud transcription is one-shot on stop. The System
 * engine remains the streaming/live option.
 */
class ProviderSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "ProviderASR"
        private const val SAMPLE_RATE = 16_000
        /** Hard cap on a single take (60 s at 16 kHz PCM16 mono ≈ 1.9 MB). */
        private const val MAX_RECORD_SECONDS = 60
    }

    override val id: String = "provider"
    override val displayName: String = "Provider transcription"
    override val supportsPartialResults: Boolean = false

    /** Cheap check only: is a provider ASR selection resolvable right now? */
    override val isAvailable: Boolean
        get() = !degraded && repository()?.resolveVoiceInputEntry() != null

    /** Cloud ASR is language-agnostic (auto-detect); no fixed locale list. */
    override val supportedLocales: List<Locale> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var transcribeJob: Job? = null
    // [sweep1014-B20a] Shared-flag wipe race: `recording` used to be ONE
    // AtomicBoolean shared by every capture session. Rapid stop→start: the new
    // start() claimed the flag (getAndSet(true) succeeded), then the OLD job's
    // cleanup ran and set it back to false — the fresh capture's loop died on
    // its first iteration ("No audio captured"). The guard hands each session
    // an epoch token; a stale job's cleanup only releases the flag when it
    // still belongs to its own session.
    private val captureGuard = CaptureEpochGuard()
    // [r3-B11-F05] Per-session token, bumped by every start()/cancel()/shutdown().
    // It replaced a single shared `cancelled: AtomicBoolean`: cancel() set it,
    // the very next start() cleared it again (selectLocale cancels and restarts
    // back-to-back), so the OLD capture job walked past its own cancellation
    // guard and transcribe-and-delivered into the NEW session's listener —
    // wiping a recording the user had just started. A token only ever moves
    // forward, so a stale job can never mistake a newer session for its own.
    private val sessionToken = AtomicLong(0L)
    // [fix/audit0917-b8] @Volatile: written by the engine's IO coroutine
    // (markDegraded is invoked from the transcription failure path), read from
    // whichever thread evaluates `isAvailable` (the UI decides whether to show
    // the voice button). A plain Boolean gave no visibility guarantee, so a
    // degraded engine could keep reporting available.
    @Volatile
    private var degraded = false

    /**
     * [T-voice-asr-group-failover] STICKY fail-over (mirrors iOS
     * VoiceInputViewModel and the text agent loop's nextFallback): once a take
     * succeeds on a group member, later takes start there — only advance when
     * IT fails. Cleared implicitly when the entry leaves the candidate set
     * (the ordering below just falls back to chain order then).
     */
    @Volatile
    private var stickyEntryId: String? = null

    private fun repository() =
        (appContext.applicationContext as? MinisApp)?.providerRepository

    /**
     * [r3-B11-F05] True while [session] is still the live capture session.
     * Every delivery point (transcribe hand-off, per-candidate loop, callback)
     * consults this instead of the old shared `cancelled` flag, so a session
     * superseded by cancel()/start() goes inert even though its coroutine is
     * still winding down.
     */
    private fun isSessionLive(session: Long): Boolean = sessionToken.get() == session

    override fun markDegraded() {
        degraded = true
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO per interface contract
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val repo = repository()
        // [T-voice-asr-group-failover] Resolve the whole ordered candidate
        // chain instead of one entry. loadBalance groups get a fresh rotation
        // seed per capture so takes spread across members; fallback groups
        // keep declaration order. Provider construction is deferred to the
        // transcription step, where a failing member advances to the next.
        val candidates = repo?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()
        if (repo == null || candidates.isEmpty()) {
            listener.onError(
                RecognitionError.OEM_NO_SERVICE,
                "No provider voice-input model configured. Add an ASR model to the Voice Input group.",
            )
            return
        }
        val myEpoch = captureGuard.tryBegin()
        if (myEpoch == null) {
            listener.onError(RecognitionError.RECOGNIZER_BUSY, "A capture is already in flight.")
            return
        }
        val mySession = sessionToken.incrementAndGet()

        captureJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                captureGuard.releaseIfCurrent(myEpoch)
                listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord unsupported buffer size.")
                return@launch
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4,
                )
            } catch (e: Exception) {
                captureGuard.releaseIfCurrent(myEpoch)
                listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord init failed: ${e.message}")
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                captureGuard.releaseIfCurrent(myEpoch)
                listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord not initialized.")
                return@launch
            }

            val pcm = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            val maxBytes = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS
            try {
                recorder.startRecording()
                listener.onReadyForSpeech()
                // [sweep1014-B20a] isCurrent (not just isActive): if a rapid
                // stop→start handed the guard to a newer session, THIS stale
                // job must stop reading the mic immediately instead of
                // piggy-backing on the new session's flag.
                while (captureGuard.isCurrent(myEpoch) && pcm.size() < maxBytes) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    pcm.write(buf, 0, n)
                    listener.onRmsDb(rmsDb(buf, n))
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed: ${e.message}", e)
                captureGuard.releaseIfCurrent(myEpoch)
                listener.onError(RecognitionError.AUDIO_ERROR, e.message)
                return@launch
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                // [audit-0909 T8-M1] Release the capture flag on EVERY exit
                // path. The loop also ends when the 60 s / maxBytes cap is
                // reached, and that success path had no reset at all — the
                // flag stayed true forever, so every later start() hit
                // "A capture is already in flight" (RECOGNIZER_BUSY) and the
                // provider ASR engine stayed dead until the user explicitly
                // cancelled or the process restarted.
                // [sweep1014-B20a] releaseIfCurrent: only clear the flag when
                // it still belongs to THIS session — a stale job's cleanup
                // must not wipe a newer capture's flag (stop→start race).
                captureGuard.releaseIfCurrent(myEpoch)
            }

            if (!isSessionLive(mySession)) return@launch
            val audio = pcm.toByteArray()
            if (audio.isEmpty()) {
                listener.onError(RecognitionError.NO_MATCH, "No audio captured.")
                return@launch
            }

            // One-shot cloud transcription of the whole take, with SEAMLESS
            // fail-over across the candidate chain (mirrors iOS
            // transcribeWithFailover / VoiceOutputPlayer): a candidate that
            // throws advances to the next; the first success becomes the
            // STICKY member for later takes; only when EVERY candidate fails
            // does the error surface (last error wins).
            transcribeJob = scope.launch {
                val wav = VoiceProvider.wrapPcm16InWav(audio, SAMPLE_RATE)
                // Sticky-first try order: rotate the chain so the last
                // successful member goes first, the rest wrap around.
                val ordered = stickyEntryId
                    ?.let { sticky -> candidates.indexOfFirst { it.second.id == sticky } }
                    ?.takeIf { it > 0 }
                    ?.let { i -> candidates.drop(i) + candidates.take(i) }
                    ?: candidates
                var lastError: Exception? = null
                // [fix/audit0917-b8] Track how many candidates were skipped
                // (no provider / unresolvable key) separately from failures:
                // when EVERY candidate was skipped, lastError stayed null and
                // the user got the generic "All voice-input candidates failed"
                // with kind=UNKNOWN, which reads like a provider outage. A
                // skip means nothing was even attempted, so say so.
                var skipped = 0
                for ((instance, entry) in ordered) {
                    if (!isSessionLive(mySession)) return@launch
                    val provider = VoiceProviderFactory.make(instance, repo.loadApiKey(instance.id))
                    if (provider == null) {
                        Log.w(TAG, "candidate ${instance.label} cannot serve voice input — skipping")
                        skipped++
                        continue
                    }
                    try {
                        val response = provider.transcribe(
                            VoiceInputRequest(
                                audioData = wav,
                                model = entry.baseModel.id,
                                language = locale.toLanguageTag(),
                                // [deep-audit-1044 A-01] The vendor adapters read
                                // modalities / display / capability off this model, and
                                // `entry.model` is base+overrides with NO models.dev
                                // enrichment — a built-in whose local row declares no
                                // modality answered "not audio" to the very provider
                                // that was chosen because the registry says it is.
                                // Same single source as the candidate gate in
                                // ProviderRepository.resolveVoiceInputCandidates.
                                resolvedModel = ModelsDevApi.effectiveModel(entry),
                            ),
                        )
                        if (!isSessionLive(mySession)) return@launch
                        stickyEntryId = entry.id
                        val text = response.text.trim()
                        if (text.isEmpty()) {
                            // A successful round-trip that heard nothing is a
                            // semantic no-match, not a provider failure — do
                            // NOT advance the chain for it.
                            listener.onError(RecognitionError.NO_MATCH, "Empty transcription.")
                        } else {
                            listener.onFinal(text)
                        }
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "transcribe failed on ${entry.model.displayName}: ${e.message} — trying next candidate")
                        lastError = e
                    }
                }
                if (isSessionLive(mySession)) {
                    val e = lastError
                    // [fix/audit0917-b8] All-skipped is a configuration problem,
                    // not a provider failure — report it as such instead of
                    // letting it fall through to "All voice-input candidates
                    // failed" with kind=UNKNOWN.
                    if (e == null && skipped > 0) {
                        listener.onError(
                            RecognitionError.UNKNOWN,
                            "No usable voice-input provider: all $skipped candidate(s) were skipped " +
                                "(missing provider implementation or API key).",
                        )
                        return@launch
                    }
                    val kind = when {
                        e is VoiceProviderException.Auth -> RecognitionError.PERMISSION_DENIED
                        e is java.io.IOException -> RecognitionError.NETWORK
                        else -> RecognitionError.UNKNOWN
                    }
                    listener.onError(kind, e?.message ?: "All voice-input candidates failed.")
                }
            }
        }
    }

    override fun stop() {
        // Flip the capture loop off; the capture coroutine then hands the take
        // to the transcription step, which delivers onFinal/onError.
        // [sweep1014-B20a] Unconditional: only one capture can be in flight
        // (the busy guard in start), so stop() is terminal for it. External
        // semantics unchanged (immediate stop).
        captureGuard.forceRelease()
    }

    override fun cancel() {
        // [r3-B11-F05] Invalidate the in-flight session FIRST: the guard release
        // below only stops the read loop, and a token bump is what stops the
        // stale job from transcribing and delivering into whatever session
        // replaces it.
        sessionToken.incrementAndGet()
        captureGuard.forceRelease()
        // captureJob is deliberately not awaited: the loop exits via the capture
        // guard because AudioRecord.read() is a blocking call that doesn't
        // respond to job cancellation. Cancelling the job is still correct —
        // it guarantees nothing else runs after that read returns (the finally
        // block releasing the AudioRecord still runs), and the token check
        // above already makes any residue inert.
        captureJob?.cancel()
        transcribeJob?.cancel()
        transcribeJob = null
        captureJob = null
    }

    /**
     * [T-android-asr-provider-shutdown] Release the engine's process-scoped
     * coroutine scope and drop the retained job handles. The scope previously
     * lived for the whole process (no lifecycle hook) — calling this from a
     * teardown path stops future launches and clears the bookkeeping. Safe to
     * call at any time; the engine may still be re-used afterwards (a fresh
     * [start] launches new jobs on the cancelled scope, so callers should treat
     * [shutdown] as terminal).
     */
    override fun shutdown() {
        sessionToken.incrementAndGet()
        captureGuard.forceRelease()
        transcribeJob?.cancel()
        captureJob?.cancel()
        transcribeJob = null
        captureJob = null
        scope.cancel()
    }

    /** Rough dB estimate over the chunk for the UI waveform. */
    private fun rmsDb(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        if (rms <= 1.0) return 0f
        // Map amplitude RMS onto the [0, 12]-ish scale the System engine's
        // onRmsChanged reports, so the shared normalizer behaves identically.
        return (20 * log10(rms / 32768.0) + 50).toFloat().coerceIn(0f, 12f)
    }
}

/**
 * [sweep1014-B20a] Epoch guard for the capture flag. Each successful
 * [tryBegin] hands out a fresh epoch token; a session's cleanup
 * ([releaseIfCurrent]) only clears the flag when the token still matches the
 * current epoch — so a stale capture job's cleanup can never wipe the flag of
 * a capture that started after it (the rapid stop→start race).
 *
 * [rv1014-F1] The flag and the epoch token live in a single CAS-able slot
 * ([current]) so "compare and clear" is one atomic operation. The previous
 * two-field version (`AtomicBoolean` flag + plain `Int` epoch) had a
 * check-then-act window in [releaseIfCurrent] — a stale cleanup could observe
 * a matching epoch and then wipe a newer session's flag in between, and the
 * plain epoch field had no visibility guarantee across threads. JVM stress
 * (review-1014/FIX-RV-F): baseline 5/5 runs with wipe violations; CAS version
 * 0 violations under the same interleaving.
 *
 * Pure JVM-testable: no Android dependencies.
 */
internal class CaptureEpochGuard {

    /** Identity holder for the in-flight capture; replaced on every begin. */
    private class Session(val epoch: Int)

    private val current = AtomicReference<Session?>(null)
    private val epochCounter = AtomicInteger(0)

    /** True while a capture holds the flag. */
    val isActive: Boolean
        get() = current.get() != null

    /**
     * Claims the flag for a new capture. Returns the epoch token of the new
     * session, or null when a capture is already in flight (RECOGNIZER_BUSY —
     * unchanged external semantics).
     */
    fun tryBegin(): Int? {
        val session = Session(epochCounter.incrementAndGet())
        return if (current.compareAndSet(null, session)) session.epoch else null
    }

    /**
     * Releases the flag ONLY if it still belongs to the caller's session.
     * Stale sessions (superseded by a newer start) leave the flag alone.
     * Single CAS: if a newer session claimed the slot between the read and
     * the CAS, the stale cleanup loses the race and clears nothing.
     */
    fun releaseIfCurrent(epoch: Int) {
        val cur = current.get()
        if (cur?.epoch == epoch) current.compareAndSet(cur, null)
    }

    /**
     * Unconditional release — for stop()/cancel()/shutdown(), which are
     * terminal for whatever capture is in flight (only one can be, per the
     * busy guard).
     */
    fun forceRelease() {
        current.set(null)
    }

    /**
     * True when [epoch] is the current session AND it still holds the flag —
     * the capture loop's continue condition: a superseded session stops
     * reading the mic immediately instead of piggy-backing on the new
     * session's flag.
     */
    fun isCurrent(epoch: Int): Boolean =
        current.get()?.epoch == epoch
}
