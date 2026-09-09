package com.rikkaminis.app.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.rikkaminis.app.R

/**
 * Metadata for the composer attach menu (the "+" circle button beside the
 * send button). The three attach actions (photos & videos / file / camera)
 * each carry a stable, non-localized key plus a title and icon — the single
 * source for the "+" menu rendering in ChatScreen, the new "Attach" card in
 * ChatMenuSettingsScreen, and the solo-promoted direct button.
 *
 * This deliberately mirrors [ChatActionCatalog] but lives in its own object
 * so the attach keys never leak into the top-right "..." menu pool: the
 * right-hand menu's rendering loop, order normalization and ConfigBuiltins
 * registration all iterate [ChatMenuPrefs].ALL_ENTRIES, and mixing domains
 * would make a stale attach key resurrect in the "..." menu or trip the
 * anchorSettingsLast logic. Keys are the same stable ids used as
 * SharedPreferences keys and order-list ids in [ChatMenuPrefs].
 */
object AttachActionCatalog {
    val CHOOSE_PHOTOS = "attach_choose_photos"
    val ADD_FILE = "attach_add_file"
    val TAKE_PHOTO = "attach_take_photo"

    /** Default order — most frequent action first, destructive-ish last. */
    val DEFAULT_ORDER: List<String> = listOf(
        CHOOSE_PHOTOS,
        ADD_FILE,
        TAKE_PHOTO,
    )

    data class Spec(
        val key: String,
        val titleRes: Int,
        val icon: ImageVector,
    )

    /** Each action calls a distinct picker/launcher — resolved in ChatScreen. */
    val ALL: List<Spec> = listOf(
        Spec(CHOOSE_PHOTOS, R.string.chat_attach_choose_photos_videos, Icons.Default.PhotoLibrary),
        Spec(ADD_FILE, R.string.chat_attach_add_file, Icons.Default.Description),
        Spec(TAKE_PHOTO, R.string.chat_attach_take_photo, Icons.Default.CameraAlt),
    )

    fun spec(key: String): Spec? = ALL.firstOrNull { it.key == key }
}