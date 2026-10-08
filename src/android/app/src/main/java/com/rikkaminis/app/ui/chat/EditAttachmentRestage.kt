package com.rikkaminis.app.ui.chat

import android.net.Uri

/**
 * One attachment re-staged into the composer when entering edit mode.
 * [isImage] mirrors the persisted image-first ordering contract
 * (attachmentNames = imageNames + nonImageNames, see
 * [ChatViewModel.prepareUserAttachments] and the reload path in
 * ChatTranscriptRebuild).
 */
data class RestagedAttachment(val fileName: String, val uri: Uri, val isImage: Boolean)

/**
 * [T-edit-resend-attachment-loss-1008] Pure mapping for re-staging a user
 * turn's persisted attachments into the composer when the user long-presses
 * → Edit. sendMessage() sources attachments solely from the composer
 * (_attachments), and truncateBeforeEdit() deletes the original DB row —
 * whose mediaRef parts carried the file:// URIs — so without re-staging the
 * edited resend silently loses every attachment (text survives, files
 * vanish).
 *
 * Order contract: [attachmentNames] is image-first
 * (imageNames + nonImageNames); index < [imageUris].size ⇒ image, otherwise
 * the (idx - imageUris.size)-th non-image URI. Entries whose URI is null or
 * whose backing file no longer exists are skipped — prepareUserAttachments
 * would skip them at send time anyway, and staging only live chips avoids
 * re-introducing the same "attachment vanished" symptom through a stale URI.
 *
 * Index discipline: skipping a dead entry also skips its name-slot — a dead
 * image does NOT shift surviving names onto non-image URIs (and vice versa):
 * doc slots stay anchored at (nameIdx - imageUris.size) regardless of how
 * many images died. Requires the image-first name↔URI position alignment
 * that all producers guarantee (prepareUserAttachments, reload path).
 *
 * ponytail: 死文件（mediaRef 指向的文件已被清理）静默跳过不提示 | 天花板: 用户编辑含已被系统清理附件的旧消息时，重挂的芯片比原气泡少且无解释 | 升级触发: 用户报「编辑重发后附件数量比原来少」
 *
 * Pure + JVM-testable (Uri behind an interface-shaped parameter; no
 * Android framework calls).
 */
fun restageEditAttachments(
    attachmentNames: List<String>,
    imageUris: List<Uri>,
    attachmentUris: List<Uri>,
    fileExists: (String?) -> Boolean,
): List<RestagedAttachment> {
    val out = mutableListOf<RestagedAttachment>()
    attachmentNames.forEachIndexed { idx, name ->
        val isImage = idx < imageUris.size
        val uri = if (isImage) imageUris[idx]
                  else attachmentUris.getOrNull(idx - imageUris.size)
        val path = uri?.path
        if (path == null || !fileExists(path)) return@forEachIndexed
        out.add(RestagedAttachment(fileName = name, uri = uri, isImage = isImage))
    }
    return out
}
