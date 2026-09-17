package com.rikkaminis.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri

/**
 * A composition-wide callback for opening a URL inside the app's WebView sheet.
 *
 * - For http/https links, callers should invoke this to trigger [UrlPreviewSheet]
 *   and keep the user in-app (iOS-style preview).
 * - For other schemes (mailto:, tel:, geo:, minis://, etc.) fall back to
 *   [openExternalUrl] which dispatches a normal system Intent.
 *
 * The root [InAppBrowserHost] provides this and renders the sheet when invoked.
 * A screen read outside a host (e.g. a bare preview/test) gets the default
 * below, which cannot dispatch an Intent — a CompositionLocal default has no
 * Context — so it logs instead of pretending to have opened anything.
 * [audit-0917] The KDoc previously claimed this fallback opened the external
 * Intent, which the default lambda could never do; the mismatch made a missing
 * host look like a working one.
 */
val LocalInAppBrowserLauncher = compositionLocalOf<(String) -> Unit> {
    // Fallback: no host above us. Nothing can be opened from here (no Context),
    // so make the no-op observable rather than silent.
    { url ->
        android.util.Log.w(
            "InAppBrowserLauncher",
            "no InAppBrowserHost above; link dropped: $url",
        )
    }
}

/**
 * Fire a system ACTION_VIEW intent for schemes we don't preview in-app
 * (mailto, tel, geo, sms, etc.) or when we explicitly want the external handler.
 */
fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Host composable: provides [LocalInAppBrowserLauncher] and renders
 * [UrlPreviewSheet] when a URL is requested. Place this once near the top of
 * the app (around the NavHost) so any screen can open links in-app.
 *
 * Only http/https URLs are routed through the in-app sheet. Anything else
 * is delegated to a normal system Intent so mailto:/tel:/maps:/minis:// keep
 * working.
 */
@Composable
fun InAppBrowserHost(
    context: Context,
    content: @Composable () -> Unit,
) {
    var previewUrl by remember { mutableStateOf<String?>(null) }

    val launcher = remember(context) {
        { url: String ->
            val lower = url.trim().lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                previewUrl = url
            } else {
                openExternalUrl(context, url)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalInAppBrowserLauncher provides launcher,
    ) {
        content()
    }

    previewUrl?.let { url ->
        UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }
}
