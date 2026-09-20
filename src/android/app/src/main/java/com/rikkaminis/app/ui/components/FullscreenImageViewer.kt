package com.rikkaminis.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.rikkaminis.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

// [fix/render-ui F-272] The `FullscreenImageViewer` composable was deleted here.
//
// It had zero call sites anywhere in the repo (production, `src/test`,
// `src/androidTest`, `docs/` — `git grep -n FullscreenImageViewer` returned
// only this definition plus comments), while the five helpers below it are
// shared with `ImageGalleryViewer` and are still live. Two consequences drove
// the deletion:
//
//  1. The dead copy held the *correct* implementations (Main-thread Toast
//     hops in `copyBitmapToClipboard` / `shareImage`) that the live copy was
//     missing — see F-270 and F-271. Keeping a dead "reference" copy next to a
//     broken live one is how the bug got copied in the first place
//     (`ImageGalleryViewer.kt` carried a comment saying it mirrored this file).
//  2. Its window walkers (`findWindowViaDialog` / `findDialogWindow`, both
//     `ctx is Activity` based) never matched, so re-enabling the viewer would
//     have re-introduced F-271 as well.
//
// The helpers that remain are `internal` on purpose: they are the single
// implementation shared by the gallery. Anything that needs a fullscreen
// single-image view should reuse `ImageGalleryViewer` (a one-item list renders
// identically — see its KDoc) rather than growing a second copy.

// ── Helper Composable ──────────────────────────────────────────────────────────

@Composable
internal fun ImageActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── Image loading helpers ──────────────────────────────────────────────────────

// [audit-0917] One loader for the process: a per-call ImageLoader(context)
// spun up its own executor and was never shut down - it leaked threads on
// every Copy/Share/Save. Coil's ImageLoader is meant to be a singleton.
@Volatile
private var sharedImageLoader: ImageLoader? = null

internal suspend fun loadBitmap(context: Context, model: Any): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val loader = sharedImageLoader
                ?: ImageLoader(context.applicationContext).also { sharedImageLoader = it }
            val req = ImageRequest.Builder(context).data(model).allowHardware(false).build()
            val result = loader.execute(req)
            (result as? SuccessResult)?.drawable?.toBitmap()
        } catch (e: Exception) {
            // [audit-0917] CancellationException must propagate: swallowing it
            // turned a cancelled load into a silent null and broke structured
            // concurrency.
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

/**
 * T139: Copy the displayed image to the system clipboard. Two issues with
 * the previous implementation crashed the app on big images:
 *  1) `bitmap.compress()` ran on the main thread (it was wrapped in
 *     `scope.launch {}` with the default Main dispatcher), causing ANR on
 *     multi-MB PNG encodes.
 *  2) The clipboard URI was handed to ClipboardManager without
 *     `grantUriPermission` + `FLAG_GRANT_READ_URI_PERMISSION`, so the
 *     receiving paste target (any process reading the clip) hit
 *     SecurityException reading our FileProvider authority.
 *
 * The fix loads the bitmap + writes the temp file on Dispatchers.IO,
 * explicitly grants read permission to all packages for the temp URI,
 * then hops to Main to set the clip + toast. All paths surface a Toast
 * so the user sees a result instead of a silent dismiss.
 */
internal fun copyBitmapToClipboard(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    model: Any,
) {
    scope.launch(Dispatchers.IO) {
        try {
            val bitmap = loadBitmap(context, model)
                ?: error("decode failed")
            // T207: write under cache/share/ so FileProvider's <cache-path
            // name="share"> root matches. Files in cacheDir root aren't
            // covered by any declared root and would throw
            // IllegalArgumentException at getUriForFile.
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            pruneShareDir(shareDir)
            val file = File(shareDir, "clipboard_img_${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            // Grant read access to anyone who pastes the clip. Without this
            // the receiving app (Photos, Gboard preview, Files) gets a
            // SecurityException on contentResolver.openInputStream(uri),
            // which the system surfaces as an immediate crash on some OEMs.
            context.grantUriPermission("*", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newUri(context.contentResolver, "image", uri)
            withContext(Dispatchers.Main) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.image_copied_toast), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.image_copy_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

internal suspend fun shareImage(context: Context, model: Any) {
    // T197: previously a silent `?: return` on decode failure made users think
    // the button did nothing, and a missing FLAG_ACTIVITY_NEW_TASK threw
    // AndroidRuntimeException when LocalContext resolved to a non-Activity
    // (Dialog inside an inner ContextWrapper). Toast on every failure path,
    // and stamp NEW_TASK on both the inner intent and the chooser.
    try {
        val bmp = loadBitmap(context, model) ?: run {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
            }
            return
        }
        withContext(Dispatchers.IO) {
            // T207: see clipboard path above — must live under cache/share/
            // for FileProvider to resolve the URI.
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            pruneShareDir(shareDir)
            val file = File(shareDir, "share_img_${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.image_share_chooser_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(chooser)
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.image_share_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
}

internal suspend fun saveToGallery(context: Context, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val filename = "minis_${System.currentTimeMillis()}.png"
            val stream: OutputStream?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Minis")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                try {
                    stream = context.contentResolver.openOutputStream(uri)
                    stream?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    // [audit-0917] Delete the orphaned IS_PENDING=1 row: the old
                    // catch returned false and left a pending entry in the gallery.
                    context.contentResolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val minisDir = File(dir, "Minis").also { it.mkdirs() }
                val file = File(minisDir, filename)
                stream = file.outputStream()
                stream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

// ── Share-dir housekeeping ─────────────────────────────────────────────────────

/**
 * [fix/audit-b17 / T10-L10b] Share / copy-to-clipboard blobs are written once
 * and never deleted by anyone — they accumulate in cache/share/ for the life
 * of the install. Prune entries older than a day before writing a new one.
 */
// [fix/render-ui F-273] `internal`, not `private`: the same `cache/share/`
// directory is written by the table-copy paths in ChatMiscViews.kt /
// StreamingMarkdownText.kt and by WebAppActivity, and nothing deleted those
// files. This is the only name-agnostic pruner (24h TTL), so the writers that
// can reach it call it. (WebAppActivity is outside this batch's file set —
// see REPORT.md §跨批补丁.)
internal fun pruneShareDir(dir: File) {
    val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    dir.listFiles()?.forEach { f ->
        if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
    }
}
