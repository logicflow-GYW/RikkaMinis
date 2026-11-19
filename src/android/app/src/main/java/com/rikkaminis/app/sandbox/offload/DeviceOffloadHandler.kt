package com.rikkaminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.rikkaminis.app.sandbox.NativeOffloadHandler
import com.rikkaminis.app.sandbox.NativeOffloadRequest
import com.rikkaminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import com.rikkaminis.app.data.repository.SkillTreeGuard

internal data class DeviceDirectorySize(val bytes: Long, val visited: Long, val truncated: Boolean, val errors: List<String>)

/** No FOLLOW_LINKS: guest-created links must never expand the accounting tree. */
internal fun deviceDirectorySize(
    dir: File,
    maxEntries: Long = FastioOffloadHandler.DEFAULT_MAX_ENTRIES,
    maxDepth: Int = SkillTreeGuard.MAX_DEPTH,
): DeviceDirectorySize {
    var bytes = 0L
    var visited = 0L
    var truncated = false
    val errors = ArrayList<String>()
    if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return DeviceDirectorySize(0, 0, false, emptyList())
    fun charge(): Boolean {
        if (visited >= maxEntries) { truncated = true; return false }
        visited++
        return true
    }
    try {
        Files.walkFileTree(dir.toPath(), emptySet<FileVisitOption>(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (charge()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!charge()) return FileVisitResult.TERMINATE
                if (attrs.isDirectory) truncated = true // depth boundary, never silently undercount
                if (attrs.isRegularFile) bytes += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult {
                if (!charge()) return FileVisitResult.TERMINATE
                errors.add("${path.fileName}: ${exc.javaClass.simpleName}")
                return FileVisitResult.CONTINUE
            }
        })
    } catch (e: IOException) { errors.add(e.javaClass.simpleName) }
    return DeviceDirectorySize(bytes, visited, truncated, errors)
}

/**
 * android-device — device model, OS version, battery, and storage info.
 *
 * Usage:
 *   android-device                Same as `all`
 *   android-device info
 *   android-device battery
 *   android-device storage
 *   android-device all
 */
class DeviceOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)

        val sub = args.positional.firstOrNull() ?: "all"
        return try {
            val out = when (sub) {
                "info" -> deviceInfo().toString(2)
                "battery" -> batteryInfo().toString(2)
                "storage" -> storageInfo().toString(2)
                "all" -> JSONObject()
                    .put("device", deviceInfo())
                    .put("battery", batteryInfo())
                    .put("storage", storageInfo())
                    .toString(2)
                else -> return NativeOffloadResult(2, "android-device: unknown subcommand '$sub'\n$HELP")
            }
            NativeOffloadResult(0, OffloadOutput.formatBody(out, args) + "\n")
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "internal")
                .put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun String?.orFallback(alt: String): String =
        this?.takeIf { it.isNotBlank() && it != "unknown" } ?: alt

    private fun deviceInfo(): JSONObject {
        val runtime = Runtime.getRuntime()
        val manufacturer = Build.MANUFACTURER.orFallback(Build.BRAND.orFallback("unknown"))
        val model = Build.MODEL.orFallback(Build.DEVICE.orFallback(Build.PRODUCT.orFallback("unknown")))
        return JSONObject()
            .put("manufacturer", manufacturer)
            .put("model", model)
            .put("brand", Build.BRAND.orFallback("unknown"))
            .put("device", Build.DEVICE.orFallback("unknown"))
            .put("product", Build.PRODUCT.orFallback("unknown"))
            .put("android_version", Build.VERSION.RELEASE.orFallback("unknown"))
            .put("sdk_level", Build.VERSION.SDK_INT)
            .put("security_patch", Build.VERSION.SECURITY_PATCH.orFallback("unknown"))
            .put("board", Build.BOARD.orFallback("unknown"))
            .put("hardware", Build.HARDWARE.orFallback("unknown"))
            .put("supported_abis", runCatching { Build.SUPPORTED_ABIS.joinToString(", ") }.getOrDefault(""))
            .put("available_processors", runtime.availableProcessors())
            .put("total_memory_mb", runtime.totalMemory() / (1024 * 1024))
            .put("free_memory_mb", runtime.freeMemory() / (1024 * 1024))
            .put("max_memory_mb", runtime.maxMemory() / (1024 * 1024))
    }

    private fun batteryInfo(): JSONObject {
        val json = JSONObject()
        val bi = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return json.put("error", "Battery info unavailable")

        val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        json.put("level_percent", pct)

        val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        json.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        json.put("status", when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        })
        json.put("power_source", when (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        })
        val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (temp > 0) {
            val c = temp / 10.0
            // Clamp to plausible range; devices that report in K or in raw °C
            // would otherwise surface a wildly wrong number.
            if (c in -20.0..80.0) json.put("temperature_celsius", c)
            else json.put("temperature_celsius_raw", temp)
        }
        json.put("health", when (bi.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        })
        return json
    }

    private fun storageInfo(): JSONObject {
        val json = JSONObject()
        try {
            val s = StatFs(Environment.getDataDirectory().path)
            val total = s.totalBytes
            val free = s.availableBytes
            json.put("internal_total_gb", String.format("%.1f", total / 1e9))
                .put("internal_free_gb", String.format("%.1f", free / 1e9))
                .put("internal_used_gb", String.format("%.1f", (total - free) / 1e9))
                .put("note", "Active partition only; Huawei PrivateSpace / Xiaomi Second Space figures reflect the currently unlocked container.")
        } catch (e: Throwable) {
            json.put("error", e.message ?: "StatFs unavailable")
        }
        val dataSize = deviceDirectorySize(context.filesDir)
        val cacheSize = deviceDirectorySize(context.cacheDir)
        json.put("app_data_mb", String.format("%.1f", dataSize.bytes / 1e6))
            .put("app_cache_mb", String.format("%.1f", cacheSize.bytes / 1e6))
        for ((name, result) in listOf("app_data" to dataSize, "app_cache" to cacheSize)) {
            if (result.truncated || result.errors.isNotEmpty()) {
                json.put("${name}_partial", true)
                json.put("${name}_visited", result.visited)
                if (result.errors.isNotEmpty()) json.put("${name}_errors", org.json.JSONArray(result.errors))
            }
        }
        return json
    }

    companion object {
        private const val HELP = """android-device — device model, OS, battery, storage (JSON)

Usage:
  android-device [all]       Everything (default)
  android-device info        Model, OS, memory
  android-device battery     Battery level, charging state, temperature
  android-device storage     Internal storage, app data/cache size
"""
    }
}
