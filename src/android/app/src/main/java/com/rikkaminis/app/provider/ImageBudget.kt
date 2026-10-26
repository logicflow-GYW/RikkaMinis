package com.rikkaminis.app.provider

import android.graphics.Bitmap
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.util.ImageDecoding
import com.rikkaminis.app.util.saveOutcome
import java.io.ByteArrayOutputStream

/**
 * Per-message inline-image byte budget — mirrors iOS AIChatViewModel.swift
 * kPerImageMaxBytes / kMessageImageMaxBytes (commit b830360).
 *
 * Anthropic returns HTTP 413 ("Downloaded image content cannot exceed 30MB")
 * when the request's total inline image payload exceeds the provider cap.
 * Pre-T-imgsize we only resized each attachment to a max-edge of 2000px at
 * JPEG q=85, which is *resolution* budgeting and does nothing for byte size
 * when the source is a 12MP HEIC. We now enforce a real byte cap at two
 * layers:
 *
 *   1. Composer (ChatViewModel.prepareUserAttachments) — runs the user's
 *      brand-new attachments through [compressUnderBudget] so every part is
 *      ≤ [perImageMaxBytes], then tallies cumulative bytes and drops the
 *      tail with a Snackbar notice once [messageTotalMaxBytes] is exceeded.
 *   2. Provider boundary (AnthropicProvider / OpenAIProvider) — belt and
 *      braces for history image parts that bypass the composer (e.g. tool
 *      results screenshot bytes, restored sessions, retry-after-edit). Each
 *      oversize part is silently re-encoded in-place via [compressBytes].
 *
 * URL HEAD pre-check (spec §2.b) intentionally omitted — both Android
 * providers always base64-inline image bytes (no remote URL forwarding),
 * mirroring the iOS finding in commit b830360.
 */
object ImageBudget {
    /** [feat/chat-tuning-panel-b] Single-image ceiling (user-tunable; default 5 MB). */
    fun perImageMaxBytes(): Long =
        AgentRuntimeLimitsPrefs.imageMaxPerImageMb().toLong() * 1024 * 1024

    /** [feat/chat-tuning-panel-b] Per-message image total (user-tunable; default 25 MB). */
    fun messageTotalMaxBytes(): Long =
        AgentRuntimeLimitsPrefs.imageMaxTotalMb().toLong() * 1024 * 1024

    /**
     * Cumulative inline-image bytes across ALL messages in a single
     * request body. Mirrors the per-message cap but applies at the
     * request boundary so a long history accumulating images from
     * multiple turns (browser screenshots, attachments, read_image
     * results) cannot push the request past the cap that triggered
     * factory.pub / Anthropic gateways to silently return 200 +
     * empty SSE with `finish_reason=stop`. Eldest images are elided
     * to text placeholders first.
     * [feat/chat-tuning-panel-b] Ceiling is user-tunable (default 25 MB).
     */
    fun requestMaxBytes(): Long =
        AgentRuntimeLimitsPrefs.imageMaxRequestMb().toLong() * 1024 * 1024

    /** [feat/chat-tuning-panel-b] Re-encode target edge (user-tunable; default 2000). */
    fun maxEdgePx(): Int = AgentRuntimeLimitsPrefs.imageMaxEdgePx()

    /** [feat/chat-tuning-panel-b] Re-encode quality (user-tunable; default 80). */
    fun jpegQuality(): Int = AgentRuntimeLimitsPrefs.imageJpegQuality()

    private const val TAG = "ImageBudget"

    /**
     * (maxEdge, quality) candidates the per-image compressor walks until the
     * output fits under [perImageMaxBytes]. Mirrors the iOS ladder from
     * AIChatViewModel.swift compressedImageDataUnderBudget(...).
     *
     * [feat/chat-tuning-panel-b] The ladder is now derived from the
     * user-tunable edge/quality via [ImageBudgetLadder.ladderFor]; at the
     * default (2000, 80) it reproduces the exact pre-panel ladder
     * (2000/80 → 1600/75 → … → 640/45, pinned by a JVM test).
     */

    /**
     * Re-encode [input] to a JPEG with max longest edge [maxEdge] at JPEG
     * quality [q]. Returns the original bytes on decode/encode failure
     * (caller's existing payload is always safer than dropping the image).
     */
    fun compressBytes(input: ByteArray, maxEdge: Int = maxEdgePx(), q: Int = jpegQuality()): ByteArray {
        if (input.isEmpty()) return input
        return try {
            // [debt-1026 B3] The decode is the shared bounded one, not a
            // hand-rolled `maxEdge * 2` sampling loop: that window left
            // `sample` at 1 for a 4000x3000 source (12000x9000 is the first
            // size that samples), i.e. it materialised the FULL-resolution
            // bitmap (~48 MB ARGB_8888) on the network path and only shrank it
            // afterwards. `ImageDecoding` probes the header first and bounds the
            // decode at (2 * maxEdge)^2; it returns null on any decode failure.
            val decoded = ImageDecoding.decodeBytesBounded(input, maxEdge) ?: return input
            try {
                val baos = ByteArrayOutputStream()
                // [debt-1026 B2] `Bitmap.compress` answers FALSE when the encoder
                // wrote no pixels — it does NOT throw. Dropped, `baos.toByteArray()`
                // was an EMPTY array, `compressUnderBudget` then saw `0 <= target`
                // as a fit and provisioned the empty image to the provider (HTTP 400
                // image-decode). The KDoc above promises the ORIGINAL bytes on an
                // encode failure; that is what the false branch returns.
                val encoded = decoded.bitmap.compress(Bitmap.CompressFormat.JPEG, q.coerceIn(1, 100), baos)
                if (!saveOutcome(streamOpened = true, compressed = encoded)) {
                    AppLogger.warning(
                        TAG,
                        "compressBytes: JPEG encoder wrote no bytes (edge=$maxEdge q=$q, ${input.size}B → keeping original)",
                    )
                    return input
                }
                return baos.toByteArray()
            } finally {
                // One bitmap to release, on both the success and the encode-throw
                // path (the old shape leaked it when `compress` threw).
                decoded.bitmap.recycle()
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "compressBytes failed (${input.size}B → keeping original): ${t.message}")
            input
        }
    }

    /**
     * Try increasingly aggressive (maxEdge, quality) candidates until the
     * re-encoded JPEG fits under [targetMaxBytes]. Returns the smallest
     * encoding produced when no candidate fits — never returns null because
     * "send something" beats "fail the request".
     *
     * Re-encodes from the original bytes on each candidate so the JPEG never
     * compounds artifacts.
     */
    fun compressUnderBudget(input: ByteArray, targetMaxBytes: Long = perImageMaxBytes()): ByteArray {
        if (input.size <= targetMaxBytes) return input
        var best: ByteArray = input
        var bestSize = input.size
        // [feat/chat-tuning-panel-b] Ladder derives from the tuned edge/quality.
        for ((edge, q) in ImageBudgetLadder.ladderFor(maxEdgePx(), jpegQuality())) {
            val candidate = compressBytes(input, edge, q)
            if (candidate.size < bestSize) {
                best = candidate
                bestSize = candidate.size
            }
            if (candidate.size.toLong() <= targetMaxBytes) {
                AppLogger.info(TAG, "compressUnderBudget hit: ${input.size}B → ${candidate.size}B (edge=$edge q=$q)")
                return candidate
            }
        }
        AppLogger.warning(TAG, "compressUnderBudget exhausted ladder: ${input.size}B → ${best.size}B (target=${targetMaxBytes}B)")
        return best
    }

    /** Result of [applyMessageBudget]. */
    data class BudgetResult(
        /** Image bytes ready to send, in original order, dropped tail removed. */
        val keptBytes: List<ByteArray>,
        /** Number of parts whose bytes were re-encoded by the ladder. */
        val compressedCount: Int,
        /** Number of tail parts dropped because the cumulative cap was hit. */
        val droppedCount: Int,
        /** Final total payload bytes after compression + drop. */
        val totalBytes: Long,
    ) {
        val mutated: Boolean get() = compressedCount > 0 || droppedCount > 0
    }

    /**
     * Walk [bytesIn] and produce a budgeted output:
     *  - Each oversize part is run through [compressUnderBudget] first.
     *  - Then cumulative bytes are summed; once the running total would
     *    exceed the per-message total cap the remaining tail is dropped.
     */
    fun applyMessageBudget(bytesIn: List<ByteArray>): BudgetResult {
        if (bytesIn.isEmpty()) return BudgetResult(emptyList(), 0, 0, 0L)
        val kept = ArrayList<ByteArray>(bytesIn.size)
        var compressed = 0
        var dropped = 0
        var running = 0L
        for (part in bytesIn) {
            val sized = if (part.size.toLong() > perImageMaxBytes()) {
                val c = compressUnderBudget(part)
                if (c.size != part.size) compressed += 1
                c
            } else part
            if (running + sized.size.toLong() > messageTotalMaxBytes()) {
                dropped += 1
                continue
            }
            kept.add(sized)
            running += sized.size.toLong()
        }
        if (dropped > 0 || compressed > 0) {
            AppLogger.info(
                TAG,
                "applyMessageBudget: in=${bytesIn.size} kept=${kept.size} compressed=$compressed dropped=$dropped total=${running}B",
            )
        }
        return BudgetResult(kept, compressed, dropped, running)
    }

    // ─── Request-level budget ──────────────────────────────────────────────

    /**
     * Identifies a single image part inside an outgoing request payload.
     * Used by providers to look up whether a part has been marked for
     * elision by [planRequestBudget]. The identity hash of the ByteArray
     * is sufficient — same bytes only ever appear once per request, and
     * different bytes hash uniquely enough that the worst-case collision
     * is "we keep one extra image past the budget" (safe).
     */
    @JvmInline
    value class ImagePartId(val identityHash: Int) {
        companion object {
            fun of(data: ByteArray): ImagePartId = ImagePartId(System.identityHashCode(data))
        }
    }

    /**
     * Image part scoped for budget planning. Lets the planner stay generic
     * over `LLMMessage.ImagePart` (current-turn user attachments) and
     * `AgentContentPart.ImageData` / `AgentContentPart.ToolResult.imageData`
     * (history image bytes) without depending on either type directly.
     */
    data class BudgetImage(
        val data: ByteArray,
        val linuxPath: String?,
        val mimeType: String,
    )

    /**
     * Result of [planRequestBudget].
     */
    data class RequestBudgetPlan(
        /** Identity hashes of image bytes that providers must NOT inline. */
        val droppedIds: Set<ImagePartId>,
        /** Map from dropped identity → original linux path (null if none). */
        val droppedPaths: Map<ImagePartId, String?>,
        /** Total bytes kept after planning (sum of per-image cap-clamped). */
        val keptBytes: Long,
        /** Total bytes elided. */
        val elidedBytes: Long,
        /** Number of images elided. */
        val droppedCount: Int,
        /** Total images considered (kept + dropped). */
        val totalCount: Int,
    ) {
        val mutated: Boolean get() = droppedCount > 0
    }

    /**
     * Walk all candidate images in reverse chronological order (latest
     * first) and decide which can fit under [maxBytes]. Anything beyond
     * the cap is recorded in [RequestBudgetPlan.droppedIds] so providers
     * emit a text placeholder instead of base64 bytes.
     *
     * @param images Ordered eldest → latest. The planner reverses
     *   internally so the latest user input + most recent tool results
     *   are protected from elision.
     */
    fun planRequestBudget(
        images: List<BudgetImage>,
        maxBytes: Long = requestMaxBytes(),
    ): RequestBudgetPlan {
        if (images.isEmpty()) {
            return RequestBudgetPlan(emptySet(), emptyMap(), 0L, 0L, 0, 0)
        }
        val dropped = HashSet<ImagePartId>()
        val droppedPaths = HashMap<ImagePartId, String?>()
        var kept = 0L
        var elided = 0L
        // Walk latest → eldest so most-recent images win the budget.
        for (img in images.asReversed()) {
            val id = ImagePartId.of(img.data)
            // Per-image cap-clamped size — same ceiling
            // `compressUnderBudget` would have produced if invoked.
            val effectiveSize = minOf(img.data.size.toLong(), perImageMaxBytes())
            if (kept + effectiveSize <= maxBytes) {
                kept += effectiveSize
            } else {
                dropped.add(id)
                droppedPaths[id] = img.linuxPath
                elided += effectiveSize
            }
        }
        if (dropped.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "planRequestBudget: in=${images.size} kept=${images.size - dropped.size} dropped=${dropped.size} keptBytes=${kept}B elidedBytes=${elided}B cap=${maxBytes}B",
            )
        }
        return RequestBudgetPlan(
            droppedIds = dropped,
            droppedPaths = droppedPaths,
            keptBytes = kept,
            elidedBytes = elided,
            droppedCount = dropped.size,
            totalCount = images.size,
        )
    }

    /**
     * Build the text placeholder a provider emits in place of an elided
     * image. The model gets a clear, actionable hint: this image was
     * dropped to fit the budget, and (if known) the linux path where the
     * bytes are still readable via [read_image]. Without a path the model
     * just sees that an image was elided and can ask the user to re-attach.
     */
    fun elidedImagePlaceholder(linuxPath: String?): String {
        return if (linuxPath != null) {
            "[image elided to fit 25MB request budget. Original at $linuxPath — re-fetch with `read_image $linuxPath` if you need to see it.]"
        } else {
            "[image elided to fit 25MB request budget. Original bytes no longer addressable; ask the user to re-attach if needed.]"
        }
    }

    /**
     * Lazily persist [data] to a session-scoped spillover dir under
     * `attachments/spillover/<sha1>.<ext>` so an elided image without a
     * pre-existing linux path can still be referenced from the text
     * placeholder. The spillover dir is bind-mounted to
     * `/var/minis/attachments/spillover/` inside iSH (same mount as
     * `attachments/uploads/`). Returns the iSH-visible linux path on
     * success, or null if the write failed (in which case the placeholder
     * falls back to the no-path variant).
     *
     * Idempotent: if the same bytes already exist on disk under the
     * sha1 prefix, returns the existing path without re-writing.
     */
    fun ensureSpillover(
        sessionAttachmentsDir: java.io.File,
        data: ByteArray,
        mimeType: String,
    ): String? {
        if (data.isEmpty()) return null
        val ext = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "bin"
        }
        val sha = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.update(data)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // SHA-1 is required by the platform; fallback is just an
            // identity hash — collisions are tolerable here.
            System.identityHashCode(data).toString(16)
        }
        val spilloverDir = java.io.File(sessionAttachmentsDir, "spillover")
        if (!spilloverDir.exists()) {
            try {
                spilloverDir.mkdirs()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover mkdirs failed: ${e.message}")
                return null
            }
        }
        val file = java.io.File(spilloverDir, "$sha.$ext")
        if (!file.exists()) {
            try {
                file.writeBytes(data)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover write failed: ${e.message}")
                return null
            }
        }
        // Mirrors uploads mount (see ChatViewModel.prepareUserAttachments).
        return "/var/minis/attachments/spillover/$sha.$ext"
    }
}
