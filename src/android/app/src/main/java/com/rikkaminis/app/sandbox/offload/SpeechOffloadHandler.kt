package com.rikkaminis.app.sandbox.offload

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.offload.OffloadPermissionManager
import com.rikkaminis.app.sandbox.NativeOffloadHandler
import com.rikkaminis.app.sandbox.NativeOffloadRequest
import com.rikkaminis.app.sandbox.NativeOffloadResult
import com.rikkaminis.app.sandbox.PRootKernel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * android-speech — speech recognition (audio → text).
 *
 * T61: re-aligned with iOS `apple-speech` (NativeOffloads/SpeechOffload.m).
 * Subcommand model:
 *
 *   android-speech transcribe [--source mic|<path>] [--language tag] [--duration N]
 *   android-speech listen [...]              # legacy alias for transcribe
 *   android-speech languages
 *   android-speech status
 *
 * What changed from the pre-T61 surface:
 *   - `transcribe` is the new canonical subcommand; `listen` is an alias kept
 *     for back-compat with prompts that learned the old form.
 *   - `--source <mic|path>` mirrors apple-speech: defaults to system mic,
 *     also accepts a Linux file path — absolute (`/var/minis/...`) or
 *     relative to the shell's cwd — joined by [resolveMaybeRelative] and
 *     resolved session-scoped via [PRootKernel.resolvePathForSession].
 *     **Audio-file transcription is not
 *     yet wired through the recognizer** — Android's [SpeechRecognizer]
 *     only exposes microphone input on most vendor implementations
 *     (the API 31 `EXTRA_AUDIO_SOURCE` extension is not honoured by
 *     Google's recogniser on Pixel-class devices and is absent on most
 *     OEM ROMs). When the user passes a file path we currently return a
 *     structured `not_supported` error; the path is validated and
 *     reported back so future versions can route through a streaming
 *     decoder + an alternative engine without changing the CLI.
 *   - `languages` enumerates installed recognition locales via the
 *     [RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS] ordered broadcast.
 *     Mirrors iOS `[SFSpeechRecognizer supportedLocales]`.
 *
 * Permission flow: when a transcribe attempt needs RECORD_AUDIO and the
 * user has already permanently denied it, we route through the same
 * 3-stage flow the chat composer's mic button uses (T67).
 */
class SpeechOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        // `--help` is a flag, so `positional` is empty for it too: the exit
        // code has to be decided on the flag, or `--help` answers 2.
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.hasFlag("h", "help")) 0 else 2, HELP)
        }

        // T330: tri-state agent gate before any work. [audit-0919 F-51]
        // `transcribe` captures microphone audio — a PII surface.
        OffloadGate.enforce("speech_recognition", "android-speech", args, request)?.let { return it }

        return try {
            when (val sub = args.positional[0]) {
                "status" -> cmdStatus(args)
                "languages" -> cmdLanguages(args)
                "transcribe", "listen" -> cmdTranscribe(args, request.sessionId, request.cwd)
                else -> NativeOffloadResult(2, "android-speech: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = JSONObject()
                .put("error", "internal")
                .put("message", e.message ?: "unknown")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── Subcommands ──────────────────────────────────────────────────────────

    private fun cmdStatus(args: OffloadArgs): NativeOffloadResult {
        val ok = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Throwable) { false }
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val body = JSONObject()
            .put("available", ok)
            .put("has_record_audio_permission", hasMic)
            .toString(2)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun cmdTranscribe(args: OffloadArgs, sessionId: String?, cwd: String?): NativeOffloadResult {
        val available = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Throwable) { false }
        if (!available) {
            val body = JSONObject()
                .put("error", "recognizer_unavailable")
                .put(
                    "message",
                    "No speech recognition service is registered on this device. " +
                        "This is common on devices without Google Mobile Services " +
                        "(Huawei HMS-only, some stripped China ROMs). Ask the user " +
                        "to install a speech recognition engine (e.g. Google, or an " +
                        "OEM-provided alternative).",
                )
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }

        // --source: default mic, accept "mic" / "system-mic" / a path (absolute,
        // or relative to the guest cwd the shell ran from).
        val source = args.get("source")
        if (source != null && !sourceIsMic(source)) {
            // T61: file-source path. Validate the path resolves and the
            // file exists, but bail with `not_supported` because Android
            // SpeechRecognizer doesn't accept arbitrary audio file inputs
            // on the platforms we ship to. Reported path is what the
            // future implementation would consume.
            // [da1033-C] `request.cwd` is forwarded so a relative --source
            // (`--source note.wav` from /var/minis/workspace) resolves against
            // the shell's cwd instead of being rejected outright.
            val resolved = resolveSourcePath(source, sessionId, cwd)
            val exists = resolved?.exists() == true
            AppLogger.warning(
                TAG,
                "transcribe --source=$source resolved=${resolved?.absolutePath} exists=$exists — file source not yet wired",
            )
            val body = JSONObject()
                .put("error", "not_supported")
                .put(
                    "message",
                    "Audio-file transcription is not yet supported on Android. " +
                        "Only microphone source works today. The given path was " +
                        "${if (exists) "found" else "not found"} on the host. Use " +
                        "`android-speech transcribe --source mic` or omit --source.",
                )
                .put("requested_path", source)
                .put("resolved_host_path", resolved?.absolutePath ?: "")
                .put("file_exists", exists)
                .toString()
            return NativeOffloadResult(2, OffloadOutput.formatBody(body, args) + "\n")
        }

        // Mic path. Permission gate first.
        ensureMicPermission(args)?.let { return it }

        // iOS-style flag names: --language, --duration, --on-device.
        // Pre-T61 callers also passed --max / --timeout; preserve those.
        val lang = args.get("language")
        val maxResults = args.getInt("max") ?: 3
        val durationSec = args.getInt("duration") ?: args.getInt("timeout") ?: 30
        val text = recognize(lang, maxResults, durationSec.toLong())
        // [dv18-A06] `recognize` reports every failure INSIDE the payload it
        // returns (`{"error": …}` — "no result", recognizer_creation_failed,
        // the onError message mapping, or the timeout sentence), and that
        // payload used to be returned with exit 0 unconditionally: a caller
        // checking `$?` could not tell a failed transcription from a
        // successful one. The payload stays byte-for-byte what recognize
        // produced; only the exit code is derived from it.
        return NativeOffloadResult(transcribeExitCode(text), OffloadOutput.formatBody(text, args) + "\n")
    }

    /**
     * Enumerate recognition locales installed on the device. Uses the
     * [RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS] ordered broadcast — the
     * standard non-deprecated way to get the supported language list on
     * Android. Times out at 3s if no recognizer responds (which happens on
     * devices without GMS).
     *
     * Mirrors iOS `[SFSpeechRecognizer supportedLocales]`. Output schema
     * matches: `{ locales: [{locale, display_name}, ...], count: N }`.
     */
    private fun cmdLanguages(args: OffloadArgs): NativeOffloadResult {
        val latch = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val extras = getResultExtras(false)
                @Suppress("DEPRECATION")
                val langs: ArrayList<String>? = extras?.getStringArrayList(
                    RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES,
                )
                resultRef.set(langs?.toList() ?: emptyList())
                latch.countDown()
            }
        }
        try {
            // sendOrderedBroadcast with our resultReceiver — the recogniser
            // populates resultExtras with EXTRA_SUPPORTED_LANGUAGES.
            context.sendOrderedBroadcast(
                Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
                null,
                receiver,
                Handler(Looper.getMainLooper()),
                0,
                null,
                null,
            )
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "languages broadcast failed: ${e.message}")
        }
        latch.await(3, TimeUnit.SECONDS)

        val prefix = args.get("language")
        val items = JSONArray()
        for (tag in resultRef.get().sorted()) {
            if (!prefix.isNullOrBlank() && !tag.startsWith(prefix)) continue
            val locale = java.util.Locale.forLanguageTag(tag.replace('_', '-'))
            items.put(
                JSONObject()
                    .put("locale", tag)
                    .put("display_name", locale.displayName.ifEmpty { tag }),
            )
        }
        val body = JSONObject()
            .put("locales", items)
            .put("count", items.length())
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolve a guest Linux path to a host File via [PRootKernel]'s guarded
     * resolvers only. With a session in scope the path is pinned to that
     * session's tree; without one it resolves through the global bind mounts.
     * [audit3 F-08] The session-vs-global decision is
     * [PRootKernel.resolvePathForSession]'s — a path the session resolver
     * rejects (escape out of the session tree) stays rejected instead of being
     * re-resolved globally.
     *
     * [da1033-C] A relative path is joined onto the guest's [cwd] by the
     * single join implementation ([resolveMaybeRelative]) — the same shape
     * every other path-taking offload handler uses — and the joined path is
     * what reaches the session-aware resolver. `..` out of the tree is
     * therefore rejected *there*, not here.
     *
     * [fix/sweep1013-security] Fail-closed: no `File(trimmed)` fallback.
     * The previous `?: File(trimmed)` tail let an unresolvable absolute
     * path (e.g. /data/data/.../databases/minis.db) read through as a raw
     * host File — a guest → host read escape past both guards. A blank
     * source is still rejected outright; the mic spellings never reach this
     * function at all ([sourceIsMic] gates the caller first).
     */
    private fun resolveSourcePath(source: String, sessionId: String?, cwd: String?): File? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        val guestPath = resolveMaybeRelative(trimmed, cwd)
        return try {
            PRootKernel.resolvePathForSession(sessionId, guestPath, context)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "resolveSourcePath('$trimmed') failed: ${e.message}")
            null
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun ensureMicPermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasMic()) return null
        AppLogger.warning(TAG, "RECORD_AUDIO not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(
                listOf(Manifest.permission.RECORD_AUDIO),
            )
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasMic() })
            ) {
                AppLogger.info(TAG, "Mic permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = Manifest.permission.RECORD_AUDIO,
                        title = "Microphone permission needed",
                        message = "RikkaMinis needs microphone permission to transcribe speech. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasMic() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "permission_denied")
                        .put("message", "The user declined the microphone permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the microphone permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun recognize(language: String?, maxResults: Int, timeoutSec: Long): String {
        val latch = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference("""{"error": "no result"}""")
        val mainHandler = Handler(Looper.getMainLooper())

        // [T-android-speech-error10] C4: every exit path must destroy the
        // recognizer exactly once. Previously destroy() only ran in the
        // onResults/onError callbacks — a latch timeout ORPHANED the
        // recognizer, and repeated runs exhausted the system recognizer
        // service quota, after which every listen failed with
        // ERROR_TOO_MANY_REQUESTS (10). Destroy always runs on the main
        // looper (the creation thread, as SpeechRecognizer requires).
        val recognizerRef = java.util.concurrent.atomic.AtomicReference<SpeechRecognizer?>(null)
        val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun destroyRecognizerOnce() {
            if (!destroyed.compareAndSet(false, true)) return
            val r = recognizerRef.getAndSet(null) ?: return
            try {
                r.destroy()
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "recognizer destroy threw: ${e.message}")
            }
        }

        mainHandler.post {
            val recognizer = try {
                // [T-android-speech-error10] Prefer the on-device recognizer
                // on S+ (mirrors SystemSpeechRecognitionEngine): it avoids
                // the shared cloud recognizer service whose request quota
                // the orphaned sessions were exhausting. Fall back to the
                // regular recognizer when on-device isn't supported.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } catch (_: Throwable) {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (e: Throwable) {
                resultRef.set(
                    JSONObject().put("error", "recognizer_creation_failed")
                        .put("message", "Could not create SpeechRecognizer: ${e.message}")
                        .toString(),
                )
                latch.countDown()
                return@post
            }
            recognizerRef.set(recognizer)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
                if (language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val confs = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val json = JSONObject()
                    json.put("text", matches?.firstOrNull() ?: "")
                    if (matches != null && matches.size > 1) {
                        val alts = JSONArray()
                        for (i in matches.indices) {
                            val a = JSONObject().put("text", matches[i])
                            if (confs != null && i < confs.size) a.put("confidence", String.format("%.2f", confs[i]))
                            alts.put(a)
                        }
                        json.put("alternatives", alts)
                    }
                    resultRef.set(json.toString(2))
                    destroyRecognizerOnce()
                    latch.countDown()
                }

                override fun onError(error: Int) {
                    val requestedLang = language ?: "default"
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions (RECORD_AUDIO required)"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                        // [T-android-speech-error10] Codes below use literal
                        // ints: their constants need API 31/33 while we
                        // compile against older listener call sites too.
                        // 10 = ERROR_TOO_MANY_REQUESTS (API 31)
                        10 -> "Too many recognition requests — the system recognizer service is saturated (orphaned sessions?)"
                        // 11 = ERROR_SERVER_DISCONNECTED (API 31)
                        11 -> "Recognition service disconnected"
                        // 12 = ERROR_LANGUAGE_NOT_SUPPORTED (API 33)
                        12 -> "Language not supported by the recognizer (requested: $requestedLang)"
                        // 13 = ERROR_LANGUAGE_UNAVAILABLE (API 33)
                        13 -> "Language not currently available — model may need downloading (requested: $requestedLang)"
                        else -> "Unknown error ($error)"
                    }
                    resultRef.set(JSONObject().put("error", msg).toString(2))
                    destroyRecognizerOnce()
                    latch.countDown()
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(intent)
        }

        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            // [T-android-speech-error10] The timeout path previously leaked
            // the recognizer (see destroyRecognizerOnce above). Destroy on
            // the main handler — the thread that created it.
            mainHandler.post { destroyRecognizerOnce() }
            return """{"error": "speech recognition timed out after ${timeoutSec}s"}"""
        }
        return resultRef.get()
    }

    companion object {
        private const val TAG = "SpeechOffload"
        private const val HELP = """android-speech — speech recognition (audio → text)

Usage:
  android-speech <command> [options]

COMMANDS:
  transcribe   Transcribe from system mic (file source coming later)
  listen       Alias for transcribe (legacy)
  languages    List available recognition locales
  status       Check speech recognition availability

OPTIONS:
  --help, -h           Show this help message
  --compact            Minimize JSON output
  -q, --quiet          Output only data field
  --source <mic|path>  Source: system mic (default) or audio file path
                       (absolute, or relative to the shell's cwd)
  --duration <sec>     Recording duration for mic source (default: 30)
  --language <locale>  Recognition locale (e.g. en-US, zh-CN)
  --max <N>            Maximum number of result candidates (default: 3)

EXAMPLES:
  android-speech transcribe --duration 5
  android-speech transcribe --source mic --language zh-CN --duration 15
  android-speech transcribe --source /var/minis/attachments/meeting.m4a
  android-speech transcribe --source note.wav   # relative to the shell cwd
  android-speech languages
  android-speech languages --language en
  android-speech status

Notes:
  - Requires RECORD_AUDIO permission for mic transcription.
  - Audio-file transcription returns `error: not_supported` on Android
    today; the path is validated and reported back so future engine work
    can wire through without changing the CLI.
"""
    }
}

/**
 * [da1033-C] True when `--source` names the microphone rather than a file:
 * unset/blank, `mic`, the two `system[-_]mic` spellings, or `microphone`.
 *
 * Top-level (not a class member) so the JVM test source set can drive it
 * directly — the same reason [resolveMaybeRelative] is top-level. The
 * classifier must run BEFORE the file resolver: a mic spelling joined onto
 * the cwd would otherwise be read as a file name.
 */
internal fun sourceIsMic(source: String): Boolean {
    val s = source.trim().lowercase()
    return s.isEmpty() || s == "mic" || s == "system-mic" || s == "system_mic" || s == "microphone"
}

/**
 * [dv18-A06] Exit code for a transcribe payload from [SpeechOffloadHandler].
 *
 * `recognize` expresses every failure INSIDE the JSON it returns — an `error`
 * key carrying "no result", "recognizer_creation_failed", the `onError`
 * mapping ("No speech recognized", "Audio recording error", "Recognizer
 * busy", …), or "speech recognition timed out after Ns". The payload is
 * therefore the only signal a caller has, and returning it with exit 0 made a
 * failure indistinguishable from a success. 1 = the recognition failed,
 * 0 = the recognizer produced a text result.
 *
 * An empty `text` **without** an `error` stays exit 0: the recognizer reported
 * that it completed, and "nothing was heard" is a result, not a failure of the
 * call — unlike the `no result` / `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT`
 * paths, which arrive as an `error` payload and now exit 1.
 *
 * A payload that is not JSON at all exits 1 (fail-closed): the only payloads
 * this function sees are produced by `recognize`, which always returns JSON,
 * so anything else is a defect worth surfacing rather than reporting success.
 *
 * Top-level (not a companion member) so the JVM test source set can drive it
 * directly — the same reason [sourceIsMic] and [resolveMaybeRelative] are.
 */
internal fun transcribeExitCode(payload: String): Int {
    val obj = try {
        JSONObject(payload)
    } catch (_: Throwable) {
        return 1
    }
    return if (obj.has("error")) 1 else 0
}
