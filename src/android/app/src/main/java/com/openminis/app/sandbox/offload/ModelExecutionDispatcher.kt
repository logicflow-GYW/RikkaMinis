package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Main-process dispatcher for [ModelExecutionService].
 *
 * Serializes a [minis-model-use run]'s provider call (instance / model /
 * messages / params / passthrough input) into a JSON request file, starts
 * [ModelExecutionService] in the `:modelservice` process, polls for the
 * result file, and returns the result JSON. The service process is killed
 * (stopSelf) after writing the result, returning all its native heap
 * (DirectByteBuffer allocations from the LLM HTTP call) to the OS — the
 * leak containment that in-process GC cannot achieve.
 *
 * Returns `null` when the service cannot be dispatched (no context yet,
 * request dir unwritable, result not ready before timeout) so the caller
 * can fall back to the in-process path — the remote execution is an
 * optimization for leak containment, never a hard dependency.
 */
object ModelExecutionDispatcher {

    private const val TAG = "ModelExecDispatcher"
    private const val REQUEST_TIMEOUT_MS = 3 * 60_000L  // matches agent tool timeout headroom
    private const val POLL_INTERVAL_MS = 200L
    /** Bounded wait for the worker's terminal marker before deleting the dir. */
    private const val TERMINAL_WAIT_MS = 5_000L
    /** Bounded wait for the worker's self-reap before deciding to keep the dir as orphan. */
    private const val REAP_WAIT_MS = 3_000L
    private const val REAP_POLL_MS = 50L

    /** Default base directory for request/result staging. */
    private const val STAGING_ROOT = "model-exec"

    /**
     * Build the serialized request JSON for a model run. Pure function —
     * JVM-testable. The API key is deliberately NOT included: the service
     * reads it from EncryptedSharedPreferences directly (same uid, same
     * encrypted prefs file) so the plaintext never touches disk.
     */
    fun buildRequestJson(
        instance: ProviderInstance,
        model: LLMModel,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        inputJson: String,
        outputExt: String?,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        streaming: Boolean = false,
    ): String {
        return JSONObject().apply {
            put("instance_id", instance.id)
            put("instance_label", instance.label)
            put("provider_type", instance.providerType.name)
            put("credential_type", instance.credentialType.name)
            instance.customBaseURL?.let { put("base_url", it) }
            put("append_v1", instance.appendV1Suffix)
            instance.customUserAgent?.let { put("user_agent", it) }
            put("use_responses_api", instance.useResponsesAPI)
            put("image_endpoint_mode", instance.imageEndpointMode.name)
            instance.imageEndpointResolved?.let { put("image_endpoint_resolved", it.name) }
            put("azure_mode", instance.azureMode)
            // [T-provider-extra-headers/body] The worker builds its provider from
            // THIS JSON alone — a knob not serialized here is a knob that never
            // reaches chat/compaction/title requests (worker-side default empty).
            if (instance.customHeaders.isNotEmpty()) {
                put("custom_headers", JSONArray().apply {
                    instance.customHeaders.forEach { h ->
                        put(JSONObject().apply {
                            put("name", h.name)
                            put("value", h.value)
                        })
                    }
                })
            }
            if (instance.customBodyFields.isNotEmpty()) {
                put("custom_body_fields", JSONArray().apply {
                    instance.customBodyFields.forEach { b ->
                        put(JSONObject().apply {
                            put("key", b.key)
                            put("value_json", b.valueJson)
                        })
                    }
                })
            }

            put("model_id", model.id)
            put("model_display_name", model.displayName)
            put("model_provider", model.provider)
            model.inputModalities.orEmpty().let { if (it.isNotEmpty()) put("input_modalities", JSONArray(it)) }
            model.outputModalities.orEmpty().let { if (it.isNotEmpty()) put("output_modalities", JSONArray(it)) }
            model.contextWindow?.let { put("context_window", it) }

            if (messages.isNotEmpty()) {
                put("messages", JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().apply {
                            put("role", m.role.value)
                            put("content", m.content)
                            if (m.contentParts.isNotEmpty()) {
                                put("contentParts", JSONArray().apply {
                                    m.contentParts.forEach { part ->
                                        put(JSONObject().apply {
                                            when (part) {
                                                is AgentContentPart.Text -> {
                                                    put("kind", "text")
                                                    put("text", part.text)
                                                }
                                                is AgentContentPart.ToolUse -> {
                                                    put("kind", "tooluse")
                                                    put("toolUseId", part.id)
                                                    put("name", part.name)
                                                    put("arguments", part.input ?: JSONObject())
                                                }
                                                is AgentContentPart.ToolResult -> {
                                                    put("kind", "toolresult")
                                                    put("toolUseId", part.id)
                                                    put("name", part.name)
                                                    put("isError", part.isError)
                                                    put("content", part.content)
                                                    part.imageData?.takeIf { it.isNotEmpty() }?.let {
                                                        put("imageDataB64", java.util.Base64.getEncoder().encodeToString(it))
                                                    }
                                                    part.imageMimeType?.let { put("imageMimeType", it) }
                                                    part.imageLinuxPath?.let { put("imageLinuxPath", it) }
                                                }
                                                is AgentContentPart.ImageData -> {
                                                    put("kind", "image")
                                                    put("mimeType", part.mimeType)
                                                    if (part.data.isNotEmpty()) {
                                                        put("b64Data", java.util.Base64.getEncoder().encodeToString(part.data))
                                                    }
                                                    part.linuxPath?.let { put("linuxPath", it) }
                                                }
                                            }
                                        })
                                    }
                                })
                            }
                            if (m.audioParts.isNotEmpty()) {
                                put("audio_parts", JSONArray().apply {
                                    m.audioParts.forEach { a ->
                                        put(JSONObject().apply {
                                            put("format", a.format)
                                            put("data", a.base64Data)
                                        })
                                    }
                                })
                            }
                        })
                    }
                })
            }
            systemPrompt?.let { put("system_prompt", it) }
            put("max_tokens", maxTokens)
            temperature?.let { put("temperature", it) }

            if (imageParts.isNotEmpty()) {
                put("image_parts", JSONArray().apply {
                    imageParts.forEach { img ->
                        put(JSONObject().apply {
                            if (img.data.isNotEmpty()) {
                                put("data", java.util.Base64.getEncoder().encodeToString(img.data))
                            }
                            put("mime_type", img.mimeType)
                            img.linuxPath?.let { put("linux_path", it) }
                        })
                    }
                })
            }

            if (inputJson.isNotBlank()) put("input_json", inputJson)
            outputExt?.let { put("output_ext", it) }

            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { t ->
                        put(JSONObject().apply {
                            put("name", t.name)
                            put("description", t.description)
                            if (t.parameters.isNotEmpty()) {
                                put("parameters", JSONObject().apply {
                                    t.parameters.forEach { (k, v) ->
                                        put(k, JSONObject().apply {
                                            put("type", v.type)
                                            put("description", v.description)
                                            v.enumValues?.takeIf { it.isNotEmpty() }?.let { put("enum", JSONArray(it)) }
                                        })
                                    }
                                })
                            }
                            if (t.required.isNotEmpty()) put("required", JSONArray(t.required))
                            t.propertyOrdering?.takeIf { it.isNotEmpty() }?.let { put("property_ordering", JSONArray(it)) }
                        })
                    }
                })
            }
            if (thinkingLevel != ThinkingLevel.OFF) put("thinking_level", thinkingLevel.name)
            if (streaming) put("streaming", true)
        }.toString()
    }

    /**
     * Dispatch a serialized request to [ModelExecutionService] and wait for
     * the result file. Returns the result JSON string, or `null` on any
     * dispatch failure (timeout / IO / service unavailable) so callers can
     * fall back to the in-process path.
     */
    suspend fun dispatch(context: Context, requestJson: String): String? {
        // [fix/audit-s3l2] Only retry on a CONFIRMED worker death. The previous
        // code retried on ANY null result, but dispatchOnce also returns null
        // on timeout — where the worker may STILL be executing the provider
        // call (slow image/long-text generation). Re-dispatching then started
        // a second provider call → duplicate billing / duplicate generation.
        // A timeout must NOT be transparently retried; only the stale-key
        // worker-death (worker beat then died before writing any output) is
        // safe to re-run onto a fresh process.
        val first = dispatchOnce(context, requestJson)
        if (first.result != null) return first.result
        return if (first.workerDied) {
            Log.w(TAG, "dispatch got no result after confirmed worker death — one retry onto a fresh process (stale-key cache / crash)")
            dispatchOnce(context, requestJson).result
        } else {
            // timeout / IO / service-unavailable: do NOT re-dispatch (worker
            // may still be running the request); surface null to the caller
            // for its in-process fallback.
            null
        }
    }

    private suspend fun dispatchOnce(context: Context, requestJson: String): DispatchOutcome {
        val dir = try {
            val root = File(context.cacheDir, STAGING_ROOT)
            root.mkdirs()
            val d = File(root, "run-${UUID.randomUUID()}")
            if (!d.mkdir()) return DispatchOutcome(null, false)
            d
        } catch (_: Exception) { return DispatchOutcome(null, false) }

        val requestFile = File(dir, "request.json")
        val resultFile = File(dir, ModelExecutionService.RESULT_FILE)
        try {
            requestFile.writeText(requestJson)
        } catch (e: Exception) {
            Log.w(TAG, "write request failed: ${e.message}")
            dir.deleteRecursively()
            return DispatchOutcome(null, false)
        }

        try {
            val intent = Intent(context, ModelExecutionService::class.java).apply {
                putExtra(ModelExecutionService.EXTRA_REQUEST_DIR, dir.absolutePath)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startService failed: ${e.message}")
            logDispatchFailure(dir)
            return DispatchOutcome(null, false)
        }

        // Poll for the result file.
        // [T-stale-apikey-worker-cache] workerDied: set when the poll loop
        // short-circuits on a confirmed dead worker (stale beat, no output).
        // It skips the post-poll cleanup waits — the worker is already dead,
        // so there is nothing left to wait for — and lets the outer dispatch()
        // re-dispatch promptly instead of burning TERMINAL/REAP waits.
        var workerDied = false
        val result: String? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            var read: String? = null
            while (true) {
                if (resultFile.exists()) {
                    read = try {
                        resultFile.readText()
                    } catch (e: Exception) {
                        Log.w(TAG, "read result failed: ${e.message}")
                        null
                    }
                    break
                }
                // [T-stale-apikey-worker-cache] Fast worker-death short-circuit:
                // the worker beat at least once (it accepted this request) and
                // then went silent WITHOUT ever writing a result — it died
                // (e.g. the stale-key abort kills the process before any
                // output). Waiting out the full 3-minute timeout would stall
                // the caller for nothing; exit the poll immediately so the
                // gateway-level retry can re-dispatch onto a fresh process.
                if (workerDiedWithoutResult(dir)) {
                    Log.w(TAG, "worker died without a result (beat stale, no result.json) — ending poll early, dir=${dir.name}")
                    workerDied = true
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
            read
        }

        if (result != null) {
            // [TF-B ack] Tell the worker we consumed the result so it can
            // self-reap immediately instead of waiting out its ack timeout.
            try {
                ModelExecutionMailbox.writeClientAck(dir)
            } catch (_: Exception) {}
        } else if (workerDied) {
            // [T-stale-apikey-worker-cache] Confirmed dead worker: no cancel
            // (nobody left to read it) and no cleanup waits (nothing can still
            // be writing). Leave the dir as an orphan for the reaper and let
            // the outer dispatch() re-dispatch NOW.
            Log.w(TAG, "run dir orphaned after confirmed worker death, dir=${dir.name}")
        } else {
            Log.w(TAG, "timeout waiting for model-exec result — falling back in-process")
            // Try to stop the worker (it may be mid-run); it self-reaps on cancel
            // / shutdown. Then settle briefly before deleting the dir so we never
            // delete under a worker still writing result.json.
            try { ModelExecutionMailbox.writeCancel(dir) } catch (_: Exception) {}
        }

        // [TF-F P0-A] Delete the request dir ONLY after BOTH:
        //   1. the terminal marker exists (worker finished writing run-dir
        //      files: stream flushed + result committed + final state), AND
        //   2. the worker's process is confirmed gone for THIS run's pid ref
        //      (or there never was a valid ref).
        // The old code deleted on result.json/cancel.ack alone, killing the
        // worker mid-finishRequest (state.json ENOENT FATAL). Timeout with the
        // worker still alive/unknown → leave the dir as an ORPHAN (never
        // delete) and let the orphan reaper reclaim later when the pid is
        // dead + terminal.
        val terminalSeen = if (workerDied) false else awaitTerminal(dir, TERMINAL_WAIT_MS)
        val reaped = if (workerDied) false else waitForWorkerReap(dir, REAP_WAIT_MS)
        if (!workerDied && terminalSeen && reaped) {
            try { dir.deleteRecursively() } catch (_: Exception) {}
        } else if (!workerDied) {
            Log.w(
                TAG,
                "run dir kept as orphan (terminal=$terminalSeen reaped=$reaped dir=${dir.name}) — " +
                    "pid still alive/unknown or terminal not reached; orphan reaper may reclaim",
            )
        }
        return DispatchOutcome(result, workerDied)
    }

    /** Outcome of a single [dispatchOnce] attempt. */
    private data class DispatchOutcome(val result: String?, val workerDied: Boolean)

    /**
     * [TF-F] Wait (bounded) for the run's terminal marker. Returns true when
     * the worker finished writing all run-dir files (terminal.json present).
     * The old code treated result.json as "worker is done writing"; now only
     * terminal.json (the worker's LAST write) counts as the write-barrier.
     */
    private suspend fun awaitTerminal(dir: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ModelExecutionRunDir.terminalPresent(dir)) return true
            // Already committed the result → the terminal write is imminent;
            // keep polling (bounded) rather than assuming.
            delay(POLL_INTERVAL_MS)
        }
        return ModelExecutionRunDir.terminalPresent(dir)
    }

    /**
     * [TF-J2] Wait (bounded) for the :modelservice worker's run to be SAFE TO
     * DELETE. On a hidepid=invisible device the main process cannot read the
     * worker's /proc entry (it sees only itself), so the old probeLiveness
     * always returned UNKNOWN here and every run dir leaked as an orphan. The
     * run is safe to delete when any of these completes the run's life:
     *   - terminal marker present (worker's LAST durable write), or
     *   - result.json committed AND no live beat (worker finished and reaped),
     *     or
     *   - the liveness beat file has gone silent/stale (worker stopped beating
     *     → process gone or finishing).
     * A run with a still-fresh beat and no terminal is NOT safe → keep polling.
     */
    private suspend fun waitForWorkerReap(dir: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (reapSafe(dir)) return true
            delay(REAP_POLL_MS)
        }
        val safe = reapSafe(dir)
        if (!safe) {
            Log.w(
                TAG,
                "worker for ${dir.name} not confirmed done within ${timeoutMs}ms " +
                    "(beat still fresh / no terminal / no result) — NOT deleting dir",
            )
        }
        return safe
    }

    /** True when THIS run dir's worker is provably done writing / reaped. */
    private fun reapSafe(dir: File): Boolean {
        // Worker's LAST durable marker — once present, it will self-reap.
        if (ModelExecutionRunDir.terminalPresent(dir)) return true
        // result committed by a worker that has stopped beating.
        val result = File(dir, ModelExecutionMailbox.FILE_RESULT).exists()
        val beatFile = File(dir, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        val beatGoneOrStale = !beatFile.isFile || ModelExecutionRunDir.beatStale(dir)
        return result && beatGoneOrStale
    }

    /**
     * [T-stale-apikey-worker-cache] True when the worker for this run dir is
     * provably DEAD without ever producing a result: it beat at least once
     * (so it existed and accepted the request), the beat has gone stale
     * (>= ModelExecutionRunDir.LIVENESS_STALE_MS of silence), and no result /
     * cancel-ack / terminal marker was written. This is the signature of a
     * process kill — most commonly the stale-key abort, but any hard crash
     * matches too. Used by the dispatch poll loop to stop waiting the full
     * REQUEST_TIMEOUT_MS on a worker that can never answer.
     */
    private fun workerDiedWithoutResult(dir: File): Boolean {
        val beatFile = File(dir, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        // Never beat → still starting up (or beat file lost); NOT a confirmed death.
        if (!beatFile.isFile) return false
        // Still beating → alive; keep waiting.
        if (!ModelExecutionRunDir.beatStale(dir)) return false
        // Beat went stale — dead unless it left any output behind.
        val hasOutput = File(dir, ModelExecutionService.RESULT_FILE).exists() ||
            File(dir, ModelExecutionMailbox.FILE_CANCEL_ACK).exists() ||
            ModelExecutionRunDir.terminalPresent(dir)
        return !hasOutput
    }

    /** Extract the stable runId from a `run-<uuid>` dir name (shared helper). */
    internal fun runIdOf(dir: File): String? {
        val name = dir.name
        val prefix = "run-"
        return if (name.startsWith(prefix) && name.length > prefix.length) {
            name.substring(prefix.length)
        } else name
    }

    private fun logDispatchFailure(dir: File) {
        try { dir.deleteRecursively() } catch (_: Exception) {}
    }
}
