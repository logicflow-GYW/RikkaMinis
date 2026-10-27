package com.rikkaminis.app.sandbox.offload

import android.content.Context
import android.util.Base64
import android.util.Log
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.directionalModalities
import com.rikkaminis.app.data.model.modalityNamesInOrder
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.debug.LLMRequestLog
import com.rikkaminis.app.sandbox.NativeOffloadHandler
import com.rikkaminis.app.sandbox.NativeOffloadRequest
import com.rikkaminis.app.sandbox.NativeOffloadResult
import com.rikkaminis.app.sandbox.PRootKernel
import com.rikkaminis.app.sandbox.hasDotDotSegment
import com.rikkaminis.app.sandbox.normalizeLinuxPath
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `minis-model-use` — list, search, and invoke LLM models from Alpine shell.
 * Mirrors iOS ModelUseOffload.m + ModelUseOffloadBridge.swift.
 *
 * Subcommands:
 *   minis-model-use list [--provider <name>] [--modality <m>]
 *   minis-model-use search <query> [--provider <name>] [--modality <m>]
 *   minis-model-use run --model <id_or_name> [prompt] [--input <path>] [--output <path>]
 *                       [--system <text>] [--system-file <path>]
 *                       [--max-tokens N] [--temperature F]
 *
 * Only entries/groups exposed via the "Available Models in Agent Loop" section
 * in Settings > Model Groups are visible (see ProviderRepository.resolvedAgentLoopEntries).
 */
class ModelUseOffloadHandler(
    private val context: Context,
    private val providerRepository: ProviderRepository,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        // `--help` is a flag, so `positional` is empty for it too: the exit
        // code has to be decided on the flag, or `--help` answers 2.
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.hasFlag("h", "help")) 0 else 2, HELP)
        }

        return try {
            when (val sub = args.positional[0]) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "run" -> cmdRun(args, request)
                else -> NativeOffloadResult(2, "minis-model-use: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            NativeOffloadResult(
                1,
                JSONObject().put("error", "model_use_failed")
                    .put("message", e.message ?: "unknown").toString() + "\n",
            )
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val providerFilter = args.get("provider")
        val modalityFilter = args.get("modality")?.lowercase()

        var entries = providerRepository.resolvedAgentLoopEntries()
        // [dv16-A04] The same single matcher as search/run. This used to be a
        // case-sensitive substring match on model.provider/label — a filter
        // that `run --provider` (exact label/id) would then refuse.
        if (providerFilter != null) {
            entries = entries.filter { entry ->
                val inst = providerRepository.instance(entry.providerInstanceId)
                inst != null && providerMatches(
                    providerFilter, inst.label, inst.id, inst.providerType.displayName,
                )
            }
        }
        if (modalityFilter != null) {
            entries = entries.filter { matchesModality(it, modalityFilter) }
        }

        val body = JSONObject().apply {
            put("models", JSONArray().apply { entries.forEach { put(entryDict(it)) } })
            put("count", entries.size)
            if (entries.isEmpty()) put("hint", NO_MODELS_HINT)
            else put("usage", USAGE_HINT)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val query = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(
                2,
                "minis-model-use search: no query. Usage: minis-model-use search <query>\n",
            )
        val q = query.lowercase()
        val providerFilter = args.get("provider")
        val modalityFilter = args.get("modality")?.lowercase()

        var matches = providerRepository.resolvedAgentLoopEntries().filter { entry ->
            entry.model.id.lowercase().contains(q) ||
                entry.model.displayName.lowercase().contains(q) ||
                entry.model.provider.lowercase().contains(q)
        }
        // [dv16-A04] `--provider` was documented on this subcommand's help
        // line but never read — the option was consumed into OffloadArgs and
        // dropped, so `search gpt --provider X` returned every provider's
        // gpt models. Same single matcher as list/run.
        if (providerFilter != null) {
            matches = matches.filter { entry ->
                val inst = providerRepository.instance(entry.providerInstanceId)
                inst != null && providerMatches(
                    providerFilter, inst.label, inst.id, inst.providerType.displayName,
                )
            }
        }
        if (modalityFilter != null) {
            matches = matches.filter { matchesModality(it, modalityFilter) }
        }

        val body = JSONObject().apply {
            put("query", query)
            put("models", JSONArray().apply { matches.forEach { put(entryDict(it)) } })
            put("count", matches.size)
            if (matches.isEmpty()) put("hint", NO_MODELS_HINT)
            else put("usage", USAGE_HINT)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    }

    private fun cmdRun(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val modelArg = args.get("model")
            ?: return NativeOffloadResult(2, "--model is required. Usage: minis-model-use run --model <id_or_name>\n")

        // Optional provider scoping — disambiguates when multiple instances expose the same model_id.
        val providerFilter = args.get("provider")

        // Resolve entry (restricted to agent-loop-visible)
        val entry = resolveEntry(modelArg, providerFilter)
            ?: return NativeOffloadResult(
                2,
                JSONObject().put("error", "model_not_found")
                    .put(
                        "message",
                        if (providerFilter != null)
                            "No model '$modelArg' under provider '$providerFilter'. Use 'minis-model-use list' to see available combinations."
                        else
                            "Model '$modelArg' not visible to the agent. Add it in Settings > Model Groups > Available Models in Agent Loop.",
                    )
                    .toString() + "\n",
            )

        // Modality precheck: if --output extension implies a media modality
        // the model doesn't advertise, fail fast before hitting the API.
        // Mirrors iOS ModelUseOffloadBridge.performRun:208-228.
        // [da1024-I] `--output` now joins onto the shell cwd exactly like
        // `--input`/`--system-file` above — [resolveMaybeRelative] is the single
        // join implementation. A bare relative path only survives when the cwd
        // itself is missing/relative; then we still fail closed with a clear
        // "absolute path required" error instead of dropping it onto the rootfs
        // root (which `read_image` would later reject). `file://` URLs are
        // already absolute and are left untouched.
        val outputPath = args.get("output")?.let { raw ->
            if (raw.startsWith("file://")) raw else resolveMaybeRelative(raw, request.cwd)
        }
        if (outputPath != null && !outputPath.startsWith("/") && !outputPath.startsWith("file://")) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "invalid_output_path")
                    .put(
                        "message",
                        "--output must be an absolute path (e.g. /var/minis/workspace/out.jpg) or a " +
                            "relative path resolvable against the shell cwd. Got the relative path " +
                            "'$outputPath', which could not be resolved (no absolute guest cwd).",
                    )
                    .toString() + "\n",
            )
        }
        val outputExt = outputPath?.substringAfterLast('.', "")?.lowercase().orEmpty()
        // [dv21-A05] Effective modalities of this entry: models.dev-enriched BASE
        // model + the user's overrides, canonicalized once through the shared
        // resolver. Reading `entry.model.*` raw skipped enrichment entirely, and
        // all 39 built-in models declare null modality fields by construction —
        // so every image/TTS-capable built-in was refused here as incapable.
        val effectiveModel = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)
        val modalities = effectiveModel.directionalModalities.toSet()
        val requiredModality = when {
            isImageExt(outputExt) -> "image_output".takeIf { "image_output" !in modalities }
            isAudioExt(outputExt) -> "audio_output".takeIf { "audio_output" !in modalities }
            isVideoExt(outputExt) -> "video_output".takeIf { "video_output" !in modalities }
            else -> null
        }
        if (requiredModality != null) {
            // Same canonical vocabulary (and order) as `minis-model-use list`.
            val supported = modalityNamesInOrder.filter { it in modalities }
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "modality_not_supported")
                    .put("message", "Model '${entry.model.displayName}' does not support $requiredModality. Supported modalities: ${supported.joinToString(", ")}. Use 'minis-model-use list' to find a model with the required capability.")
                    .toString() + "\n",
            )
        }

        // System prompt: --system takes precedence over --system-file
        // [audit-0917] A --system-file that cannot be read is an explicit error,
        // not a silent system-prompt drop (mirrors the --input branch below).
        val sysFile = args.get("system-file")?.let { readLinuxPath(it, request.sessionId, request.cwd) }
        if (args.get("system-file") != null && sysFile == null) {
            return NativeOffloadResult(
                2,
                "minis-model-use run: cannot read --system-file '${args.get("system-file")}'\n",
            )
        }
        val explicitSystem = args.get("system") ?: sysFile

        // Parse input messages: positional prompt | --input <path>
        // [B14-fix-r14] stdin was NEVER a real channel: the native_offload
        // protocol forwards argv/env/cwd only (no stdin pipe — the handler
        // runs in-process inside the app, not in the shell), so a piped
        // call like `echo 'q' | minis-model-use run --model X` used to
        // silently send an empty-messages request (HTTP 400). The piped
        // usage was documented in HELP/agent-prompt but never worked. Fix:
        // accept the prompt as a positional argument (same plain-text →
        // single user message path stdin would have taken), and fail fast
        // with a clear message instead of sending an empty request.
        val positionalPrompt = args.positional.getOrNull(1)
        val inputText = when {
            args.get("input") != null -> readLinuxPath(args.get("input")!!, request.sessionId, request.cwd)
                ?: return NativeOffloadResult(
                    2,
                    "minis-model-use run: cannot read --input '${args.get("input")}'\n",
                )
            positionalPrompt != null -> positionalPrompt
            else -> return NativeOffloadResult(
                2,
                JSONObject().put("error", "missing_input")
                    .put(
                        "message",
                        "no input: minis-model-use does not read stdin (the native_offload protocol " +
                            "has no stdin channel). Pass the prompt as a positional argument — " +
                            "minis-model-use run --model <id> 'your prompt' — or write it to a file " +
                            "and pass --input <path>.",
                    )
                    .toString() + "\n",
            )
        }
        val parsed = try {
            parseMessages(inputText, request.sessionId, request.cwd)
        } catch (e: ImageInputError) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "image_input_error")
                    .put("message", e.message ?: "image input could not be resolved")
                    .toString() + "\n",
            )
        } catch (e: AudioInputError) {
            // [GH#67] Malformed input_audio → explicit error, not a silent drop.
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "audio_input_error")
                    .put("message", e.message ?: "audio input could not be parsed")
                    .toString() + "\n",
            )
        }
        // Any `role: system` messages from the input JSON are folded into the
        // system prompt — the Android LLMMessage enum only exposes user/assistant.
        val inlineSystem = parsed.filter { it.role == "system" }
            .joinToString("\n") { it.content }
        val nonSystem = parsed.filter { it.role != "system" }

        // [GH#67] Audio input needs a model that declares the audio input
        // modality — explicit error instead of the historical silent drop.
        // Mirrors iOS ModelUseOffloadBridge's hasAudioInput gate.
        if (nonSystem.any { it.audios.isNotEmpty() } &&
            "audio_input" !in modalities
        ) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "modality_not_supported")
                    .put(
                        "message",
                        "Model '${entry.model.displayName}' does not support audio_input, " +
                            "but the input contains an input_audio block. " +
                            "Use 'minis-model-use list --modality audio' to find an audio-capable model.",
                    )
                    .toString() + "\n",
            )
        }

        val messages = nonSystem.map { textMessage(it.role, it.content, it.audios) }
        val hasAudioInput = messages.any { it.audioParts.isNotEmpty() }
        // [T-android-model-use-multimodal] Forward image_url blocks as the
        // top-level `imageParts` argument — providers (OpenAI / Anthropic /
        // Gemini) attach these to the LAST user message in the conversation
        // when building the wire request. Collecting images from the LAST
        // user-role ParsedMessage matches that contract: any image attached
        // to an earlier turn would be silently ignored on the provider side
        // (this is a known limitation; iOS has the same per-call shape).
        val imageParts = nonSystem.lastOrNull { it.role == "user" }?.images.orEmpty()
        val systemPrompt = when {
            explicitSystem != null && inlineSystem.isNotEmpty() -> "$explicitSystem\n\n$inlineSystem"
            explicitSystem != null -> explicitSystem
            inlineSystem.isNotEmpty() -> inlineSystem
            else -> null
        }

        val maxTokens = args.get("max-tokens")?.toIntOrNull() ?: 4096
        val temperature = args.get("temperature")?.toDoubleOrNull()

        // [fix/da1015-modeluse-proc] No provider is built in this process any
        // more. `:app` never sends the request (the CLI dispatches to
        // :modelservice, which builds its own provider), so
        // `ProviderFactory.create` here only ever produced two side effects for
        // a request somebody else makes:
        //   • a [com.rikkaminis.app.data.KeyRoulette] draw — LRU state plus a
        //     non-atomic rewrite of `key_roulette.json`, i.e. a SECOND draw
        //     milliseconds before the worker's own, competing with it on the
        //     same file; and
        //   • a [com.rikkaminis.app.network.ConnectionWarmer] TLS/DNS warm-up.
        // The object was then used for exactly one thing — the type gate below —
        // so the gate is now answered from the instance's provider type, which
        // is the same input ProviderFactory uses to pick the class
        // (ModelUseProtocol.isOpenAiCompatibleProviderType).
        //
        // Key PRESENCE is still pre-checked here: it preserves the CLI's own
        // fail-fast message and exit code instead of spending a dispatch round
        // trip to learn the same thing. The value itself is not read or passed —
        // the worker re-reads it from the same encrypted prefs.
        if (providerRepository.loadApiKey(entry.providerInstanceId) == null) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "missing_api_key")
                    .put("message", "No API key configured for provider ${entry.model.provider}.")
                    .toString() + "\n",
            )
        }
        val instance = providerRepository.instance(entry.providerInstanceId)
            ?: return NativeOffloadResult(2, "minis-model-use run: provider instance not found\n")
        val openAiCompatible = ModelUseProtocol.isOpenAiCompatibleProviderType(instance.providerType)

        // [GH#67] input_audio serialization is implemented for the OpenAI
        // chat/completions + responses paths only. Other provider types would
        // drop the audio at their own serialization layer — fail loudly here
        // instead. Mirrors iOS ModelUseOffloadBridge.
        if (hasAudioInput && !openAiCompatible) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "audio_input_unsupported_provider")
                    .put(
                        "message",
                        "input_audio is only supported for OpenAI-compatible providers " +
                            "(chat/completions and responses paths) for now; provider type " +
                            "'${instance.providerType}' would silently drop the audio.",
                    )
                    .toString() + "\n",
            )
        }

        // [T-android-model-use-passthrough-mode] Explicit passthrough envelope —
        // the raw-mode escape hatch. Checked before image routing and message
        // dispatch because body_mode=replace requests may carry no messages at
        // all. Only OpenAI-compatible providers; others error clearly. Mirrors
        // iOS ModelUseOffloadBridge passthrough branch.
        //
        // [fix/audit3-1015 F-03] The HTTP call runs in :modelservice.
        // ProviderBoundary forbids provider calls in this process and that
        // boundary is load-bearing — the old in-process call could only ever
        // come back `passthrough_failed`, so the mode was 100% dead. This
        // branch assembles the request (shared pure body builder), dispatches
        // it, and shapes the reply.
        val ptSpec = ModelUseProtocol.parsePassthroughEnvelope(inputText)
        if (ptSpec.active) {
            if (!openAiCompatible) {
                return NativeOffloadResult(
                    2,
                    JSONObject().put("error", "passthrough_unsupported")
                        .put(
                            "message",
                            "passthrough mode is not supported for provider type " +
                                "'${instance.providerType}' yet — only OpenAI-compatible " +
                                "providers. Use the standard OpenAI input format instead.",
                        ).toString() + "\n",
                )
            }
            val ptWarnings = ptSpec.warnings.toMutableList()
            val ptBody = ModelUseProtocol.buildRawPassthroughBody(
                spec = ptSpec,
                modelId = entry.model.id,
                systemPrompt = systemPrompt,
                messages = messages.map { it.role.value to it.content },
                maxTokens = maxTokens,
                warnings = ptWarnings,
            )
            // [dv23-A03] The serialized model must be the SAME enriched model the
            // guards above judged: `entry.model` is only `baseModel + overrides`,
            // so every capability field models.dev supplies (output-/inputModalities,
            // maxOutputTokens, contextWindow, reasoning effort tiers) was dropped at
            // the process boundary — :modelservice rebuilt an LLMModel with them
            // null and e.g. never sent `responseModalities: ["TEXT","IMAGE"]`, so a
            // --output out.png run that passed the gate here came back text-only.
            // ponytail: 复用 :190 已算好的 `effectiveModel`，不在此处重新解析 —— 这正是
            // 共享 resolver 的字面调用计数保持恰为 2（:190 与 :1007）的原因，
            // ModelUseModalityParityTest 钉住了这个计数（该断言按原文计数，
            // 因此本注释刻意不复述那个字面调用形式）。 | 天花板: 某个 buildRequestJson
            // 调用点若移出 cmdRun（拿不到该局部变量）就必须自解析，届时该测试计数断言要同步改。
            // | 升级触发: 出现第 3 个 buildRequestJson 调用点，或 cmdRun 被拆分。
            val ptRequestJson = ModelExecutionDispatcher.buildRequestJson(
                instance = instance,
                model = effectiveModel,
                messages = messages,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                imageParts = imageParts,
                inputJson = inputText,
                outputExt = outputExt,
                passthroughRequest = ModelUseProtocol.buildPassthroughWireRequest(ptSpec, ptBody),
            )
            val ptRemote = runBlocking { ModelExecutionDispatcher.dispatch(context, ptRequestJson) }
            return shapeRawPassthrough(
                spec = ptSpec,
                warnings = ptWarnings,
                entry = entry,
                remoteResult = ptRemote,
                outputPath = outputPath,
                sessionId = request.sessionId,
            )
        }


        // [T-android-model-use-passthrough-mode GH#72] Standard mode: promote the
        // explicit `extra_body` / `extra_headers` envelope (previously image-
        // path-only) to the chat/responses text path too, and honor a custom
        // absolute `endpoint` path. Explicit envelope only — implicit unknown
        // top-level keys stay image-path-only so the chat schema keeps its
        // OpenAI top-level semantics. Mirrors iOS standard-mode promotion.
        // [T-model-use-passthrough-warnings] `callWarnings` collects every
        // provided-but-ignored/downgraded field (starting with envelope-
        // lookalike warnings from the passthrough parser); the worker's
        // `applied_extras` confirms which passthrough capabilities actually took
        // effect. Both are attached to the final result JSON. Mirrors iOS
        // performRun.
        val callWarnings = ptSpec.warnings.toMutableList()
        // [fix/da1015-modeluse-proc] The `extra_body` / `extra_headers` /
        // `endpoint` envelope is INTERPRETED by `:modelservice`, which re-parses
        // this same `input_json` with the same shared parsers and stamps the
        // values onto the provider IT builds. Parsing it here as well only ever
        // assigned those values to the (now gone) in-process provider object —
        // assignments that reached no request, since this process no longer
        // sends one.
        //
        // The parsing is kept for what it still uniquely provides: the
        // CALLER-FACING WARNINGS. `extra_headers` present but not a JSON object
        // is the case that proves it — the worker's copy of that parser returns
        // silently for it, so without these lines that input error is invisible
        // to the user (neither JSON body nor stderr mentions it).
        if (openAiCompatible) {
            val chatExtra = parseChatExtraBody(inputText, callWarnings)
            if (chatExtra.isNotEmpty()) {
                Log.i(TAG, "[ModelUseRoute] CHAT-PASSTHROUGH extra_body keys=[${chatExtra.keys.sorted().joinToString(",")}]")
            }
            parseExtraHeaders(inputText, callWarnings)
            parseCustomEndpointPath(inputText)?.let { customPath ->
                Log.i(TAG, "[ModelUseRoute] CUSTOM-ENDPOINT absolute path=$customPath")
            }
        }

        // [T-android-image-endpoint-mode] Image-output routing (mirrors iOS
        // ModelUseOffloadBridge image branch). For OpenAI-compatible API-key
        // instances whose target model emits images, route through
        // /v1/images/generations per the instance's imageEndpointMode (with
        // auto-probe + cache), instead of the chat-completions path. OAuth
        // instances are excluded — the Codex gpt-image-2 path is handled inside
        // OpenAIProvider's isCodexImageModel branch on the normal sendMessage
        // call below, and a Codex OAuth token can't reach the Images API. On a
        // route-missing failure (auto mode) we fall through to the chat path.
        // [ModelExecutionService] Remote-first execution: run the provider
        // call in the :modelservice process so its native heap (DirectByteBuffer
        // response buffers, JSON/image decoding) dies with the process — the
        // only leak containment for app-native heap that GC can't reclaim
        // (logs showed Post-recycle GC freed 0MB). On any dispatch failure or
        // unparseable result the call falls back to the in-process path below
        // (behaviour unchanged, leak containment degraded).
        // [dv23-A03] Same as the passthrough branch above: the model that crosses
        // into :modelservice must be the enriched `effectiveModel`, not the raw
        // `entry.model` (see the note there).
        val remoteRequestJson = ModelExecutionDispatcher.buildRequestJson(
            instance = instance,
            model = effectiveModel,
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            inputJson = inputText,
            outputExt = outputExt,
        )
        val remoteResult = runBlocking { ModelExecutionDispatcher.dispatch(context, remoteRequestJson) }
        if (remoteResult != null) {
            val remote = try { JSONObject(remoteResult) } catch (e: Throwable) { null }
            if (remote != null) {
                // [fix/audit3-1015 F-10] The image endpoint auto-probe runs in
                // :modelservice now; its answer rides back on the result and is
                // persisted HERE, in the process that owns ProviderRepository —
                // workers must not write SharedPreferences. Read before the error
                // branch: the cache is valid even when the (follow-up) chat call
                // then failed.
                remote.optString("image_endpoint_resolved", "").takeIf { it.isNotEmpty() }?.let { cached ->
                    com.rikkaminis.app.data.model.ImageEndpointMode.values()
                        .firstOrNull { it.name == cached }
                        ?.let { providerRepository.setImageEndpointResolved(instance.id, it) }
                }
                val remoteErr = remote.optString("error", "")
                if (remoteErr.isNotEmpty()) {
                    Log.w(TAG, "remote run failed: $remoteErr")
                    return NativeOffloadResult(
                        remote.optInt("exit_code", 1),
                        JSONObject().put("error", remoteErr)
                            .put("message", remote.optString("message", "")).toString() + "\n",
                    )
                }
                val remoteMedia = (remote.optJSONArray("media_files") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val m = arr.optJSONObject(i) ?: return@mapNotNull null
                        val b64 = m.optString("data", "")
                        if (b64.isEmpty()) return@mapNotNull null
                        com.rikkaminis.app.data.model.LLMMediaAttachment(
                            type = com.rikkaminis.app.data.model.LLMMediaAttachment.MediaType.values()
                                .firstOrNull { it.value == m.optString("type", "") }
                                ?: com.rikkaminis.app.data.model.LLMMediaAttachment.MediaType.IMAGE,
                            mimeType = m.optString("mime_type", "application/octet-stream"),
                            // [dv21-A06] Tolerant + non-throwing, via the ONE decode
                            // entry for the offload wire. The bare
                            // `Base64.decode(b64, Base64.DEFAULT)` here threw an
                            // IllegalArgumentException on a single malformed
                            // attachment, which escaped `mapNotNull` and discarded the
                            // already-generated text reply plus every later attachment.
                            data = OffloadWireBase64.decode(b64) ?: return@mapNotNull null,
                        )
                    }
                }
                val remoteUsage = remote.optJSONObject("usage")?.let { u ->
                    com.rikkaminis.app.data.model.LLMUsage(
                        inputTokens = u.optInt("input_tokens", 0),
                        outputTokens = u.optInt("output_tokens", 0),
                    )
                }
                val remoteWarnings = (remote.optJSONArray("warnings") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it, "").ifEmpty { null } }.toMutableList()
                }
                val remoteExtras = remote.optJSONObject("applied_extras") ?: JSONObject()
                return writeRunOutput(
                    entry = entry,
                    response = com.rikkaminis.app.data.model.LLMResponse(
                        text = remote.optString("text", ""),
                        stopReason = remote.optString("stop_reason", "").ifEmpty { null },
                        usage = remoteUsage,
                        mediaAttachments = remoteMedia,
                    ),
                    outputPath = outputPath,
                    outputExt = outputExt,
                    request = request,
                    // [fix/da1015-modeluse-proc] MERGE, not replace. This line
                    // used to be `callWarnings = remoteWarnings`, which threw
                    // away every warning collected above — i.e. the ones only
                    // this process can produce. Local first, then the worker's,
                    // de-duplicated on first occurrence (both processes run the
                    // same extra_body parser, so the overlap collapses).
                    callWarnings = ModelUseProtocol.mergeCallWarnings(callWarnings, remoteWarnings),
                    appliedExtras = remoteExtras,
                )
            }
            Log.w(TAG, "remote result unparseable — falling back in-process")
        }
        // TF-D: the remote (:modelservice) path IS the executor. The app process
        // MUST NOT fall back to an in-process provider call — its native heap
        // would climb in the main process, the exact problem this isolation
        // exists to solve.
        //
        // [fix/audit3-1015 F-10] This is where the CLI-side image route used to
        // run (`tryImageGenerationRoute`), i.e. the app re-decided image routing
        // and made an in-process provider.generateImage call whenever the remote
        // result was missing or unparseable. Routing now has exactly one owner
        // (`ModelUseProtocol.decideImageRoute`, called by the worker) and the
        // app never calls a provider, so the fallback is a structured error.
        Log.w(TAG, "remote model-use run produced no usable result — returning structured error")
        return NativeOffloadResult(
            1,
            JSONObject().put("error", "model_use_remote_unavailable")
                .put(
                    "message",
                    "The model execution service did not produce a result for ${entry.model.id}. " +
                        "Please retry the run.",
                ).toString() + "\n",
        )
    }

    // [dv14-A4] ISSUE-08: a private `imageReservedKeys` set used to sit here.
    // It was DEAD — no call site in this file, and the app process no longer
    // owns the image route (routing has one owner, `decideImageRoute`, called
    // by the worker) — but it was the second of three copies of the same
    // 17-key dialect, so the next change to it would have looked authoritative
    // and gone nowhere. The set lives in [ModelUseProtocol] only; this file
    // reads the dialect through [ModelUseProtocol.parseImagePassthrough] when
    // it needs it at all.

    // MARK: - Call feedback [T-model-use-passthrough-warnings]

    /**
     * Append collected `warnings` and `applied_extras` to a result's JSON body.
     * Both are omitted when empty (no noise on clean calls); warnings are
     * de-duplicated (the same type mismatch can be seen by more than one
     * parser on the image path). Non-JSON outputs pass through unchanged.
     * Mirrors iOS ModelUseOffloadBridge.attachCallFeedback.
     */
    private fun attachCallFeedback(
        result: NativeOffloadResult,
        warnings: List<String>,
        extras: JSONObject?,
    ): NativeOffloadResult {
        if (warnings.isEmpty() && (extras == null || extras.length() == 0)) return result
        val body = try {
            val t = result.output.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return result
        if (warnings.isNotEmpty()) {
            val merged = LinkedHashSet<String>()
            body.optJSONArray("warnings")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i)?.let { merged.add(it) }
            }
            merged.addAll(warnings)
            body.put("warnings", JSONArray(merged.toList()))
        }
        if (extras != null && extras.length() > 0 && !body.has("applied_extras")) {
            body.put("applied_extras", extras)
        }
        return NativeOffloadResult(result.exitCode, body.toString(2) + "\n")
    }



    /**
     * Standard-mode chat `extra_body`: the EXPLICIT envelope only (implicit
     * unknown top-level keys stay image-path-only, per iOS). Values kept as raw
     * org.json types so JSONObject.put re-serializes them faithfully.
     */
    /**
     * [fix/audit3-1015 F-04] Delegates to the single shared dialect in
     * [ModelUseProtocol] (this was the in-process copy the worker's parser was
     * supposed to mirror; the mirror had drifted). Behavior here is unchanged —
     * the worker now agrees with it instead of the other way round.
     */
    private fun parseChatExtraBody(inputJson: String, warnings: MutableList<String>): Map<String, Any?> =
        ModelUseProtocol.parseChatExtraBody(inputJson, warnings)

    /**
     * Standard-mode `extra_headers` (string map).
     *
     * [da1019 ISSUE-01] Delegates to the single shared dialect in
     * [ModelUseProtocol]; the per-process copy here and the one in
     * `ModelExecutionService` had drifted (the worker dropped a non-object
     * `extra_headers` without a warning while this side warned).
     */
    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> =
        ModelUseProtocol.parseExtraHeaders(inputJson, warnings)

    /**
     * Parse a CUSTOM absolute endpoint path from top-level `endpoint` /
     * `image_endpoint` values. A value starting with "/" is an absolute path
     * replacing the entire URL path after scheme+host. Symbolic values
     * (auto/images-gen/chat) return null. Mirrors iOS parseCustomEndpointPath.
     *
     * [da1019 ISSUE-01] Delegates to [ModelUseProtocol] — the worker's copy of
     * this parser required nothing of the value, so it accepted a symbolic
     * endpoint mode as a path.
     */
    private fun parseCustomEndpointPath(inputJson: String): String? =
        ModelUseProtocol.parseCustomEndpointPath(inputJson)

    /**
     * Shape a passthrough reply that :modelservice executed into the CLI
     * contract. Field names, exit codes, `--output` degradation and warnings
     * are unchanged from the pre-F-03 in-process version — only the source of
     * the bytes moved: the worker performs the HTTP call and returns the body
     * as Base64, and file IO stays here, where session scoping lives.
     */
    private fun shapeRawPassthrough(
        spec: ModelUseProtocol.PassthroughSpec,
        warnings: MutableList<String>,
        entry: ModelEntry,
        remoteResult: String?,
        outputPath: String?,
        sessionId: String?,
    ): NativeOffloadResult {
        val remote = try { remoteResult?.let { JSONObject(it) } } catch (_: Throwable) { null }
        val remoteError = remote?.optString("error", "").orEmpty()
        if (remote == null || remoteError.isNotEmpty()) {
            val message = when {
                remoteResult == null -> "model service did not return a result"
                remote == null -> "model service returned an unparseable result"
                else -> remote.optString("message", "passthrough request failed")
            }
            Log.w(TAG, "raw passthrough failed: $message")
            return NativeOffloadResult(
                1,
                JSONObject().put("error", remoteError.ifEmpty { "passthrough_failed" })
                    .put("message", message)
                    .also { if (warnings.isNotEmpty()) it.put("warnings", JSONArray(warnings)) }
                    .toString() + "\n",
            )
        }

        val status = remote.optInt("http_status", 0)
        val url = remote.optString("endpoint_url", "")
        val contentType = remote.optString("content_type", "").ifEmpty { null }
        val data: ByteArray = try {
            Base64.decode(remote.optString("body_b64", ""), Base64.NO_WRAP)
        } catch (_: Throwable) {
            ByteArray(0)
        }

        val out = JSONObject()
            .put("passthrough", true)
            .put("http_status", status)
            .put("content_type", contentType ?: JSONObject.NULL)
            .put("bytes", data.size)
            .put("endpoint_url", url)
            .put("body_mode", spec.bodyMode)
            .put("model_id", entry.model.id)

        if (outputPath != null && PRootKernel.isLinuxPathUnderReadOnlyMount(outputPath)) {
            // [deep-audit-1016 FIX-C 2b] T219 read-only mount guard — same
            // contract as tools/FileWriteTool.kt (refuse, never silently write
            // elsewhere). Keeps the established `output_error` field so the
            // existing JSON contract (and the tests asserting it) is unchanged;
            // the body still comes back inline because the provider call itself
            // succeeded and is not re-runnable for free.
            out.put(
                "output_error",
                "'$outputPath' is inside a read-only mounted folder — nothing was written. " +
                    "Toggle writability in Settings → Mount External Folders if this is a mistake.",
            )
            inlineTextIfPossible(data, out)
        } else if (outputPath != null) {
            val hostFile = sessionAwareHostFile(outputPath, sessionId)
            if (hostFile == null) {
                out.put("output_error", "Could not resolve --output '$outputPath'")
                inlineTextIfPossible(data, out)
            } else {
                try {
                    hostFile.parentFile?.mkdirs()
                    hostFile.writeBytes(data)
                    logModelUseWrite(outputPath, hostFile, sessionId)
                    out.put("output_file", outputPath)
                } catch (e: Throwable) {
                    // Don't fail the whole call over an unwritable --output — the
                    // request already succeeded. Surface it + fall back to inline.
                    out.put("output_error", "Could not write --output at $outputPath: ${e.message}")
                    inlineTextIfPossible(data, out)
                }
            }
        } else {
            inlineTextIfPossible(data, out)
        }
        if (status < 200 || status >= 300) {
            out.put(
                "error_hint",
                "Provider returned HTTP $status; the raw response body (output file or inline) is unmodified.",
            )
        }
        if (warnings.isNotEmpty()) {
            out.put("warnings", JSONArray(warnings))
        }
        Log.i(TAG, "[ModelUseRoute] raw-passthrough done status=$status bytes=${data.size} url=${LLMRequestLog.redactURL(url)} warnings=${warnings.size}")
        // Non-2xx → non-zero exit code (raw body still delivered), else 0.
        val code = if (status in 200..299) 0 else 1
        return NativeOffloadResult(code, out.toString(2) + "\n")
    }


    /**
     * Inline a UTF-8-decodable response into the result (truncated at 64KB);
     * binary responses get a hint to re-run with --output. Mirrors iOS.
     */
    private fun inlineTextIfPossible(data: ByteArray, out: JSONObject) {
        val text = try { String(data, Charsets.UTF_8).takeIf { data.isEmpty() || it.toByteArray(Charsets.UTF_8).contentEquals(data) } } catch (_: Throwable) { null }
        if (text != null) {
            val capped = if (text.length > 65536) text.substring(0, 65536) + "…[truncated]" else text
            out.put("response_text", capped)
            if (text.length > 65536) out.put("truncated", true)
        } else {
            out.put("hint", "Response is binary (${data.size} bytes) and no --output was given — re-run with --output <path> to save it.")
        }
    }

    /**
     * [ModelExecutionService] Shared output writer for both the remote and
     * in-process run paths: media-first if the model returned image/audio/
     * video and --output has a media extension, else fall back to text.
     * Mirrors iOS ModelUseOffloadBridge.performRun (non-streaming branch).
     */
    private fun writeRunOutput(
        entry: ModelEntry,
        response: com.rikkaminis.app.data.model.LLMResponse,
        outputPath: String?,
        outputExt: String,
        request: NativeOffloadRequest,
        callWarnings: MutableList<String>,
        appliedExtras: JSONObject,
    ): NativeOffloadResult {
        val sessionId = request.sessionId
        val mediaFiles = JSONArray()
        val ts = System.currentTimeMillis() / 1000
        val modelSlug = entry.model.id.replace("/", "_")
        if (outputPath != null) {
            // [deep-audit-1016 FIX-C 2b] T219 read-only mount guard — refuse
            // before touching disk (same contract as tools/FileWriteTool.kt).
            // Exit code 2 matches the neighbouring "cannot resolve --output"
            // refusal: the established shape for "the --output target could not
            // be used", which callers already branch on.
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(outputPath)) {
                return NativeOffloadResult(
                    2,
                    "minis-model-use run: '$outputPath' is inside a read-only mounted folder and cannot be " +
                        "written — toggle writability in Settings → Mount External Folders\n",
                )
            }
            val hostFile = sessionAwareHostFile(outputPath, sessionId)
                ?: return NativeOffloadResult(
                    2,
                    "minis-model-use run: cannot resolve --output '$outputPath'\n",
                )
            val firstMedia = response.mediaAttachments.firstOrNull()
            val outputIsMedia = isImageExt(outputExt) || isAudioExt(outputExt) || isVideoExt(outputExt)
            if (firstMedia != null && outputIsMedia) {
                hostFile.parentFile?.mkdirs()
                hostFile.writeBytes(firstMedia.data)
                logModelUseWrite(outputPath, hostFile, sessionId)
                mediaFiles.put(JSONObject().apply {
                    put("type", firstMedia.type.value)
                    put("mime_type", firstMedia.mimeType)
                    put("path", outputPath)
                    put("size", firstMedia.data.size)
                })
            } else {
                Log.d(
                    "ModelUseImage",
                    "handler text fallback: path=$outputPath textLen=${response.text.length} " +
                        "mediaAttachments=${response.mediaAttachments.size} outputIsMedia=$outputIsMedia",
                )
                hostFile.parentFile?.mkdirs()
                hostFile.writeText(response.text)
            }
        } else if (response.mediaAttachments.isNotEmpty()) {
            val attachDir = sessionAwareHostFile("/var/minis/attachments", sessionId)
            if (attachDir != null) {
                attachDir.mkdirs()
                for ((idx, media) in response.mediaAttachments.withIndex()) {
                    val ext = mimeToExt(media.mimeType)
                    val fileName = "model-use-$modelSlug-$ts-$idx.$ext"
                    val hostFile = File(attachDir, fileName)
                    hostFile.writeBytes(media.data)
                    val responsePath = "/var/minis/attachments/$fileName"
                    logModelUseWrite(responsePath, hostFile, sessionId)
                    mediaFiles.put(JSONObject().apply {
                        put("type", media.type.value)
                        put("mime_type", media.mimeType)
                        put("path", responsePath)
                        put("size", media.data.size)
                    })
                }
            }
        }

        val body = JSONObject().apply {
            put("model", entry.model.id)
            put("text", response.text)
            response.usage?.let { u ->
                put("usage", JSONObject().apply {
                    put("input_tokens", u.inputTokens)
                    put("output_tokens", u.outputTokens)
                })
            }
            if (outputPath != null) put("output_file", outputPath)
            if (mediaFiles.length() > 0) put("media_files", mediaFiles)
        }
        return attachCallFeedback(
            NativeOffloadResult(0, body.toString(2) + "\n"),
            callWarnings, appliedExtras,
        )
    }

    /**
     * [T-android-model-use-session-scoped-write] Resolve a `/var/minis/<sub>/...`
     * Linux path for THIS call, without ever answering with a different
     * session's file.
     *
     * The session-scoped view exists because `PRootKernel.bindMounts` is global
     * and last-writer-wins: `ExecutionCoordinator.buildSessionBindMounts`
     * overwrites `/var/minis/attachments` etc. on every shell build, so the
     * global resolver returns whichever session built a shell most recently — a
     * model-use call from session A could then write into session B's
     * attachments dir while the response reports the abstract
     * `/var/minis/attachments/...` path, which A's shell (mounted to A's dir)
     * can't read. Mirrors iOS da4b6c5d, which routes the write to
     * minisAttachmentsPersistentDir(for: callerSid).
     *
     * [fix/audit3-1015 F-08] Delegates the decision to
     * [sessionAwareHostFile], which fails CLOSED: when the call carries a
     * session id and the path is one of the per-session scopes, a refusal by
     * the traversal guard is final — it is not re-resolved through the global
     * resolver (which would search every session tree, then the rootfs). The
     * global resolver is consulted only when no session view applies at all.
     */
    private fun sessionAwareHostFile(linuxPath: String, sessionId: String?): File? =
        sessionAwareHostFile(context.filesDir, sessionId, linuxPath) { PRootKernel.resolveHostPath(it) }

    /** [T-android-model-use-session-scoped-write] One-line audit of a model-use
     *  write so the response path vs on-disk host path vs session scoping is
     *  greppable when diagnosing "file disappears / wrong session" reports. */
    private fun logModelUseWrite(responsePath: String, hostFile: File, sessionId: String?) {
        Log.i(
            "ModelUseImage",
            "[ModelUseWrite] path=$responsePath hostFile=${hostFile.absolutePath} " +
                "sessionId=$sessionId globalAttachMount=${PRootKernel.bindMounts["/var/minis/attachments"]}",
        )
    }

    private fun isImageExt(ext: String): Boolean =
        ext in setOf("png", "jpg", "jpeg", "webp", "gif", "heic")

    private fun isAudioExt(ext: String): Boolean =
        ext in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac")

    private fun isVideoExt(ext: String): Boolean =
        ext in setOf("mp4", "mov", "webm", "mkv")

    private fun mimeToExt(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("wav") -> "wav"
        mime.contains("mp3") || mime.contains("mpeg") -> "mp3"
        mime.contains("mp4") -> "mp4"
        mime.contains("ogg") -> "ogg"
        else -> mime.substringAfterLast("/", "bin")
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun resolveEntry(idOrName: String, providerFilter: String? = null): ModelEntry? {
        val all = providerRepository.resolvedAgentLoopEntries()

        // Apply --provider filter first. [dv16-A04] The same single matcher
        // as list/search — this pool used to match label/id only, so
        // `--provider OpenAI` (the value list/search print as their JSON
        // "provider" field) resolved to nothing and run failed with
        // "model not found". Restricts the lookup pool so the same model_id
        // under multiple providers can be picked unambiguously.
        val pool = if (providerFilter.isNullOrEmpty()) all else {
            all.filter { entry ->
                val inst = providerRepository.instance(entry.providerInstanceId)
                inst != null && providerMatches(
                    providerFilter, inst.label, inst.id, inst.providerType.displayName,
                )
            }
        }
        if (pool.isEmpty()) return null

        // 0. Qualified `<instance_label>/<model_id>` — split on FIRST '/' only
        //    since model_id itself may contain '/' (e.g. "deepseek-ai/DeepSeek-V4").
        val slash = idOrName.indexOf('/')
        if (slash > 0 && slash < idOrName.length - 1) {
            val labelPart = idOrName.substring(0, slash).lowercase()
            val modelPart = idOrName.substring(slash + 1).lowercase()
            pool.find { entry ->
                val inst = providerRepository.instance(entry.providerInstanceId)
                inst != null &&
                    inst.label.lowercase() == labelPart &&
                    entry.model.id.lowercase() == modelPart
            }?.let { return it }
        }

        // Exact ID
        pool.find { it.model.id == idOrName }?.let { return it }
        // Exact display name
        pool.find { it.model.displayName == idOrName }?.let { return it }
        // Entry UUID
        pool.find { it.id == idOrName }?.let { return it }
        // Case-insensitive prefix / contains
        val q = idOrName.lowercase()
        pool.find { it.model.id.lowercase().startsWith(q) }?.let { return it }
        pool.find { it.model.displayName.lowercase().contains(q) }?.let { return it }
        return null
    }

    private fun entryDict(entry: ModelEntry): JSONObject =
        com.rikkaminis.app.offload.ModelUseManager.entryDict(
            entry,
            providerRepository.instance(entry.providerInstanceId),
        )

    /**
     * Modality filter: matches iOS behavior. Terms are comma-separated and
     * checked against input/output modality lists. All requested terms must
     * be satisfied for an entry to pass.
     *
     * [dv21-A05] The modality lists come from the SAME effective-model resolver
     * as the payload `list` emits and as the config collection — models.dev
     * enrichment first, user overrides on top, canonical direction suffixes.
     * This used to read `entry.model` raw, so `list` advertised `image_input`
     * for a built-in model (its own path enriched) while
     * `list --modality image_input` filtered that same model out (this path did
     * not) — the two halves of one command disagreeing about one entry.
     */
    private fun matchesModality(entry: ModelEntry, filter: String): Boolean {
        val modalities = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)
            .directionalModalities.toSet()
        val required = filter.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (term in required) {
            val ok = when (term) {
                "text", "text_input" -> "text_input" in modalities
                "text_output" -> "text_output" in modalities
                "image", "image_input" -> "image_input" in modalities
                "image_output" -> "image_output" in modalities
                "audio", "audio_input" -> "audio_input" in modalities
                "audio_output" -> "audio_output" in modalities
                "video", "video_input" -> "video_input" in modalities
                "video_output" -> "video_output" in modalities
                "pdf", "pdf_input" -> "pdf_input" in modalities
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    // [B14-fix-r14] Relative --input/--system-file paths are resolved against
    // the guest's cwd — the native_offload protocol forwards the shell's cwd
    // (NativeOffloadRequest.cwd), so a relative path inherits it instead of
    // silently dropping onto the rootfs root. The final path still goes
    // through the same session-aware guard as every other model-use path
    // (canonicalize + prefix check + fail-closed on refusal) — a hostile cwd
    // string gets the same containment as an agent-supplied absolute path.
    // [da1024-I] The join itself lives ONCE in [resolveMaybeRelative] (the
    // same helper `minis-config`/`browser-use` use) — this file no longer keeps
    // a private inline copy that could drift from the other handlers.
    private fun readLinuxPath(linuxPath: String, sessionId: String?, cwd: String?): String? {
        val resolved = resolveMaybeRelative(linuxPath, cwd)
        val hostFile: File = sessionAwareHostFile(resolved, sessionId) ?: return null
        if (!hostFile.exists() || !hostFile.isFile) return null
        return try { hostFile.readText() } catch (_: Throwable) { null }
    }

    /**
     * One parsed message from the input JSON, carrying both text content
     * and any image attachments extracted from a content array. Images
     * collected here are forwarded to [LLMProvider.sendMessage]'s
     * `imageParts` parameter (which providers attach to the last user
     * message), not embedded in [LLMMessage.content] — that field stays
     * a plain string so providers don't see a stringified `[{type:...}]`
     * payload they can't parse.
     */
    private data class ParsedMessage(
        val role: String,
        val content: String,
        val images: List<LLMMessage.ImagePart>,
        /** [GH#67] input_audio blocks extracted from the content array. */
        val audios: List<LLMMessage.AudioPart> = emptyList(),
    )

    /**
     * Parse messages from stdin text or a JSON file. Preserves `system`
     * messages so the caller can merge them into systemPrompt. Image
     * parts (OpenAI-style `{"type":"image_url","image_url":{"url":...}}`
     * blocks) are extracted into [ParsedMessage.images] — previously
     * they were silently dropped during the content-array reduction and
     * the model received text-only input, which is why Jackson saw the
     * `NO_IMAGE` response (T-android-model-use-multimodal).
     *
     * Accepted shapes:
     * - `{"messages":[{"role":"user","content":"..."}]}` — OpenAI-style
     * - `[{"role":"...","content":"..."}, ...]` — array of messages
     * - Plain text → wrapped as a single user message
     */
    private fun parseMessages(text: String, sessionId: String?, cwd: String? = null): List<ParsedMessage> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        try {
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val arr = obj.optJSONArray("messages")
                    ?: return listOf(ParsedMessage("user", trimmed, emptyList()))
                return parseMessageArray(arr, sessionId, cwd)
            }
            if (trimmed.startsWith("[")) {
                return parseMessageArray(JSONArray(trimmed), sessionId, cwd)
            }
        } catch (e: ImageInputError) {
            // Deliberate hard errors from block parsing must NOT be swallowed
            // by the plain-text fallback — otherwise the whole input JSON gets
            // sent to the model as literal text (observed with a malformed
            // input_audio block during GH#67 verification).
            throw e
        } catch (e: AudioInputError) {
            throw e
        } catch (_: Throwable) {
            // Fall through to plain-text handling
        }
        return listOf(ParsedMessage("user", trimmed, emptyList()))
    }

    private fun parseMessageArray(arr: JSONArray, sessionId: String?, cwd: String? = null): List<ParsedMessage> {
        val out = mutableListOf<ParsedMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user").lowercase()
            val textBuf = StringBuilder()
            val imgs = mutableListOf<LLMMessage.ImagePart>()
            val auds = mutableListOf<LLMMessage.AudioPart>()
            when (val c = m.opt("content")) {
                is String -> textBuf.append(c)
                is JSONArray -> {
                    for (j in 0 until c.length()) {
                        val part = c.optJSONObject(j) ?: continue
                        val type = part.optString("type")
                        if (type == "text" || type.isEmpty()) {
                            textBuf.append(part.optString("text", ""))
                        } else if (type == "image_url") {
                            val imgObj = part.optJSONObject("image_url") ?: continue
                            val url = imgObj.optString("url", "").takeIf { it.isNotEmpty() }
                                ?: continue
                            try {
                                imgs.add(resolveImageUrl(url, sessionId, cwd))
                            } catch (e: ImageInputError) {
                                // Don't silently drop — surface to the agent so it
                                // can correct the URL or fall back to text.
                                throw e
                            }
                        } else if (type == "input_audio") {
                            // [GH#67] input_audio was previously skipped with no
                            // trace — the request went out text-only. Parse the
                            // official OpenAI shape; malformed blocks are a hard
                            // input error, not a silent drop. Mirrors iOS
                            // parseOpenAIMessages.
                            val audioObj = part.optJSONObject("input_audio")
                                ?: throw AudioInputError(
                                    "input_audio block is malformed. Expected " +
                                        "{\"type\":\"input_audio\",\"input_audio\":" +
                                        "{\"data\":\"<base64>\",\"format\":\"wav\"}}.",
                                )
                            val b64 = audioObj.optString("data", "")
                            if (b64.isEmpty()) {
                                throw AudioInputError("input_audio.data is missing or empty.")
                            }
                            try {
                                Base64.decode(b64, Base64.DEFAULT)
                            } catch (e: IllegalArgumentException) {
                                throw AudioInputError("input_audio.data is not valid base64: ${e.message}")
                            }
                            val format = audioObj.optString("format", "wav").lowercase()
                            auds.add(LLMMessage.AudioPart(format = format, base64Data = b64))
                        }
                    }
                }
                else -> { /* leave both buffers empty → message skipped below */ }
            }
            if (textBuf.isEmpty() && imgs.isEmpty() && auds.isEmpty()) continue
            out.add(ParsedMessage(role, textBuf.toString(), imgs, auds))
        }
        return out
    }

    /**
     * Resolve an OpenAI-style image_url string to raw bytes + MIME.
     * Mirrors iOS ModelUseOffloadBridge.resolveImageURL (subset that the
     * Android offload path actually needs):
     *
     *  - `data:<mime>;base64,<...>` → inline base64
     *  - `file:///<host-path>` → direct host read
     *  - `/var/minis/<scope>/<path>` or `/<abs/linux/path>` → via
     *    [sessionAwareHostFile] (session-scoped first, global bind mounts for
     *    paths with no session view, fail-closed on a refused scope path)
     *  - a relative path → joined onto the guest cwd with [resolveMaybeRelative]
     *    first, then resolved like an absolute path above (a relative path with
     *    no absolute cwd stays relative and is rejected below)
     *  - `http(s)://` → throw with a hint to download via shell_execute
     *    first (matches iOS — avoids egressing user content)
     *  - anything else (unknown schemes) → throw
     *
     * Throws [ImageInputError] on any unresolvable URL so the CLI exits
     * non-zero with a descriptive message instead of silently feeding
     * the model an image-less request (the prior failure mode).
     */
    private fun resolveImageUrl(url: String, sessionId: String?, cwd: String? = null): LLMMessage.ImagePart {
        // data:<mime>;base64,<base64>
        if (url.startsWith("data:")) {
            val rest = url.substring(5)
            val semi = rest.indexOf(';')
            val comma = rest.indexOf(',')
            if (semi <= 0 || comma <= semi) {
                throw ImageInputError("Malformed data: URL — expected `data:<mime>;base64,<...>`")
            }
            val mime = rest.substring(0, semi)
            val b64 = rest.substring(comma + 1)
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw ImageInputError("data: URL base64 payload failed to decode: ${e.message}")
            }
            return LLMMessage.ImagePart(data = bytes, mimeType = mime)
        }

        // http(s):// — surface the iOS-parity message
        if (url.startsWith("http://") || url.startsWith("https://")) {
            throw ImageInputError(
                "http(s):// image URLs are not supported by minis-model-use. " +
                    "Download first with `shell_execute` (curl/wget) into " +
                    "/var/minis/workspace/, then reference the local path."
            )
        }

        // file:///abs/path → strip prefix, treat as host path; fall back
        // to resolveHostPath so bind-mounted /var/minis/* still works
        // when callers write file:///var/minis/... .
        val linuxPath = when {
            url.startsWith("file://") -> url.removePrefix("file://")
            url.startsWith("/") -> url
            else -> resolveMaybeRelative(url, cwd).takeIf { it.startsWith("/") }
                ?: throw ImageInputError(
                    "Unsupported image_url '$url'. Use a data: URL, file:///host/path, " +
                        "/var/minis/<scope>/<path>, an absolute Linux path, or a relative path " +
                        "resolvable against the shell cwd."
                )
        }
        val hostFile: File = sessionAwareHostFile(linuxPath, sessionId)
            ?: throw ImageInputError(
                "Image file not found at '$url'. Host-absolute paths outside " +
                    "the mounted scopes (/var/minis/*) are not readable from " +
                    "minis-model-use.",
            )
        if (!hostFile.exists() || !hostFile.isFile) {
            throw ImageInputError("Image file not found at '$url' (resolved to ${hostFile.absolutePath}).")
        }
        val bytes = try {
            hostFile.readBytes()
        } catch (e: Throwable) {
            throw ImageInputError("Image file at '$url' is unreadable: ${e.message}")
        }
        return LLMMessage.ImagePart(
            data = bytes,
            mimeType = imageMimeForExtension(linuxPath),
            linuxPath = linuxPath,
        )
    }

    private fun imageMimeForExtension(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }

    private class ImageInputError(message: String) : RuntimeException(message)

    /** [GH#67] Malformed/undecodable input_audio block — hard input error. */
    private class AudioInputError(message: String) : RuntimeException(message)

    private fun textMessage(
        role: String,
        content: String,
        audios: List<LLMMessage.AudioPart> = emptyList(),
    ): LLMMessage {
        val r = when (role.lowercase()) {
            "assistant" -> LLMMessage.Role.ASSISTANT
            else -> LLMMessage.Role.USER
        }
        return LLMMessage(role = r, content = content, audioParts = audios)
    }

    companion object {

        /**
         * [fix/sweep1013-security R1] Pure join for session-scoped host files,
         * kept in the companion so the traversal guard is host-testable
         * ([SessionScopedPathGuardTest]).
         *
         * Normalizes dot-segments and refuses `..` traversal / symlink
         * escapes via [PRootKernel.safeResolveWithin] — the same guard as
         * every other `File(base, userPath)` join. `File(base, rest)` alone
         * (the pre-fix code) let a guest-supplied `--output` like
         * `/var/minis/workspace/../../../debug_server_token` escape the
         * session tree and land in host-private app storage (shared_prefs,
         * debug token, logs, databases). Fail-closed: returns null for any
         * path that isn't a session-scoped `/var/minis/<sub>` path or that
         * would resolve outside the session tree.
         */
        internal fun sessionScopedJoin(filesDir: File, sessionId: String, linuxPath: String): File? {
            val m = SESSION_SCOPED_PATH.find(linuxPath) ?: return null
            val base = File(filesDir, "minis-sessions/$sessionId/${m.groupValues[1]}")
            return PRootKernel.safeResolveWithin(base, m.groupValues[2].removePrefix("/"))
        }

        /** `/var/minis/<per-session scope>[/...]` — the same four scopes as
         *  GuestPathMapper.PER_SESSION_SUBDIRS (attachments/offloads/workspace/browser). */
        private val SESSION_SCOPED_PATH = Regex("^/var/minis/(attachments|offloads|workspace|browser)(/.*)?$")


        /**
         * Pure classifier behind [sessionAwareHostFile] — kept here (like
         * [sessionScopedJoin]) so the three-way decision is host-testable
         * without a Context. [filesDir] is the app's `filesDir`.
         *
         * [deep-audit-1016 FIX-C 2a] Two additions, both on the *raw* string
         * before any scope match:
         *
         *  1. A literal `..` segment is refused outright
         *     ([hasDotDotSegment]). For a guest-supplied write target a `..` is
         *     an escape attempt, not a request for the normalized destination —
         *     FIX-08's decision is that a refusal here is FINAL (never re-asked
         *     of the global resolver), and silently re-targeting
         *     `workspace/../browser/c` to `browser/c` would be exactly the
         *     "normalize an attack into a permission" move this guard exists to
         *     prevent.
         *  2. The scope match now runs on the normalized path. Pre-fix the raw
         *     match meant `/var/minis/./workspace/out.txt` (or
         *     `//var/minis/workspace/out.txt`) failed the regex, fell through to
         *     `NotSessionScoped`, and the global resolver wrote the file into
         *     the ROOTFS placeholder — a ghost the session's own shell cannot
         *     see, shared with every other session.
         */
        internal fun classifySessionPath(filesDir: File, sessionId: String?, linuxPath: String): SessionScopedPathDecision {
            if (sessionId == null) return SessionScopedPathDecision.NotSessionScoped
            if (hasDotDotSegment(linuxPath)) return SessionScopedPathDecision.Rejected
            val normalized = normalizeLinuxPath(linuxPath)
                ?: return SessionScopedPathDecision.Rejected
            if (SESSION_SCOPED_PATH.find(normalized) == null) return SessionScopedPathDecision.NotSessionScoped
            val joined = sessionScopedJoin(filesDir, sessionId, normalized)
                ?: return SessionScopedPathDecision.Rejected
            return SessionScopedPathDecision.Resolved(joined)
        }

        /**
         * [fix/audit3-1015 F-08] Resolve a Linux path to a host file for a call
         * that may carry a session id, with the global resolver injected so the
         * "was it consulted?" question is testable (a [SessionScopedPathDecision.Rejected]
         * path must NOT consult it).
         */
        internal fun sessionAwareHostFile(
            filesDir: File,
            sessionId: String?,
            linuxPath: String,
            globalResolve: (String) -> File?,
        ): File? = when (val d = classifySessionPath(filesDir, sessionId, linuxPath)) {
            is SessionScopedPathDecision.Resolved -> d.file
            SessionScopedPathDecision.NotSessionScoped -> globalResolve(linuxPath)
            SessionScopedPathDecision.Rejected -> null
        }

        private const val TAG = "ModelUseOffload"
        private const val NO_MODELS_HINT =
            "No models available. Go to Settings > Model Groups to add models that the agent can use."

        /** Appended to non-empty list/search results so the agent knows how to invoke a model.
         *  Three forms are supported by `run --model`:
         *    1. Plain `model_id` (e.g. `claude-sonnet-4-6`) — works when unique.
         *    2. `instance_label/model_id` (e.g. `deepseek/deepseek-v4-flash`) — required when
         *       the same model_id is configured under multiple provider instances.
         *    3. `--model <model_id> --provider <instance_label>` — equivalent to (2),
         *       preferred when the model_id itself contains slashes.
         *  `entry_id` (UUID) also works but is opaque; prefer the human-readable forms.
         */
        private const val USAGE_HINT =
            "To invoke a model, pass `--model <model_id>` to `minis-model-use run`. " +
            "If multiple providers expose the same `model_id`, disambiguate either with " +
            "`--model <instance_label>/<model_id>` (e.g. `--model deepseek/deepseek-v4-flash`) " +
            "or with `--model <model_id> --provider <instance_label>` " +
            "(e.g. `--model deepseek-v4-flash --provider deepseek`). " +
            "The opaque `entry_id` (UUID) is also accepted. " +
            // [T-android-model-use-image-passthrough GH#62] Progressive-disclosure
            // breadcrumbs: surface capability keywords up front so the model knows
            // what's possible and which model's per-entry `hint` to read for the
            // exact param shape.
            "Capabilities by modality (run without --model, or inspect a model's `hint` field, " +
            "for the exact JSON shape): text generation, image generation, image-to-image, " +
            "image editing, audio (TTS/STT), embeddings. " +
            "Topics/params you can pass per call: messages, generation_config (size/quality/n/" +
            "aspect_ratio/image_size), image_endpoint, and — for OpenAI-compatible image models — " +
            "PASSTHROUGH via `extra_body`, `extra_headers`, `endpoint_path`, or any unknown " +
            "top-level field (forwarded verbatim; enables provider-specific params like Seedream " +
            "image-to-image). Read the target model's `hint` for concrete examples."

        private const val HELP = """minis-model-use — list, search, and invoke LLM models

Usage:
  minis-model-use list [--provider <name>] [--modality <mod>]
  minis-model-use search <query> [--provider <name>] [--modality <mod>]
  minis-model-use run --model <id_or_name> [prompt] [--provider <label_or_id>]
                      [--input <path>] [--output <path>]
                      [--system <text>] [--system-file <path>]
                      [--max-tokens N] [--temperature F]

  NOTE: stdin is NOT read — the native_offload protocol has no stdin
  channel. Pass the prompt as a positional argument (run --model <id> 'text')
  or write it to a file and pass --input <path>.

Run model selection:
  --model accepts: model_id, display name, entry_id (UUID), OR the qualified
  form `<instance_label>/<model_id>` (e.g. `deepseek/deepseek-v4-flash`) to
  disambiguate when the same model_id is configured under multiple providers.
  Equivalently, pass `--model <model_id> --provider <instance_label>`.

Provider matching (--provider on list/search/run):
  --provider matches (case-insensitive, exact) the instance label, its id,
  or the provider type name (e.g. `OpenAI`, `Anthropic`) — the values
  `list`/`search` print as `instance_label` / `provider_type` in their JSON.
  A value that matches none of the three faces filters to zero rows (run
  reports "not found") rather than falling back to unfiltered results.

Input format (OpenAI Chat Completions JSON — the ONLY input format):
  Standard fields (messages, system, temperature, max_tokens,
  generation_config) are AUTOMATICALLY CONVERTED to whatever the selected
  model's provider expects on the wire. Do NOT hand-write provider-native
  bodies as the primary input.

  AUDIO INPUT (audio_input models, e.g. gpt-4o-audio / Qwen-Audio):
    Add an input_audio content block to a user message (official OpenAI
    shape; forwarded verbatim on both the Chat Completions and Responses
    API paths). The model MUST declare the audio_input modality (see
    `list --modality audio`), otherwise the call fails with an explicit
    modality error.
    {"messages":[{"role":"user","content":[
       {"type":"text","text":"Transcribe this audio"},
       {"type":"input_audio","input_audio":{"data":"<base64>",
        "format":"wav"}}]}]}
    NOTE: dedicated STT transcription endpoints (POST
    /v1/audio/transcriptions on speaches / faster-whisper / whisper.cpp
    servers) require multipart/form-data uploads, which this tool does
    NOT support yet — passthrough mode sends JSON bodies only (known
    limitation). For speech-to-text today, use a chat-protocol audio
    model as above.

  Standard-mode extras (OpenAI-compatible providers, all endpoints):
    extra_body     object — merged VERBATIM into the final request body
                   (your keys win; `model` stays locked). Use for provider
                   fields our schema doesn't model, e.g. OpenRouter web
                   search: {"extra_body":{"plugins":[{"id":"web"}]}} — these
                   are NOT converted.
    extra_headers  string map — added to request headers; same-name REPLACES
                   the default (incl. auth headers).
    endpoint       "/abs/path" starting with "/" replaces the ENTIRE path on
                   the provider's own base URL host (credentials never leave
                   the host); body is still auto-converted, response parsed.

Passthrough mode (raw escape hatch; response is NOT parsed):
  For endpoints/formats our schema doesn't model (video gen, TTS,
  provider-native APIs), add a top-level "passthrough" object:
    {"passthrough": {
       "endpoint": "/v1/audio/speech",  // "/abs/path" replaces the whole
                                         // path on the provider base host;
                                         // omit = chat path
       "method":  "POST",               // default POST
       "headers": {"X-Custom": "1"},    // same-name REPLACES defaults
       "body":    {"input": "hi", "voice": "alloy"},
       "body_mode": "replace"           // replace = body IS the whole request
                                        // body; merge (default) = layered
                                        // over the converted OpenAI baseline,
                                        // model locked
    }}
  Passthrough values are sent VERBATIM (no conversion). The response is
  returned RAW: full bytes to --output (required for binary), or inline text
  up to 64KB; the result reports http_status, content_type, bytes and the
  fully-assembled endpoint_url. OpenAI-compatible providers only for now.

Image generation fields (only for image_output models):
  Pass image params either at the top level OR under "generation_config".
  Top level matches OpenAI /v1/images/generations and takes precedence.

  OpenAI-style (DALL-E / gpt-image-1 etc.):
    n         integer, number of images (default 1)
    size      "1024x1024" | "1792x1024" | "1024x1792" | etc.
    quality   "standard" | "hd"
    prompt    string (overrides last user message)

  Gemini-style (Imagen / gemini-2.5-flash-image etc.) — under generation_config:
    aspect_ratio       "1:1" | "16:9" | "9:16" | "4:3" | "3:4"
    image_size         "512px" | "1K" | "2K" | "4K"
    number_of_images   1-4
    person_generation  "DONT_ALLOW" | "ALLOW_ADULT"
  Unknown providers silently ignore unsupported fields.

  Example (OpenAI):
    {"prompt":"a red panda astronaut","size":"1792x1024","quality":"hd","n":1}
  Example (Gemini):
    {"messages":[{"role":"user","content":"a red panda astronaut"}],
     "generation_config":{"aspect_ratio":"16:9","image_size":"2K"}}

Examples:
  minis-model-use list
  minis-model-use list --modality image_input
  minis-model-use search gemini
  minis-model-use run --model claude-sonnet-4-6 --input /var/minis/workspace/prompt.json
  minis-model-use run --model deepseek/deepseek-v4-flash --input msgs.json   # relative: resolved against the shell's cwd
  minis-model-use run --model deepseek-v4-flash --provider deepseek --input msgs.json   # equivalent
  minis-model-use run --model gpt-4o 'What is 2+2?'
  minis-model-use run --model gemini-2.5-flash --system 'You are a poet' \
                      --input msgs.json --output /var/minis/workspace/out.txt
"""
    }
}

/**
 * [fix/audit3-1015 F-08] What a path means for THIS call. The three
 * cases are the fix; collapsing any two of them reintroduces the bug.
 *
 *  - [Resolved]         session-scoped shape, traversal guard passed → use it.
 *  - [NotSessionScoped] no session view applies (no sessionId, or the path
 *                       is not one of the per-session scopes, e.g. `/tmp/x`
 *                       or `/var/minis/shared/...`) → the global resolver
 *                       is the honest answer and stays in use.
 *  - [Rejected]         session-scoped SHAPE, refused by the guard (a `..`
 *                       climb above the scope base, or a symlink pointing
 *                       out of the session tree) → there is no legitimate
 *                       second opinion.
 *
 * Why [Rejected] must not fall back: the global resolver searches every
 * `minis-sessions/<id>/<scope>` tree for a file that exists and then the
 * rootfs. So a path session A was refused could come back as session B's
 * same-named file (a cross-session read the caller never asked for), or
 * as a rootfs placeholder that the guest's mount makes invisible (a write
 * that silently goes nowhere). Either way the containment decision the
 * guard just made is overruled by a resolver that knows less.
 */
internal sealed class SessionScopedPathDecision {
    class Resolved(val file: File) : SessionScopedPathDecision()
    object NotSessionScoped : SessionScopedPathDecision()
    object Rejected : SessionScopedPathDecision()
}

/**
 * [dv16-A04] The single `--provider` matcher: one decision shared by `list`,
 * `search` and `run` (resolveEntry). The three subcommands used to answer
 * `--provider` differently — list matched model.provider/label as a
 * case-sensitive substring, search never read the option despite its help
 * line, and run resolved label/id exactly — so an agent reading `list`'s
 * JSON and replaying its "provider" field into `run` could hit
 * "model not found", and `search --provider x` silently returned other
 * providers' models.
 *
 * Semantics: case-insensitive EXACT match (trim + lowercase) against the
 * provider instance's label, its id, or the provider type's display name.
 * Those are the three faces a caller can obtain verbatim: "instance_label" /
 * "provider_type" from the `list`/`search` output, the instance UUID from
 * `minis-config get models`, and the type name from the settings UI. An
 * OpenAI-type instance's display name is "OpenAI", the same string the
 * output prints as its "provider" field — so the read-then-replay workflow
 * now round-trips. `list`'s old substring match was the historical
 * coincidence, not a contract; exact is the explainable answer and the same
 * rule `run` has always used (extended to the type name).
 *
 * Top-level (not a member) so it is JVM-testable with zero Android deps.
 */
internal fun providerMatches(query: String, label: String, id: String, typeName: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return false
    return label.trim().lowercase() == q ||
        id.trim().lowercase() == q ||
        typeName.trim().lowercase() == q
}
