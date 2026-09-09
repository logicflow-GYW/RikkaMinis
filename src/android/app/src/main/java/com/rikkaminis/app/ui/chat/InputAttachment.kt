package com.rikkaminis.app.ui.chat

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.util.UUID

@Immutable
data class InputAttachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val uri: Uri,
    val mimeType: String,
    val kind: Kind,
) {
    enum class Kind { IMAGE, DOCUMENT }

    val isImage: Boolean get() = kind == Kind.IMAGE
}