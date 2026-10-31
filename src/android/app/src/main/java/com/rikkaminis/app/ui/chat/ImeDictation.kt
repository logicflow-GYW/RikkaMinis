package com.rikkaminis.app.ui.chat

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * [fix/ime-dictation-guard] Whether something is recording audio right now.
 * While the keyboard is up, that is the IME's voice typing — Gboard, WeChat
 * and Doubao all record through the platform audio APIs.
 *
 * Android offers no "the IME is dictating" signal, but since API 29 every app
 * may see the active recording configurations (other apps' client details are
 * anonymised), so a non-empty list while our own composer is focused is the
 * best available proxy. Hiding the keyboard then ends the dictation, which is
 * why scroll-to-dismiss checks this first.
 *
 * Ported from upstream OpenMinis `ui/chat/ReadingAnchor.kt` (issue #472 /
 * FIX-261022-07): upstream saw Gboard log `stopVoiceInput reason SHUTDOWN`
 * right after a `hide()` triggered by dragging the message list.
 *
 * Fail-open by construction: any reflection/service failure reads as "not
 * recording", i.e. the pre-fix behaviour — a guard must never be able to trap
 * the keyboard on screen.
 */
internal object ImeDictation {
    fun recordingActive(context: Context): Boolean {
        // Our minSdk is 26 but `activeRecordingConfigurations` only exists from
        // API 29 — without this gate every drag on Android 8/9 throws
        // NoSuchMethodError (swallowed below, but pointlessly). Upstream has a
        // higher minSdk, which is why its copy has no gate.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.activeRecordingConfigurations?.isNotEmpty() == true
        }.getOrDefault(false)
    }
}
