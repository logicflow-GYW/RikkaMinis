package com.rikkaminis.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Open the system page where the user grants (or revokes) all-files access.
 *
 * The mirror row's only actionable state is "the grant is gone", and its fix is a
 * Settings page that a background sync cannot request. The OEM fallback is not
 * decoration: HarmonyOS / EMUI 2.x never host the per-app
 * `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` page, so a bare
 * `startActivity` throws `ActivityNotFoundException` there and the tap looks
 * ignored. Falls back to the app-details screen, which those ROMs do have.
 *
 * Shared with `MountedFoldersScreen`: both screens show a banner/row that is
 * dead without this grant, and a second hand-written copy is how one of them
 * ends up with a fallback the other lacks. (That screen still keeps its own Q
 * branch — on API 29 the repair is the runtime-permission dialog, not a Settings
 * page — but the R+ half is this function.)
 */
internal fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val pkg = Uri.parse("package:${context.packageName}")
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
    }.isSuccess
    if (opened) return
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    }
}
