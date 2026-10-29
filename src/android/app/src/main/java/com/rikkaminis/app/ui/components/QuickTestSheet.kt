package com.rikkaminis.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMMediaAttachment
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.normalizeModalityName
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.util.ImageDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope

private const val TAG = "QuickTest"

/**
 * [audit3-F-13] Longest-edge ceiling for an image reply preview, in pixels.
 *
 * Derived from the layout below (`fillMaxWidth().heightIn(max = 220.dp)` inside
 * a bottom sheet, `ContentScale.Fit`): on the widest phones that is ~1100 px of
 * row width and ~600 px of height at 2.75x density, so 1024 keeps the preview
 * sharp without a resize pass, while a 4K generation (4096x4096 = 64 MB
 * ARGB_8888, native memory the GC cannot see) is decoded at ~4 MB instead.
 */
private const val QUICKTEST_MAX_EDGE_PX = 1024

/**
 * [T-android-model-quick-test] A lightweight half-sheet that fires
 * modality-matched smoke tests against one model so the user can confirm
 * "this model + credential actually works" without leaving the provider
 * screen. Mirrors iOS `ModelQuickTestSheet`.
 *
 * Up to 3 applicable tests run CONCURRENTLY, each with its own
 * running / success / failure state laid out as a stacked card.
 */

/** One testable capability. Priority order (most distinctive first) is
 *  imageGen > speechOut > transcription > text. */
enum class QuickTestKind {
    IMAGE_GEN,
    SPEECH_OUT,
    TRANSCRIPTION,
    TEXT;

    val titleRes: Int
        get() = when (this) {
            TEXT -> R.string.quicktest_kind_text
            IMAGE_GEN -> R.string.quicktest_kind_image
            SPEECH_OUT -> R.string.quicktest_kind_speech
            TRANSCRIPTION -> R.string.quicktest_kind_transcription
        }

    val icon: ImageVector
        get() = when (this) {
            TEXT -> Icons.Outlined.TextFields
            IMAGE_GEN -> Icons.Outlined.Image
            SPEECH_OUT -> Icons.Outlined.GraphicEq
            TRANSCRIPTION -> Icons.Outlined.Mic
        }
}

/** Result state of a single test run. */
sealed class QuickTestState {
    object Running : QuickTestState()
    data class TextReply(val text: String) : QuickTestState()
    data class ImageReply(val data: ByteArray) : QuickTestState()
    /** [T-android-provider-voice] Synthesized audio bytes, playable in place. */
    data class AudioReply(val data: ByteArray) : QuickTestState()
    data class Failure(val message: String) : QuickTestState()
}

/** Mutable per-run holder observed by its card. */
private class QuickTestRun(val kind: QuickTestKind) {
    var state by mutableStateOf<QuickTestState>(QuickTestState.Running)
    var elapsedMs by mutableStateOf(0L)
}

/**
 * Top-3 most distinctive applicable test kinds for [entry]. A model is
 * testable for a kind when its effective modality exposes that capability.
 * Text is always included as a baseline if the model has text output;
 * if nothing matches we fall back to text so there is always one test.
 */
private fun applicableKinds(entry: ModelEntry): List<QuickTestKind> {
    // [dv22/B-03] Capability source is the effective model (models.dev enrichment
    // under the user's overrides), not the raw `entry.model`: the built-in
    // catalog declares no modality fields, so every built-in looked text-only and
    // the image/speech/transcription quick tests never showed up.
    val model = ModelsDevApi.effectiveModel(entry)
    val outputs = (model.outputModalities ?: emptyList()).map { it.normalizeModalityName() }
    val inputs = (model.inputModalities ?: emptyList()).map { it.normalizeModalityName() }
    val kinds = mutableListOf<QuickTestKind>()
    if ("image" in outputs) kinds.add(QuickTestKind.IMAGE_GEN)
    if ("audio" in outputs) kinds.add(QuickTestKind.SPEECH_OUT)
    if ("audio" in inputs) kinds.add(QuickTestKind.TRANSCRIPTION)
    // outputModalities null/empty ⇒ text-out (documented convention), so text
    // is applicable whenever the list is empty OR explicitly contains text.
    if (outputs.isEmpty() || "text" in outputs) kinds.add(QuickTestKind.TEXT)
    if (kinds.isEmpty()) kinds.add(QuickTestKind.TEXT)
    return kinds.take(3)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTestSheet(
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs = remember(entry.id) {
        mutableStateListOf<QuickTestRun>().apply {
            applicableKinds(entry).forEach { add(QuickTestRun(it)) }
        }
    }
    val isRunning by remember { derivedStateOf { runs.any { it.state is QuickTestState.Running } } }

    // Kick off all tests on first show (once per entry).
    androidx.compose.runtime.LaunchedEffect(entry.id) {
        runAll(scope, context, entry, providerRepository, runs)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Toolbar row: title + Run again / Done.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.quicktest_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { runAll(scope, context, entry, providerRepository, runs) },
                    enabled = !isRunning,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.quicktest_run_again),
                    )
                }
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.quicktest_done))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Header: icon + name + id.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        entry.model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        entry.model.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            runs.forEach { run ->
                TestCard(run)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun TestCard(run: QuickTestRun) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                run.kind.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(run.kind.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(run)
        }
        Spacer(Modifier.height(8.dp))
        TestContent(run)
    }
}

@Composable
private fun StatusBadge(run: QuickTestRun) {
    when (val s = run.state) {
        is QuickTestState.Running -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        is QuickTestState.Failure -> Icon(
            Icons.Filled.Cancel,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            if (run.elapsedMs > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    String.format("%.1fs", run.elapsedMs / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TestContent(run: QuickTestRun) {
    when (val s = run.state) {
        is QuickTestState.Running -> Text(
            stringResource(R.string.quicktest_testing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is QuickTestState.TextReply -> Text(
            s.text,
            style = MaterialTheme.typography.bodyMedium,
        )
        is QuickTestState.ImageReply -> {
            // [audit3-F-13] Bounded decode. Generation replies are 2K/4K PNGs,
            // and this `remember {}` runs on the Compose thread: the bare
            // `decodeByteArray` it replaces allocated the full-resolution
            // bitmap (64 MB for 4096x4096) before the 220.dp preview could
            // shrink it.
            val bmp = remember(s.data) {
                ImageDecoding.decodeBytesBounded(s.data, QUICKTEST_MAX_EDGE_PX)?.bitmap
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(8.dp),
                        ),
                )
            } else {
                Text(
                    "Received ${s.data.size} bytes (couldn't decode preview)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is QuickTestState.AudioReply -> AudioReplyContent(s.data)
        is QuickTestState.Failure -> Text(
            s.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [T-android-provider-voice] Playable synthesized-audio result. Writes the
 * bytes to a cache file and drives a MediaPlayer; auto-plays once on first
 * appearance (mirrors iOS Quick Test playing the returned clip).
 */
@Composable
private fun AudioReplyContent(data: ByteArray) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    // [fix/audit0917-b8] Gate the play button on the clip actually being on
    // disk. The write happens in the LaunchedEffect below (IO); a tap before it
    // finished called setDataSource on a missing file, which fails into the
    // onFailure log — i.e. the button silently did nothing, with no way for the
    // user to tell a slow write from a dead control.
    var isReady by remember(data) { mutableStateOf(false) }
    // [R4-V F-12] A failed cache write used to be swallowed: isReady flipped to
    // true anyway and play() then failed silently, so the control looked live
    // and did nothing. The failure is now a visible state.
    var writeFailed by remember(data) { mutableStateOf(false) }
    val player = remember { android.media.MediaPlayer() }
    // [R4-V F-12] `ByteArray.hashCode()` is IDENTITY (Kotlin arrays do not
    // override it), so the cache name was different for identical bytes on
    // every composition and identical for none of them — a name that carries
    // no information about the clip. Content-derived (size + content hash):
    // the same clip reuses its file, a different clip never collides.
    val file = remember(data) {
        java.io.File(context.cacheDir, quickTestClipFileName(data))
    }

    fun play() {
        runCatching {
            player.reset()
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { isPlaying = false }
            player.prepare()
            player.start()
            isPlaying = true
        }.onFailure {
            AppLogger.warning(TAG, "[QuickTest] audio playback failed: ${it.message}")
            isPlaying = false
        }
    }

    // [fix/audit-b17 / T10-L9] The clip used to be written inside remember{},
    // i.e. on the main thread during composition. Write it on IO first, then
    // auto-play — same order as before, so setDataSource never sees a
    // half-written file.
    androidx.compose.runtime.LaunchedEffect(file) {
        // [R4-V F-12] Only a write that really landed enables the control.
        val written = withContext(Dispatchers.IO) {
            runCatching { file.writeBytes(data) }.isSuccess && file.length() == data.size.toLong()
        }
        if (!written) {
            AppLogger.warning(TAG, "[QuickTest] audio clip cache write failed: ${file.name}")
            writeFailed = true
            isReady = false
            return@LaunchedEffect
        }
        writeFailed = false
        isReady = true
        play()
    }
    // [da1031-D ISSUE-08] The clip's lifetime is bound to the file key itself,
    // not to the composable: a re-test replaces `file` (remember(data)) and
    // this keyed effect deletes the clip that just went out of use. The old
    // Unit-keyed effect only ever saw the LAST file, so every re-test leaked
    // its predecessor in cacheDir. Registration order keeps the teardown order
    // of the original block: reverse-dispose releases the player first, then
    // deletes the clip.
    androidx.compose.runtime.DisposableEffect(file) {
        onDispose {
            runCatching { file.delete() }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        // [fix/audit0917-b8] Disabled until the clip has been written (see
        // isReady above) — a tap during the write used to be a silent no-op.
        IconButton(onClick = { if (!isPlaying && isReady) play() }, enabled = isReady) {
            Icon(
                if (isPlaying) Icons.Outlined.GraphicEq else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.quicktest_audio_play else R.string.quicktest_audio_replay,
                ),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (writeFailed) {
            // Reuses the existing clip-failure copy rather than adding a key:
            // the strings files are outside this branch's file set, and "the
            // local test clip could not be produced" is exactly what happened
            // from the user's side.
            Text(
                stringResource(R.string.quicktest_clip_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "%.1f KB".format(data.size / 1024.0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * [R4-V F-12] Cache name for a Quick Test audio clip, derived from its CONTENT.
 *
 * `ByteArray.hashCode()` is `Object.hashCode()` — identity, not content — so the
 * old name was stable within one composition and meaningless across them: two
 * identical clips never shared a file and the name told a reader nothing. Size
 * plus [contentHashCode] is stable, cheap, and never collides on equal bytes.
 * (Cache dir only; this is a name, not a security boundary.)
 */
internal fun quickTestClipFileName(data: ByteArray): String {
    val hash = data.contentHashCode().toUInt().toString(16)
    return "quicktest-audio-${data.size}-$hash.bin"
}

/** Reset every run to Running and fire each test concurrently. */
private fun runAll(
    scope: CoroutineScope,
    context: android.content.Context,
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    runs: SnapshotStateList<QuickTestRun>,
) {
    runs.forEach { it.state = QuickTestState.Running; it.elapsedMs = 0 }
    runs.forEach { run ->
        AppLogger.info(TAG, "[QuickTest] start model=${entry.model.id} kind=${run.kind}")
        scope.launch {
            val start = System.currentTimeMillis()
            val result = performTest(run.kind, entry, providerRepository, context)
            run.elapsedMs = System.currentTimeMillis() - start
            run.state = result
            when (result) {
                is QuickTestState.Failure ->
                    AppLogger.warning(TAG, "[QuickTest] FAIL model=${entry.model.id} kind=${run.kind}: ${result.message}")
                else ->
                    AppLogger.info(TAG, "[QuickTest] OK model=${entry.model.id} kind=${run.kind} in ${run.elapsedMs}ms")
            }
        }
    }
}

/** Run one smoke test against a real provider (no mocks). */
private suspend fun performTest(
    kind: QuickTestKind,
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    context: android.content.Context,
): QuickTestState = withContext(Dispatchers.IO) {
    fun failure(msg: String) = QuickTestState.Failure(msg)

    // [dv24/B-01] The capability source is the EFFECTIVE model, exactly as
    // `applicableKinds` above already decides which cards to show. Every
    // execution call below used to pass the raw `entry.model`, which for a
    // built-in carries no modality declaration at all: the card appeared
    // (because the enrichment happened for the *list*) and then the request
    // that left this process described a text-only model. Across the
    // `:modelservice` boundary `ModelRequestJson.encodeModel` OMITS a null
    // `outputModalities`, and that process never calls `ModelsDevApi.init`, so
    // the worker could not enrich it back — Gemini image routing then skipped
    // `responseModalities: ["TEXT","IMAGE"]` ("No image returned") and the
    // OpenAI-compatible providers were routed down `ChatPath`
    // (`/v1/chat/completions`) instead of `/v1/images/generations` (400/404).
    // Resolved ONCE here: the three call sites are one decision.
    val effectiveModel = ModelsDevApi.effectiveModel(entry)

    val instance = providerRepository.instance(entry.providerInstanceId)
        ?: return@withContext failure("Provider instance not found.")
    val apiKey = providerRepository.loadApiKey(instance.id)
        ?: return@withContext failure("No API key configured for this provider.")

    // [T-android-provider-voice] Speech tests route through the VoiceProvider
    // stack (vendor adapters + endpoints), NOT the chat provider — mirrors iOS
    // ModelQuickTestSheet .speechOut / .transcription.
    if (kind == QuickTestKind.SPEECH_OUT || kind == QuickTestKind.TRANSCRIPTION) {
        val voice = com.rikkaminis.app.provider.voice.VoiceProviderFactory.make(instance, apiKey)
            ?: return@withContext failure(context.getString(R.string.quicktest_voice_unsupported))
        return@withContext when (kind) {
            QuickTestKind.SPEECH_OUT -> {
                if (!voice.supportsVoiceOutput) {
                    failure(context.getString(R.string.quicktest_voice_unsupported))
                } else {
                    runCatching {
                        // The selected entry id is the model AND the voice id
                        // (template voices carry the voice id as the model id) —
                        // iOS 0a52bdbf: pass entry.model.id for both so the test
                        // speaks in THAT voice, not the vendor default.
                        val data = voice.synthesize(
                            com.rikkaminis.app.provider.voice.VoiceOutputRequest(
                                input = "Hi! This is RikkaMinis testing text to speech.",
                                model = entry.model.id,
                                voice = entry.model.id,
                            ),
                        )
                        if (data.isEmpty()) {
                            failure(context.getString(R.string.quicktest_no_audio))
                        } else {
                            QuickTestState.AudioReply(data)
                        }
                    }.getOrElse { failure(it.message ?: "Speech request failed.") }
                }
            }
            else -> { // TRANSCRIPTION
                if (!voice.supportsVoiceInput) {
                    failure(context.getString(R.string.quicktest_voice_unsupported))
                } else {
                    val spoken = "Hello from RikkaMinis, testing speech to text."
                    val clip = synthesizeTestClip(context, spoken)
                        ?: return@withContext failure(context.getString(R.string.quicktest_clip_failed))
                    runCatching {
                        val resp = voice.transcribe(
                            com.rikkaminis.app.provider.voice.VoiceInputRequest(
                                audioData = clip,
                                model = entry.baseModel.id,
                                language = "en",
                                // [dv24/B-01] enriched model, not the raw entry
                                resolvedModel = effectiveModel,
                            ),
                        )
                        val heard = resp.text.trim().ifEmpty { "(empty transcription)" }
                        QuickTestState.TextReply(
                            context.getString(R.string.quicktest_transcript_result, spoken, heard),
                        )
                    }.getOrElse { failure(it.message ?: "Transcription request failed.") }
                }
            }
        }
    }

    // TF-D: the app process never creates a provider for the quick test either.
    // TEXT and IMAGE_GEN route through the :modelservice worker via the
    // ProviderExecutionGateway — the worker reads the API key and builds/runs
    // the provider, so its native heap dies with the process.
    when (kind) {
        QuickTestKind.TEXT -> {
            runCatching {
                when (val r = com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.send(
                    context = context,
                    instance = instance,
                    // [dv24/B-01] enriched model, not the raw entry
                    model = effectiveModel,
                    messages = listOf(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = "Hi! I'm setting you up in RikkaMinis. Say hello back in one short, friendly sentence.",
                        ),
                    ),
                    systemPrompt = null,
                    maxTokens = 128,
                    temperature = null,
                )) {
                    is com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.SendResult.Success -> {
                        val text = r.response.text.trim()
                        QuickTestState.TextReply(
                            text.ifEmpty { context.getString(R.string.quicktest_empty_reply) },
                        )
                    }
                    is com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.SendResult.RemoteFailure ->
                        failure("${r.code}: ${r.message}")
                    is com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.SendResult.Unavailable ->
                        failure(r.reason)
                }
            }.getOrElse { failure(it.message ?: "Request failed.") }
        }

        QuickTestKind.IMAGE_GEN -> {
            // [da1031-D ISSUE-08] Eligibility is ModelUseProtocol's call now —
            // the same predicate the worker applies inside decideImageRoute.
            // The old hardcode (`providerType == openAI`) refused an OpenRouter
            // / xAI image model on this card even though the worker routes it
            // fine, so the card lied about the provider's capability.
            if (!com.rikkaminis.app.sandbox.offload.ModelUseProtocol.supportsImageApiRoute(
                    providerType = instance.providerType,
                    credentialType = instance.credentialType,
                    isOpenAiCompatibleProvider =
                        com.rikkaminis.app.sandbox.offload.ModelUseProtocol
                            .isOpenAiCompatibleProviderType(instance.providerType),
                )
            ) {
                return@withContext failure(context.getString(R.string.quicktest_image_unsupported))
            }
            runCatching {
                when (val r = com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.generateImage(
                    context = context,
                    instance = instance,
                    // [dv24/B-01] enriched model, not the raw entry — the
                    // worker's ARG modality / route decision reads it
                    model = effectiveModel,
                    prompt = "A friendly cute mascot logo for an app called RikkaMinis, minimalist, centered, soft colors",
                    n = 1,
                    size = "1024x1024",
                    quality = null,
                )) {
                    is com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.SendResult.Success -> {
                        // [da1031-D ISSUE-08] The worker's auto-probe answer rides
                        // back on the raw result; the model-use offload path
                        // persists it (ModelUseOffloadHandler), this card used to
                        // drop it — so every image test re-probed from scratch.
                        // Same rule as there: write only a non-empty, known mode,
                        // ignore anything else; a cache write must never turn a
                        // successful test into a failure.
                        runCatching {
                            org.json.JSONObject(r.rawJson)
                                .optString("image_endpoint_resolved", "")
                                .takeIf { it.isNotEmpty() }
                                ?.let { cached ->
                                    com.rikkaminis.app.data.model.ImageEndpointMode.values()
                                        .firstOrNull { it.name == cached }
                                        ?.let { mode ->
                                            providerRepository.setImageEndpointResolved(instance.id, mode)
                                        }
                                }
                        }
                        val img = r.response.mediaAttachments.firstOrNull {
                            it.type == LLMMediaAttachment.MediaType.IMAGE
                        } ?: return@runCatching failure(context.getString(R.string.quicktest_no_image))
                        QuickTestState.ImageReply(img.data)
                    }
                    is com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.SendResult.RemoteFailure ->
                        failure("${r.code}: ${r.message}")
                    is com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.SendResult.Unavailable ->
                        failure(r.reason)
                }
            }.getOrElse { failure(it.message ?: "Image request failed.") }
        }

        // Handled above.
        QuickTestKind.SPEECH_OUT, QuickTestKind.TRANSCRIPTION ->
            failure(context.getString(R.string.quicktest_speech_unavailable))
    }
}

/**
 * [T-android-provider-voice] Synthesize a short English test clip as a WAV via
 * the system TextToSpeech engine (no bundled audio asset). Android equivalent
 * of iOS synthesizeTestWAV (AVSpeechSynthesizer buffer capture); Android's
 * synthesizeToFile already writes a WAV container. Returns null on any
 * engine/init failure (caller reports a friendly message). 15 s guard so a
 * stuck engine can't hang the test card forever.
 *
 * [dv26/G10] "Nothing may be written after cancellation" is one explicit latch.
 * Before it, a cancelled call (15 s timeout, or the sheet dismissed while the
 * engine was still initialising) ran `invokeOnCancellation` + the `finally`
 * delete, and THEN the late `TextToSpeech(context) { … }` init callback reached
 * `synthesizeToFile(...)` — writing `quicktest-clip-<ts>.wav` back into cacheDir
 * with nobody left to delete it. Every side effect below is gated through
 * [SynthClipPolicy]; the three delete sites cover the three ways a clip can
 * outlive the caller (exit path, cancel path, callback that lands after cancel).
 */
private suspend fun synthesizeTestClip(context: android.content.Context, text: String): ByteArray? {
    val file = java.io.File(context.cacheDir, "quicktest-clip-${System.currentTimeMillis()}.wav")
    // Flipped only by the cancellation path below; read by the init callback,
    // the synth dispatch and the progress callbacks (all main-looper callbacks).
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    return try {
        kotlinx.coroutines.withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var tts: android.speech.tts.TextToSpeech? = null
                fun finish(ok: Boolean) {
                    runCatching { tts?.shutdown() }
                    // A callback landing after cancellation is a late write: the
                    // clip is on disk and no caller will ever read it.
                    if (SynthClipPolicy.mustDropOnCallback(cancelled.get())) runCatching { file.delete() }
                    if (cont.isActive) cont.resumeWith(Result.success(ok))
                }
                tts = android.speech.tts.TextToSpeech(context) { status ->
                    if (!SynthClipPolicy.mayTouchEngine(cancelled.get())) {
                        runCatching { tts?.shutdown() }
                        return@TextToSpeech
                    }
                    if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                        finish(false)
                        return@TextToSpeech
                    }
                    tts?.language = java.util.Locale.US
                    tts?.setOnUtteranceProgressListener(
                        object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) = finish(true)
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) = finish(false)
                            override fun onError(utteranceId: String?, errorCode: Int) = finish(false)
                        },
                    )
                    // The request can be cancelled between the init callback and
                    // this line — re-check right before the write.
                    if (!SynthClipPolicy.maySynthesize(cancelled.get())) {
                        runCatching { tts?.shutdown() }
                        return@TextToSpeech
                    }
                    val result = tts?.synthesizeToFile(text, android.os.Bundle(), file, "quicktest-clip")
                    if (result != android.speech.tts.TextToSpeech.SUCCESS) finish(false)
                }
                cont.invokeOnCancellation {
                    cancelled.set(true)
                    runCatching { tts?.shutdown() }
                    // The engine can have finished the write between the dispatch
                    // and this cancellation, and the `finally` below may run
                    // before that write lands — drop the clip again here.
                    runCatching { file.delete() }
                }
            }
        }?.takeIf { it }?.let { file.readBytes() }
    } catch (e: Exception) {
        AppLogger.warning(TAG, "[QuickTest] test clip synthesis failed: ${e.message}")
        null
    } finally {
        runCatching { file.delete() }
    }
}
