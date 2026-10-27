package com.rikkaminis.app.provider.voice

import com.rikkaminis.app.provider.applyUserAgentOverride
import com.rikkaminis.app.provider.executeOrCancel

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * [T-android-provider-voice] VoiceProvider base class — Android port of iOS
 * VoiceProvider.swift.
 *
 * Implements the OpenAI-compatible /v1/audio/transcriptions (ASR) and
 * /v1/audio/speech (TTS) endpoints. Vendor subclasses override the hook
 * methods to adapt non-OpenAI request/response shapes. `transcribe`/
 * `synthesize` are open (not final): vendors whose response envelope differs
 * (MiniMax/Doubao wrap audio in base64 JSON) override them wholesale.
 */
open class VoiceProvider(
    val providerId: String,
    val baseURL: String,
    val apiKey: String? = null,
    /**
     * [da1029 I05] Per-instance User-Agent override, mirroring the chat
     * providers' `customUserAgent`. Before this, the voice family was the one
     * outbound subsystem that never carried it: the chat / model-list /
     * connectivity paths all go through `applyUserAgentOverride`, so a user
     * fronting an OpenAI-compatible relay with a CF WAF / UA allow-list got a
     * working chat but a 403 on ASR/TTS (the request went out as `okhttp/4.12.0`).
     *
     * Declared as a constructor parameter with a default so the 11 vendor
     * subclasses keep their 3-argument `super(...)` calls unchanged; the
     * factory assigns it after construction ([VoiceProviderFactory.make]).
     * null/blank falls back to [com.rikkaminis.app.provider.MinisUserAgent.DEFAULT]
     * inside the single-source helper — the same fallback the chat link uses.
     */
    var customUserAgent: String? = null,
) {
    companion object {
        private const val TAG = "VoiceProvider"

        /** TTS responses can be large and slow — generous timeouts (iOS: 120s). */
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            // [T5-M1] WebSocket transports (Xunfei TTS) are NOT covered by
            // readTimeout — OkHttp applies that only to HTTP body reads. With
            // no ping the client never notices a dead tunnel and synthesize()
            // hangs forever (QuickTestSheet has no withTimeout). Verified in
            // the sandbox against a silent WS server: without pingInterval the
            // socket stayed open past 20s with no onFailure; with it the
            // failure fired on schedule.
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        /**
         * Reduce a language tag (e.g. "zh-CN", "zh-Hans-CN", "en") to its
         * ISO-639-1 two-letter primary code ("zh", "en") — what Whisper expects.
         */
        fun iso6391(tag: String?): String? {
            val trimmed = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return trimmed.split('-', '_').firstOrNull()?.lowercase(Locale.ROOT)
        }

        /** Wrap 16-bit little-endian mono PCM in a minimal WAV container. */
        fun wrapPcm16InWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcm.size
            val header = java.nio.ByteBuffer.allocate(44)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)                       // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            return header.array() + pcm
        }
    }

    // -- Entry points ---------------------------------------------------------

    open suspend fun transcribe(request: VoiceInputRequest): VoiceInputResponse {
        val model = request.resolvedModel
        if (model != null && usesChatBasedASR(model)) {
            Log.i(TAG, "Using chat-based ASR for ${model.displayName}")
            return transcribeChatBased(request)
        }
        if (!supportsVoiceInput) {
            throw VoiceProviderException.Unsupported("${javaClass.simpleName} does not support voice input")
        }
        val req = buildVoiceInputRequest(request)
        val data = executeRequest(req)
        return parseVoiceInputResponse(data, request)
    }

    open suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        if (!supportsVoiceOutput) {
            throw VoiceProviderException.Unsupported("${javaClass.simpleName} does not support voice output")
        }
        val req = buildVoiceOutputRequest(request)
        return executeRequest(req)
    }

    // -- Capability flags (override) -----------------------------------------

    open val supportsVoiceInput: Boolean get() = true
    open val supportsVoiceOutput: Boolean get() = true

    // -- Endpoint paths (override) -------------------------------------------

    open fun voiceInputEndpointPath(): String = "/v1/audio/transcriptions"
    open fun voiceOutputEndpointPath(): String = "/v1/audio/speech"

    // -- Default model / voice (override) ------------------------------------

    open fun defaultVoiceInputModel(): String = "whisper-1"
    open fun defaultVoiceOutputModel(): String = "tts-1"
    open fun defaultVoiceOutputVoice(): String = "alloy"

    // -- Request construction (override to customize format) -----------------

    /** Build the ASR request. Default: OpenAI multipart/form-data. */
    open fun buildVoiceInputRequest(request: VoiceInputRequest): Request {
        val url = composedUrlString(voiceInputEndpointPath())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "voice.wav",
                request.audioData.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", request.model ?: defaultVoiceInputModel())
        // Whisper/OpenAI-compatible APIs expect an ISO-639-1 TWO-LETTER code and
        // reject region-qualified ids like "zh-CN" with HTTP 400.
        iso6391(request.language)?.let { multipart.addFormDataPart("language", it) }
        multipart.addFormDataPart("response_format", request.responseFormat.wireValue)
        request.prompt?.let { multipart.addFormDataPart("prompt", it) }

        val builder = Request.Builder().url(url).post(multipart.build())
        applyVoiceAuth(builder)
        return builder.build()
    }

    /** Build the TTS request. Default: OpenAI JSON body. */
    open fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val url = composedUrlString(voiceOutputEndpointPath())
        val body = JSONObject().apply {
            put("model", request.model ?: defaultVoiceOutputModel())
            put("input", request.input)
            put("voice", request.voice ?: defaultVoiceOutputVoice())
            put("response_format", request.responseFormat.wireValue)
            request.speed?.let { put("speed", it.toDouble()) }
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)
        return builder.build()
    }

    /** Parse the ASR response. Default: OpenAI JSON, falling back to plain text. */
    open fun parseVoiceInputResponse(data: ByteArray, request: VoiceInputRequest): VoiceInputResponse {
        val text = String(data, Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj != null && obj.has("text")) {
            // [audit-0917 F-203] The empty-transcript guard the three vendor
            // parsers carry (Xunfei/Doubao/Deepgram, 442e6d0) was missing here —
            // the base class every OpenAI-compatible endpoint falls back to.
            // `optString` yields "" for a missing or empty key, so the old
            // `has("text")` gate returned text="" and the consumer
            // (ProviderSpeechRecognitionEngine) mapped a malformed response to
            // NO_MATCH ("heard nothing") instead of surfacing a protocol error.
            // NOTE: the raw-text fallback below is deliberately left as-is — a
            // non-JSON body is a different, already-handled shape.
            val transcript = obj.optString("text").takeIf { it.isNotEmpty() }
                ?: throw VoiceProviderException.Parse("Empty ASR transcript in JSON response")
            return VoiceInputResponse(
                text = transcript,
                language = obj.optString("language").takeIf { it.isNotBlank() },
                durationSeconds = if (obj.has("duration")) obj.optDouble("duration") else null,
            )
        }
        // Some compatible endpoints return raw text.
        if (text.isNotEmpty()) return VoiceInputResponse(text = text)
        throw VoiceProviderException.Parse("Empty or undecodable ASR response")
    }

    /** Inject auth header. Default: Bearer token. */
    open fun applyVoiceAuth(builder: Request.Builder) {
        val key = apiKey?.takeIf { it.isNotEmpty() } ?: return
        builder.header("Authorization", "Bearer $key")
    }

    // -- Helpers --------------------------------------------------------------

    open fun effectiveBaseURL(): String = baseURL.trimEnd('/')

    /**
     * Join the base URL with an endpoint path, avoiding a duplicated version
     * segment (e.g. Groq's base already ends in /v1 and our default paths start
     * with /v1/ — naive concatenation yields /v1/v1/… → 404).
     */
    fun composedUrlString(path: String): String {
        val base = effectiveBaseURL()
        var p = path
        for (version in listOf("/v1", "/v2", "/v3")) {
            if (base.endsWith(version) && p.startsWith("$version/")) {
                p = p.removePrefix(version)
                break
            }
        }
        return base + p
    }

    suspend fun executeRequest(request: Request): ByteArray = withContext(Dispatchers.IO) {
        // [da1029 I05] Single HTTP chokepoint for the whole voice family
        // (ASR, TTS, chat-based ASR and every vendor override that builds its
        // own request all end up here), so the per-instance UA is applied in
        // exactly one place. A null/blank override falls back to the branded
        // MinisUserAgent.DEFAULT — identical to the chat link, where the
        // factory hands the instance value to each provider and the provider
        // applies it right before building its request, so a relay
        // allow-listing the app's UA sees the same identity on the voice
        // endpoints as on /chat/completions.
        val effectiveRequest = request.newBuilder()
            .applyUserAgentOverride(customUserAgent)
            .build()
        val call = httpClient.newCall(effectiveRequest)
        // [FIX-1 / F-209] Cancellable execute — see provider/CallCancellation.kt:
        // executeOrCancel rethrows a real CancellationException (never a
        // fallback) and cancels the Call when the coroutine completes.
        //
        // [da1020 ISSUE-05] Prompt-interruption watcher. readTimeout here is
        // 120s (TTS can be slow), and coroutine cancellation does not interrupt
        // a blocking body read. executeOrCancel's own hook is registered on the
        // withContext job itself, which cannot reach its final state while this
        // body is blocked — sandbox-verified (probe/jvm/CancelProbe2.kt,
        // kotlinx-coroutines 1.9.0): its Call.cancel() only fired AFTER the
        // blocking read was released (promptCancelCount=0). A child job that
        // does nothing but await cancellation CAN complete promptly (it has no
        // blocking body), so its completion hook cancels the Call while the
        // read is still in flight — which makes execute() throw immediately
        // (probe: job.join returned in ~190ms with the Call cancelled).
        val watchJob = launch { awaitCancellation() }
        val watchDisposable = watchJob.invokeOnCompletion { _ -> call.cancel() }
        try {
            call.executeOrCancel().use { response ->
                val body = response.body?.bytes()
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        Log.e(TAG, "Voice auth failed: HTTP ${response.code}")
                        throw VoiceProviderException.Auth()
                    }
                    Log.e(TAG, "Voice request failed: HTTP ${response.code}")
                    throw VoiceProviderException.Http(response.code, body)
                }
                body ?: ByteArray(0)
            }
        } finally {
            // Normal-completion path: drop the hook and stop the watcher so
            // withContext does not wait for it. On the throw path structured
            // concurrency cancels the watcher, and the still-attached hook then
            // cancels the Call — harmless cleanup for an already-failed call.
            watchDisposable.dispose()
            watchJob.cancel()
        }
    }

    // -- Chat-based ASR (audio+text multimodal chat models) -------------------

    /**
     * Whether this model transcribes via chat completions instead of the
     * Whisper-style transcriptions endpoint. DEFAULT IS THE TRANSCRIPTIONS
     * ENDPOINT — audio-capable CHAT models are a small enumerable family
     * (gpt*audio*, qwen*audio*, *omni*); dedicated ASR models are an open set
     * and must be the fallback. Model NAME is the signal — modality bits can't
     * distinguish a hand-annotated Whisper from a true audio chat model.
     */
    open fun usesChatBasedASR(model: com.rikkaminis.app.data.model.LLMModel): Boolean {
        val id = model.id.lowercase()
        if (id.contains("omni")) return true
        if (!id.contains("audio")) return false
        return id.contains("gpt") || id.contains("qwen")
    }

    /**
     * Transcribe audio via chat completions. The system prompt forces the model
     * to output ONLY the verbatim transcription so it behaves like a dedicated
     * ASR engine.
     */
    open suspend fun transcribeChatBased(request: VoiceInputRequest): VoiceInputResponse {
        val url = composedUrlString("/v1/chat/completions")
        val audioBase64 = Base64.encodeToString(request.audioData, Base64.NO_WRAP)
        val langHint = iso6391(request.language) ?: "auto"

        val body = JSONObject().apply {
            put("model", request.model ?: "gpt-4o-mini-audio-preview")
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "You are a speech-to-text transcription engine. Output ONLY the exact verbatim transcription of the audio. No commentary, no punctuation corrections, no translations, no markdown, no extra text. If the audio is empty or unintelligible, output an empty string. Language hint: $langHint.",
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put(
                                            "input_audio",
                                            JSONObject()
                                                .put("data", audioBase64)
                                                .put("format", "wav"),
                                        ),
                                ),
                            ),
                    ),
            )
            put("max_tokens", 4096)
            put("temperature", 0)
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)

        val data = executeRequest(builder.build())
        return VoiceInputResponse(text = parseChatAsrTranscript(data))
    }
}

/**
 * Parse the chat-completion ASR response body into the transcript text.
 *
 * [da1020 ISSUE-05] The empty-transcript guard mirrors the transcriptions
 * endpoint parser (parseVoiceInputResponse, [audit-0917 F-203]): `optString`
 * yields "" for a missing or empty key, so the old inline chain returned
 * text="" and the consumer (ProviderSpeechRecognitionEngine) mapped a
 * malformed chat response to NO_MATCH ("heard nothing") instead of surfacing
 * a protocol error — which also blocked the fail-over chain (a Parse throw
 * advances to the next candidate; a successful empty transcript does not).
 *
 * Extracted as a top-level pure function so the parse contract is testable
 * without the OkHttp/android surface.
 */
internal fun parseChatAsrTranscript(data: ByteArray): String {
    val obj = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        ?: throw VoiceProviderException.Parse("Undecodable chat ASR response")
    val text = obj.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw VoiceProviderException.Parse("Empty ASR transcript in chat ASR response")
    return text
}
