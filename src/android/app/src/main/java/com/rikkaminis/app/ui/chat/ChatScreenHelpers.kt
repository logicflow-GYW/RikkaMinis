package com.rikkaminis.app.ui.chat

// [T-android-split-chat] Camera/file URI helpers + PendingNonTextSelection model
// extracted verbatim from ChatScreen.kt. Imports pruned to the symbols this
// file uses (2026-10-27 sweep); all internal (used by the ChatScreen composable).

import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Create an empty JPEG file under filesDir/camera-photos/ and return a
 * FileProvider content URI for it, plus the underlying File. The camera
 * app writes the captured image into this URI synchronously.
 *
 * Bug 1 (MIUI): the previous version put the staging file under cacheDir,
 * which MIUI may clear while the camera Activity is in flight. Moving to
 * filesDir keeps the URI valid until we explicitly delete it.
 */
internal fun createCameraOutputUri(context: android.content.Context): Pair<Uri, java.io.File> {
    val dir = java.io.File(context.filesDir, "camera-photos").also { it.mkdirs() }
    // [da1028-C] `camera-photos` sits in filesDir, so the OS has no reason to
    // reclaim it and nothing else ever deleted these captures. Prune the 24h-old
    // ones with the shared pruner (same contract as cache/share) — the walk is
    // bounded by that TTL, so it stays cheap on the click path.
    com.rikkaminis.app.ui.components.pruneShareDir(dir)
    val ts = System.currentTimeMillis()
    val file = java.io.File(dir, "photo-$ts.jpg")
    file.createNewFile()
    val authority = context.packageName + ".fileprovider"
    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    return uri to file
}

internal fun getFileName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    return cursor.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) it.getString(nameIndex) else null
        } else null
    }
}

// ─── Model Picker Bottom Sheet ──────────────────────────────────────────────

/**
 * Captures a tap on a model whose output modality (image/audio/video) makes
 * it unsuitable for driving an Agent task, so the picker can show a
 * confirmation dialog before applying the binding.
 */
internal sealed class PendingNonTextSelection {
    abstract val modelDisplayName: String
    abstract val modalityLabel: String

    data class Group(
        val groupId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()

    data class GroupEntry(
        val groupId: String,
        val entryId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()

    data class Entry(
        val entryId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()
}
