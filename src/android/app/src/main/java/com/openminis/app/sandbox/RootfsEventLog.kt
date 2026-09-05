package com.openminis.app.sandbox

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-rootfs-event-log] Append-only event log for rootfs lifecycle events,
 * written to the HOST side (`filesDir/logs/rootfs-events.log`) so entries
 * survive any guest-rootfs wipe.
 *
 * Why: autoRepair's Stage 3 full reset and the APK-world restore used to
 * only write to logcat, which rotates within minutes — a rootfs that came
 * back "fresh" left no trace of when/why it was wiped. Users and agents
 * then misattributed the loss to phantom "sandbox resets". This log is the
 * durable ground truth for that question.
 *
 * Companion file `rootfs-boot-id` records the generation counter of the
 * last completed install: a fresh extraction always bumps it, so even if
 * the event log itself is ever lost, a changed boot id still proves that a
 * wipe happened (and roughly when, via the file's mtime).
 *
 * Pure JVM (no Android imports) so the formatting logic is unit-testable
 * in the sandbox; I/O failures are swallowed by design — the event log is
 * diagnostics, never allowed to break the rootfs operation it observes.
 */
object RootfsEventLog {

    private const val FILE_NAME = "rootfs-events.log"
    private const val BOOT_ID_NAME = "rootfs-boot-id"
    private const val MAX_BYTES = 128L * 1024L

    /** Event kinds. Kept as strings in output for grep-ability. */
    object Events {
        const val INSTALL = "INSTALL"
        const val STAGE3_RESET = "REPAIR_STAGE3_RESET"
        const val MANUAL_RESET = "MANUAL_RESET"
        const val APKWORLD_RESTORE = "APKWORLD_RESTORE"
        const val APKWORLD_RETRY = "APKWORLD_RETRY"
        const val AUTO_REPAIR = "AUTO_REPAIR"
    }

    /**
     * Format one event line. internal so tests can assert exact
     * output; timestamp is ISO-local like LaunchCycleBeacon's entries.
     */
    internal fun formatLine(event: String, detail: String, atMs: Long): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(atMs))
        val d = detail.trim()
        return if (d.isEmpty()) "[$ts] $event" else "[$ts] $event $d"
    }

    /** Append an event; never throws. */
    fun logEvent(logsDir: File, event: String, detail: String = "", atMs: Long = System.currentTimeMillis()) {
        try {
            logsDir.mkdirs()
            val file = File(logsDir, FILE_NAME)
            // Simple size guard: keep the tail (diagnostics only).
            if (file.length() > MAX_BYTES) {
                val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText(keep)
            }
            file.appendText(formatLine(event, detail, atMs) + "\n")
        } catch (_: Throwable) {
            // Diagnostics must never break the operation being observed.
        }
    }

    /**
     * Generation counter of the last completed rootfs install. Callers
     * persist it on every successful install; a changed value between two
     * reads proves the rootfs was wiped and re-extracted in between.
     */
    fun readBootId(rootfsDir: File): Long = try {
        File(rootfsDir, BOOT_ID_NAME).readText().trim().toLongOrNull() ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /** Persist the boot id after a successful install; never throws. */
    fun writeBootId(rootfsDir: File, id: Long) {
        try {
            File(rootfsDir, BOOT_ID_NAME).writeText(id.toString())
        } catch (_: Throwable) {
        }
    }
}
