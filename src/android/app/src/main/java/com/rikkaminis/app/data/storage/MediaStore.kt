package com.rikkaminis.app.data.storage

import android.content.Context
import com.rikkaminis.app.data.model.MediaRef
import com.rikkaminis.app.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MediaStore(context: Context) {

    private val TAG = "MediaStore"

    val mediaBaseDir: File = File(context.filesDir, "media")

    fun saveMedia(
        data: ByteArray,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef {
        val id = UUID.randomUUID().toString()
        val dateDir = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
        val originalExt = originalFileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() } }
            ?.lowercase()
        val ext = originalExt ?: extensionFor(mimeType)
        val relativePath = "$dateDir/$sessionId/$id.$ext"
        val file = File(mediaBaseDir, relativePath)
        file.parentFile?.mkdirs()
        // [audit-0917] Clean up a partial file when the write fails, exactly as
        // saveMediaStreamed does. An unguarded writeBytes left a truncated
        // attachment on disk after a disk-full / OOM failure, and nothing ever
        // prunes it — the reference was never returned, so no caller could.
        try {
            file.writeBytes(data)
        } catch (t: Throwable) {
            runCatching { file.delete() }
            throw t
        }
        return MediaRef(
            id = id,
            relativePath = relativePath,
            mimeType = mimeType,
            originalFileName = originalFileName,
        )
    }

    /**
     * Streaming variant: copy from [source] InputStream into the same
     * dated path layout as [saveMedia], without loading the whole file
     * into memory. Used by non-image attachments (APKs, archives, large
     * binaries) where a `readBytes()` would OOM for anything over ~100MB
     * on low-RAM devices.
     *
     * Returns null on I/O failure; the caller treats this as "skip this
     * attachment" without aborting the send.
     */
    fun saveMediaStreamed(
        source: InputStream,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef? {
        val id = UUID.randomUUID().toString()
        val dateDir = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
        val originalExt = originalFileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() } }
            ?.lowercase()
        val ext = originalExt ?: extensionFor(mimeType)
        val relativePath = "$dateDir/$sessionId/$id.$ext"
        val file = File(mediaBaseDir, relativePath)
        file.parentFile?.mkdirs()
        return try {
            file.outputStream().use { out -> source.copyTo(out) }
            MediaRef(
                id = id,
                relativePath = relativePath,
                mimeType = mimeType,
                originalFileName = originalFileName,
            )
        } catch (t: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    fun loadMedia(ref: MediaRef): ByteArray? {
        val file = File(mediaBaseDir, ref.relativePath)
        return if (file.exists()) file.readBytes() else null
    }

    fun deleteSessionMedia(sessionId: String) {
        if (sessionId.isBlank()) return
        // [audit-0917] Only delete a directory that really is this session's
        // media folder — i.e. <base>/<yyyy>/<MM>/<dd>/<sessionId> — instead of
        // any directory anywhere under the base whose name happens to match.
        // The old walk also ignored deleteRecursively()'s false return, so a
        // failed delete (or a name collision in an unrelated branch) was
        // invisible. Failures are now logged.
        mediaBaseDir.walkTopDown()
            .filter { dir ->
                dir.isDirectory &&
                    dir.name == sessionId &&
                    dir.parentFile?.parentFile?.parentFile?.parentFile == mediaBaseDir
            }
            .forEach { dir ->
                val deleted = runCatching { dir.deleteRecursively() }.getOrDefault(false)
                if (!deleted) {
                    AppLogger.warning(TAG, "deleteSessionMedia: could not delete ${dir.path}")
                }
            }
    }

    private fun extensionFor(mimeType: String): String {
        return when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("pdf") -> "pdf"
            else -> "bin"
        }
    }
}
