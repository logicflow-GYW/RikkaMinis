package com.rikkaminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.repository.ChatRepository
import java.io.File

/**
 * [fix/partial-stream-recovery] Cold-start recovery of streamed partial
 * content (RikkaHub #1663 同构, backlog §27b).
 *
 * The :modelservice worker appends every chunk to the per-run stream.jsonl
 * staging file
 * as it streams, but the client-side flush points (`flushAllStreamingDeltas`)
 * all live on turn-exit paths — a process kill mid-answer leaves the partial
 * content ONLY in that staging file, and the orphan reaper never recovers it
 * (it only deletes dirs whose worker finished cleanly). Net effect: the
 * per-chunk write cost was already paid, and the data was thrown away.
 *
 * This object closes that gap:
 *  - [writeMeta] stamps the owning session id into the run dir BEFORE the
 *    stream starts (sub-agent streams have no session — they write no meta
 *    and are unattributable);
 *  - [recover] runs at cold start (before the app UI settles), scans the
 *    staging root for killed-run leftovers (partial stream + no terminal +
 *    no cancel + not already recovered), joins the text deltas, appends an
 *    assistant message into the owning session, and deletes the dir.
 *
 * Order with the orphan reaper: the reaper only deletes dirs WITH a terminal
 * barrier, and recovery only touches dirs WITHOUT one — the two are disjoint
 * by construction, so either order is safe.
 */
object PartialStreamRecovery {

    private const val TAG = "PartialStreamRecovery"
    private const val STAGING_ROOT = "model-exec"
    private const val META_FILE = "meta.json"
    private const val RECOVERED_FILE = "recovered.json"

    /** Assistant role string, matching the chat transcript's persisted value. */
    internal const val ROLE_ASSISTANT = "assistant"

    /**
     * Same pattern as the user-cancel cleanup's truncation reminder: an
     * English system-reminder block the model reads on the next call.
     * Deliberately not localized (i18n is for user-authored copy; system
     * reminders stay English, mirroring the cancel path).
     */
    internal val recoveredReminder: String =
        "<system-reminder>This response was interrupted by a process kill. " +
            "Partial content recovered from the streaming log; it may be incomplete.</system-reminder>"

    /**
     * Stamp the owning session id into the run dir before the stream starts.
     * Never throws — a failed meta write only means the run becomes
     * unattributable (the old behavior), it must not break streaming.
     */
    fun writeMeta(dir: File, sessionId: String) {
        try {
            org.json.JSONObject().put("session_id", sessionId)
                .let { File(dir, META_FILE).writeText(it.toString() + "\n") }
        } catch (t: Throwable) {
            Log.w(TAG, "meta write failed (stream stays unattributable): ${t.message}")
        }
    }

    /**
     * Scan the staging root and recover killed-run partial streams. Returns
     * the number of recovered sessions (for logging). Never throws.
     */
    suspend fun recover(context: Context, repository: ChatRepository): Int {
        val root = File(context.cacheDir, STAGING_ROOT)
        if (!root.isDirectory) return 0
        var recovered = 0
        val children = runCatching { root.listFiles()?.filter { it.isDirectory } }.getOrDefault(emptyList())
        for (dir in children.orEmpty()) {
            try {
                val stream = File(dir, ModelExecutionService.STREAM_FILE)
                val metaFile = File(dir, META_FILE)
                val decision = PartialStreamRecoveryPolicy.decide(
                    streamLen = stream.length(),
                    terminalPresent = ModelExecutionRunDir.terminalPresent(dir),
                    cancelPresent = File(dir, ModelExecutionService.CANCEL_FILE).exists(),
                    recoveredPresent = File(dir, RECOVERED_FILE).exists(),
                    hasSessionMeta = metaFile.exists(),
                    mtimeAgeMs = System.currentTimeMillis() - dir.lastModified(),
                )
                when (decision) {
                    PartialStreamRecoveryPolicy.Decision.RECOVER -> Unit // handled below
                    PartialStreamRecoveryPolicy.Decision.SKIP_EMPTY,
                    PartialStreamRecoveryPolicy.Decision.SKIP_NO_META,
                    -> {
                        // Nothing recoverable and nothing downstream will ever
                        // clean these up (the reaper needs a terminal barrier).
                        dir.deleteRecursively()
                        continue
                    }
                    else -> continue // terminal / cancelled / active / already-recovered: keep for their owners
                }
                val sessionId = readMetaSessionId(dir)
                if (sessionId.isNullOrBlank()) {
                    Log.w(TAG, "unattributable killed run (no session meta), discarding: ${dir.name}")
                    dir.deleteRecursively()
                    continue
                }
                val text = joinRecoveredText(stream.readLines())
                if (text.isBlank()) {
                    dir.deleteRecursively()
                    continue
                }
                repository.appendMessage(
                    sessionId = sessionId,
                    role = ROLE_ASSISTANT,
                    partsJson = buildRecoveredPartsJson(text),
                )
                // Stamp BEFORE deleting: if the delete fails (reaper-style
                // filesystem restrictions) the marker prevents a duplicate
                // insert on the next cold start.
                File(dir, RECOVERED_FILE).writeText("recovered\n")
                val deleted = runCatching { dir.deleteRecursively() }.getOrDefault(false)
                if (!deleted) Log.w(TAG, "recovered but dir delete failed: ${dir.name}")
                recovered++
                Log.i(
                    TAG,
                    "recovered partial stream: session=${sessionId.take(8)} chars=${text.length} dir=${dir.name}",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "recovery skipped for ${dir.name}: ${t.message}")
            }
        }
        return recovered
    }

    /** Best-effort session id from the run dir's meta.json; null when absent. */
    private fun readMetaSessionId(dir: File): String? {
        val meta = File(dir, META_FILE)
        if (!meta.exists()) return null
        return try {
            org.json.JSONObject(meta.bufferedReader().use { it.readText() })
                .optString("session_id").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Pure decision table for one staging run dir at cold start. JVM-testable —
 * the Android-side [PartialStreamRecovery.recover] consumes it verbatim.
 *
 * Order matters: recovered-barrier first (a duplicate insert is the worst
 * outcome — the delete after insert can fail), then terminal / cancel (their
 * owners already committed or discarded the content), then empty, then the
 * recent-write grace (a dir touched moments ago may still be streaming).
 */
internal object PartialStreamRecoveryPolicy {

    enum class Decision { RECOVER, SKIP_TERMINAL, SKIP_CANCELLED, SKIP_EMPTY, SKIP_ACTIVE, SKIP_RECOVERED, SKIP_NO_META }

    /**
     * Grace for dirs touched very recently: at cold start the :modelservice
     * process is normally dead too, but a service that outlived a main-process
     * restart may still be appending — never race an active run.
     */
    const val RECENT_WRITE_GRACE_MS: Long = 30_000L

    fun decide(
        streamLen: Long,
        terminalPresent: Boolean,
        cancelPresent: Boolean,
        recoveredPresent: Boolean,
        hasSessionMeta: Boolean,
        mtimeAgeMs: Long,
    ): Decision = when {
        recoveredPresent -> Decision.SKIP_RECOVERED
        terminalPresent -> Decision.SKIP_TERMINAL
        cancelPresent -> Decision.SKIP_CANCELLED
        streamLen <= 0L -> Decision.SKIP_EMPTY
        !hasSessionMeta -> Decision.SKIP_NO_META
        mtimeAgeMs < RECENT_WRITE_GRACE_MS -> Decision.SKIP_ACTIVE
        else -> Decision.RECOVER
    }
}

/**
 * Pure delta-join over one stream.jsonl: concatenates visible text deltas in
 * order (the Text chunk is an incremental delta, matching what the UI
 * accumulated). Thinking/reasoning/tool/usage frames are ignored — the
 * recovered artifact is the visible answer, mirroring the user-cancel
 * cleanup's partial-text semantics. JVM-testable.
 */
internal fun joinRecoveredText(lines: List<String>): String = buildString {
    for (line in lines) {
        if (line.isBlank()) continue
        val chunk = ChatStreamJsonl.decode(line) ?: continue
        if (chunk is LLMStreamChunk.Text) append(chunk.text)
    }
}

/** Serialize the recovered text + reminder into the persisted parts JSON shape. */
internal fun buildRecoveredPartsJson(text: String): String =
    org.json.JSONArray()
        .put(
            org.json.JSONObject().put("type", "text").put("value", text),
        )
        .put(
            org.json.JSONObject().put("type", "text").put("value", PartialStreamRecovery.recoveredReminder),
        )
        .toString()
