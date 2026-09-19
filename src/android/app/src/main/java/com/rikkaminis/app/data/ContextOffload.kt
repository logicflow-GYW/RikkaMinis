package com.rikkaminis.app.data

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import java.io.File

/**
 * Per-session offload storage helpers — write large tool outputs to disk so
 * the model can `file_read` them later while we replace the in-history copy
 * with a tiny `[CONTEXT OFFLOADED] ... <linux-path>` stub.
 *
 * Mirrors iOS `AIChatViewModel.minisOffloadsPersistentDir(for:)`,
 * `offloadContextContent(_:toolId:toolName:ext:)`, and
 * `offloadContextImage(_:toolId:mimeType:)` (AIChatViewModel.swift:6964 +
 * 7170 + 7188). Same path layout — `.../offloads/tools/<name>_<id>.<ext>` —
 * so file_read paths round-trip across platforms when an Android-offloaded
 * session is opened on iOS (or vice versa) via cloud sync.
 *
 * Linux-visible mount: `/var/minis/offloads/tools/<file>`. The host base
 * `filesDir/minis-sessions/<sid>/offloads` is bind-mounted into the
 * sandbox by [com.rikkaminis.app.sandbox.PRootKernel.perSessionSubdirs]
 * (which already includes the "offloads" subdir — no kernel changes
 * required for this feature).
 */
object ContextOffload {
    /** Linux-side mount point — keep in lock-step with iOS `minisOffloadsLinuxDir`. */
    const val LINUX_OFFLOADS_DIR = "/var/minis/offloads"

    /** Sentinel prefix on stub strings — the agent loop checks this to skip
     *  re-offloading parts that have already been processed. Mirrors iOS. */
    const val OFFLOADED_PREFIX = "[CONTEXT OFFLOADED]"

    /**
     * Host-side persistent dir for [sessionId]'s tool offloads. Lazily
     * created on first write — callers should call [ensureToolsDir] before
     * writing.
     */
    fun toolsDir(context: Context, sessionId: String): File =
        File(context.filesDir, "minis-sessions/$sessionId/offloads/tools")

    private fun ensureToolsDir(context: Context, sessionId: String): File {
        val dir = toolsDir(context, sessionId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Take the last 12 chars of [toolId] as a short, locally-unique suffix
     * for the on-disk filename. Anthropic IDs are `toolu_01…` (constant
     * 8-char prefix), so the trailing 12 chars are still distinguishing.
     * Mirrors iOS `shortToolId(_:)`.
     *
     * [FIX-1 / F-232] The id is MODEL-CONTROLLED — it round-trips through the
     * provider and comes back in the tool_use block — so "take the last 12
     * chars" was an unbounded write of attacker-influenced text into a file
     * name. A trailing `../../x` produced `<sid>/x.txt`, i.e. a real escape
     * from `offloads/tools/`. The old `sanitize()` only replaced `/`, which
     * does not touch `..` at all (and `..` needs no slash to traverse once
     * joined). Restrict the suffix to a filename-safe alphabet — the same
     * allow-list shape `sanitizeToolId` uses for the wire.
     */
    private fun shortToolId(toolId: String): String {
        val safe = toolId.filter { it.isLetterOrDigit() || it in "_-" }
        return if (safe.length <= 12) safe else safe.takeLast(12)
    }

    /**
     * [FIX-1 / F-232] Defence in depth behind [shortToolId]: canonicalize the
     * resolved path and refuse anything that escaped [dir]. The allow-list
     * above should already make this unreachable, which is exactly why it is
     * cheap to keep — the sanitizer is the guard that can be weakened by a
     * later edit, this is the one that cannot.
     */
    private fun resolveInside(dir: File, fileName: String): File? {
        val file = File(dir, fileName)
        val canonicalDir = dir.canonicalFile
        val canonicalFile = file.canonicalFile
        return if (canonicalFile.path == canonicalDir.path ||
            canonicalFile.path.startsWith(canonicalDir.path + File.separator)
        ) {
            canonicalFile
        } else {
            AppLogger.warning(TAG, "refusing offload path escaping tools dir: $fileName")
            null
        }
    }

    private fun sanitize(name: String): String =
        name.ifEmpty { "tool" }.replace('/', '_')

    /**
     * Write tool text content to disk and return the Linux-visible path
     * the model can later pass to `file_read`. Returns the empty string
     * on any I/O failure — caller should still update the in-history part
     * with a stub so the model isn't left holding the original bytes.
     */
    fun offloadContent(
        context: Context,
        sessionId: String,
        content: String,
        toolId: String,
        toolName: String,
        ext: String = "txt",
    ): String {
        val dir = ensureToolsDir(context, sessionId)
        val fileName = "${sanitize(toolName)}_${shortToolId(toolId)}.$ext"
        // [FIX-1 / F-232] Resolve through the containment guard before writing.
        val file = resolveInside(dir, fileName) ?: return ""
        return try {
            file.writeText(content)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadContent failed: ${e.message}")
            ""
        }
    }

    /**
     * Write tool image bytes to disk and return the Linux-visible path.
     * Extension derived from MIME type — falls through to `.bin` for
     * unrecognised types so the file_read path still resolves something
     * the model can preview.
     */
    fun offloadImage(
        context: Context,
        sessionId: String,
        bytes: ByteArray,
        toolId: String,
        mimeType: String,
    ): String {
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
        val dir = ensureToolsDir(context, sessionId)
        val fileName = "image_${shortToolId(toolId)}.$ext"
        // [FIX-1 / F-232] Resolve through the containment guard before writing.
        val file = resolveInside(dir, fileName) ?: return ""
        return try {
            file.writeBytes(bytes)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadImage failed: ${e.message}")
            ""
        }
    }

    /**
     * Build the in-history stub that replaces an offloaded part. Format
     * is identical to iOS so a session opened on either platform shows
     * the same `[CONTEXT OFFLOADED] …` text where the real bytes used
     * to be.
     */
    fun stub(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$OFFLOADED_PREFIX Content (~$approxTokens tokens, $byteCount bytes) saved to: $linuxPath\n" +
            "Use file_read tool to retrieve if needed."

    private const val TAG = "ContextOffload"
}
