package com.rikkaminis.app.sandbox

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * [feat/host-status-snapshot] Publishes the host's physical state into the guest
 * rootfs at `/run/minis-host-status.json` so sandbox scripts can answer
 * "how much battery / storage / am I online" with a plain `cat` instead of a
 * NativeOffload RPC round-trip (or a recursive walk of the rootfs).
 *
 * Shape (schema 1):
 * ```
 * { "schema": 1, "updated_at": "2026-11-01T02:33:12.345Z",
 *   "battery": { "pct": 87, "charging": true, "temp_c": 31.2 },
 *   "storage": { "free_bytes": 123, "total_bytes": 456 },
 *   "network": "wifi", "doze": false, "interactive": true, "uptime_ms": 1234 }
 * ```
 * Unavailable readings are written as `null` — never as a fabricated default.
 *
 * Two deliberate simplifications (mirroring the upstream HostStatusPublisher):
 * - `ponytail: storage reads StatFs only | 天花板: per-directory usage is not
 *   reported | 升级触发: a caller needs "how big is the rootfs" rather than
 *   "how free is the disk" — then add a *bounded* scanner, never a recursive
 *   dirSize on the caller's thread (recursive walks are what froze the phone
 *   upstream).`
 * - `ponytail: refresh is a 30 s heartbeat, no broadcast receivers | 天花板:
 *   a state change can be up to 30 s stale | 升级触发: a consumer needs
 *   sub-second freshness (e.g. a guest script polling "am I charging") — then
 *   register ACTION_BATTERY_CHANGED / CONNECTIVITY_ACTION in the host layer,
 *   not by shortening the interval.`
 */
// ---------------------------------------------------------------------------
// pure layer — JVM-testable, no Android imports below this marker until the
// "android layer" marker. `HostStatusSnapshotTest` compiles this region as-is.
// ---------------------------------------------------------------------------

/** Bumped when the JSON shape changes in a way consumers must notice. */
internal const val HOST_STATUS_SCHEMA = 1

/** Guest-visible location, relative to the rootfs root. */
internal const val HOST_STATUS_RELATIVE_PATH = "run/minis-host-status.json"

/**
 * Raw readings collected from the platform. Every field is nullable on purpose:
 * a missing sensor must serialize as `null` rather than as a plausible-looking
 * zero that a guest script would act on.
 */
internal data class StatusInputs(
    val batteryPct: Int?,
    val charging: Boolean?,
    val batteryTempC: Double?,
    val storageFreeBytes: Long?,
    val storageTotalBytes: Long?,
    val network: String?,
    val doze: Boolean?,
    val interactive: Boolean?,
    val uptimeMs: Long,
)

/**
 * ISO-8601 UTC with a fixed 3-digit millisecond part. `Instant.toString()`
 * drops the fraction when it is exactly zero, which would hand consumers two
 * different formats for the same field; a fixed pattern avoids that.
 * DateTimeFormatter is immutable and thread-safe.
 */
private val statusTimestampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).withZone(ZoneOffset.UTC)

/** Renders [i] as the documented JSON shape. Pure: no I/O, no clock read. */
internal fun buildStatusJson(i: StatusInputs, nowMs: Long): String {
    val battery = JSONObject()
        .put("pct", i.batteryPct ?: JSONObject.NULL)
        .put("charging", i.charging ?: JSONObject.NULL)
        .put("temp_c", i.batteryTempC ?: JSONObject.NULL)
    val storage = JSONObject()
        .put("free_bytes", i.storageFreeBytes ?: JSONObject.NULL)
        .put("total_bytes", i.storageTotalBytes ?: JSONObject.NULL)
    return JSONObject()
        .put("schema", HOST_STATUS_SCHEMA)
        .put("updated_at", statusTimestampFormat.format(Instant.ofEpochMilli(nowMs)))
        .put("battery", battery)
        .put("storage", storage)
        .put("network", i.network ?: JSONObject.NULL)
        .put("doze", i.doze ?: JSONObject.NULL)
        .put("interactive", i.interactive ?: JSONObject.NULL)
        .put("uptime_ms", i.uptimeMs)
        .toString(2)
}

// ---------------------------------------------------------------------------
// android layer — collection, atomic write, heartbeat.
// ---------------------------------------------------------------------------

/**
 * Process-lifetime publisher. [start] is idempotent: calling it again with a
 * different rootfs directory (rootfs reinstall / restore) cancels the previous
 * heartbeat and restarts against the new one, which is also what makes a
 * rootfs that is still being extracted self-heal — [writeOnce] no-ops until the
 * directory exists and the next heartbeat retries.
 */
object HostStatusSnapshot {

    private const val TAG = "HostStatusSnapshot"

    /**
     * Refresh cadence. Picked over an event-driven design (broadcast receivers)
     * because the readers are agents running shell commands seconds-to-minutes
     * apart, so 30 s of staleness is invisible; a receiver would add a
     * manifest-visible registration surface for no observable gain.
     */
    private const val HEARTBEAT_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null

    @Volatile
    private var currentRootfs: File? = null

    /** Last failure message; repeated identical failures are logged once. */
    @Volatile
    private var lastLoggedFailure: String? = null

    /** Guest path of the published file, for logging / diagnostics. */
    fun guestPath(): String = "/$HOST_STATUS_RELATIVE_PATH"

    /**
     * Starts (or restarts) the publisher. Never blocks the caller and never
     * throws: the first write happens on the IO dispatcher, not on this thread.
     */
    fun start(context: Context, rootfsDir: File) {
        val app = context.applicationContext
        synchronized(lock) {
            job?.cancel()
            currentRootfs = rootfsDir
            job = scope.launch { heartbeatLoop(app) }
        }
    }

    /** Cancels the heartbeat. Idempotent; safe to call from any thread. */
    fun stop() {
        val previous: Job?
        synchronized(lock) {
            previous = job
            job = null
            currentRootfs = null
        }
        previous?.cancel()
    }

    private suspend fun CoroutineScope.heartbeatLoop(app: Context) {
        // First write immediately (already off the caller's thread), then beat.
        currentRootfs?.let { writeOnce(app, it) }
        while (isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            val rootfs = currentRootfs ?: continue
            writeOnce(app, rootfs)
        }
    }

    /**
     * Collects and publishes one snapshot. Silently no-ops while the rootfs does
     * not exist yet (install in progress) and swallows every failure into a
     * single log line — a status file must never break the app.
     */
    fun writeOnce(context: Context, rootfsDir: File) {
        try {
            if (!rootfsDir.isDirectory) return
            val json = buildStatusJson(collectInputs(context, rootfsDir), System.currentTimeMillis())
            val runDir = File(rootfsDir, "run")
            if (!runDir.isDirectory && !runDir.mkdirs() && !runDir.isDirectory) {
                logFailure("cannot create ${runDir.absolutePath}")
                return
            }
            val target = File(runDir, File(HOST_STATUS_RELATIVE_PATH).name)
            val tmp = File(runDir, "${target.name}.tmp")
            tmp.writeText(json)
            // Preserve the read-only-for-others shape the guest expects: 0644.
            tmp.setReadable(true, false)
            tmp.setWritable(true, true)
            if (!tmp.renameTo(target)) {
                // Same-filesystem rename is atomic; if the platform refuses it
                // (rare), fall back to an in-place write so the file still updates.
                target.writeText(json)
                target.setReadable(true, false)
                tmp.delete()
            }
            lastLoggedFailure = null
        } catch (t: Throwable) {
            logFailure("write failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logFailure(message: String) {
        if (lastLoggedFailure == message) return
        lastLoggedFailure = message
        runCatching { AppLogger.warning(TAG, message) }
    }

    private fun collectInputs(context: Context, rootfsDir: File): StatusInputs {
        var batteryPct: Int? = null
        var charging: Boolean? = null
        var tempC: Double? = null
        try {
            // Sticky broadcast query — reads the cached battery intent without
            // registering a receiver and without any permission.
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) {
                val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryPct = ((level * 100.0) / scale).roundToInt().coerceIn(0, 100)
                }
                charging = when (sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING,
                    BatteryManager.BATTERY_STATUS_FULL,
                    -> true
                    BatteryManager.BATTERY_STATUS_DISCHARGING,
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                    -> false
                    else -> null
                }
                val tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tenths != Int.MIN_VALUE) {
                    val celsius = tenths / 10.0
                    // Vendor readings outside the physical range are sensor
                    // garbage, not temperature: report null rather than a value
                    // a script might threshold on.
                    if (celsius in -30.0..100.0) tempC = celsius
                }
            }
        } catch (t: Throwable) {
            logFailure("battery read failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        var freeBytes: Long? = null
        var totalBytes: Long? = null
        try {
            // StatFs on the app data partition — O(1). Never a recursive dirSize:
            // walking the rootfs on every beat is what froze devices upstream.
            val base = rootfsDir.parentFile ?: Environment.getDataDirectory()
            val stat = StatFs(base.absolutePath)
            freeBytes = stat.availableBytes
            totalBytes = stat.totalBytes
        } catch (t: Throwable) {
            logFailure("storage read failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        var doze: Boolean? = null
        var interactive: Boolean? = null
        try {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power != null) {
                doze = power.isDeviceIdleMode
                interactive = power.isInteractive
            }
        } catch (t: Throwable) {
            logFailure("power read failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        val uptimeMs = try {
            SystemClock.elapsedRealtime()
        } catch (t: Throwable) {
            logFailure("uptime read failed: ${t.javaClass.simpleName}: ${t.message}")
            0L
        }

        return StatusInputs(
            batteryPct = batteryPct,
            charging = charging,
            batteryTempC = tempC,
            storageFreeBytes = freeBytes,
            storageTotalBytes = totalBytes,
            network = networkKind(context),
            doze = doze,
            interactive = interactive,
            uptimeMs = uptimeMs,
        )
    }

    /**
     * Only the three classifications the schema allows. A connected transport we
     * cannot name (ethernet, VPN-only, bluetooth) maps to "unknown" — the file
     * describes *kind of uplink*, not *is the internet reachable*.
     */
    private fun networkKind(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (manager == null) {
                "unknown"
            } else {
                val active = manager.activeNetwork
                if (active == null) {
                    "none"
                } else {
                    val capabilities = manager.getNetworkCapabilities(active)
                    when {
                        capabilities == null -> "unknown"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                        else -> "unknown"
                    }
                }
            }
        } catch (t: Throwable) {
            logFailure("network read failed: ${t.javaClass.simpleName}: ${t.message}")
            "unknown"
        }
    }
}
