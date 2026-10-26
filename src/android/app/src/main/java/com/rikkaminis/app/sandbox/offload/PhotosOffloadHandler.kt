package com.rikkaminis.app.sandbox.offload

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.offload.OffloadPermissionManager
import com.rikkaminis.app.sandbox.GuestPathMapper
import com.rikkaminis.app.sandbox.NativeOffloadHandler
import com.rikkaminis.app.sandbox.NativeOffloadRequest
import com.rikkaminis.app.sandbox.NativeOffloadResult
import com.rikkaminis.app.sandbox.PRootKernel
import com.rikkaminis.app.ui.settings.readAtMost
import com.rikkaminis.app.util.ImageDecoding
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [dv18-A A-03] What `--id` / `--name` name, decided once.
 *
 * `album` reads two selectors that mean different SQL: `--id` is an exact bucket
 * id, `--name` is a case-insensitive contains match. Reading them separately
 * (`getLong("id")` then `get("name")`) made three bad inputs answer a question
 * the caller never asked:
 *
 *   - `--id abc --name Foo` → `getLong` answers null for a non-number, so the
 *     name branch ran: the caller asked for bucket `abc` and got a name search;
 *   - `--id abc` alone → "required", which is false — the flag WAS given;
 *   - `--name ""` → `LIKE '%%'`, i.e. the entire library, exit 0.
 *
 * [AlbumSelector.Unreadable] is the third answer the old code had no slot for:
 * a flag that was supplied but names nothing is REFUSED (exit 2), never guessed
 * — the same discipline [CalendarSelector] applies to `--calendar-id` and
 * [SendDisposition] to `--after`.
 */
internal sealed class AlbumSelector {
    /** Neither flag was supplied. */
    object Absent : AlbumSelector()

    /** `--id <n>`: an exact bucket id. */
    data class ById(val id: Long) : AlbumSelector()

    /** `--name <text>`: a non-blank name to match. */
    data class ByName(val name: String) : AlbumSelector()

    /**
     * A supplied flag that names nothing: `--id abc` (not a number), or
     * `--name ""` / `--name " "` (an empty pattern matches everything).
     * [flag] and [raw] exist so the refusal can quote what was actually typed.
     */
    data class Unreadable(val flag: String, val raw: String) : AlbumSelector()
}

/**
 * [dv18-A A-03] Pure form of the `album` selector decision (no Context). The
 * name is NOT trimmed: a blank name is refused, a padded one keeps its exact
 * pattern (only the "matches everything" case was the defect).
 */
internal fun resolveAlbumSelector(rawId: String?, rawName: String?): AlbumSelector {
    if (rawId != null) {
        val id = rawId.toLongOrNull()
        return if (id != null) AlbumSelector.ById(id) else AlbumSelector.Unreadable("--id", rawId)
    }
    if (rawName != null) {
        return if (rawName.isNotBlank()) AlbumSelector.ByName(rawName)
        else AlbumSelector.Unreadable("--name", rawName)
    }
    return AlbumSelector.Absent
}

/**
 * [dv18-A A-03] The one refusal message for a supplied-but-useless selector, so
 * both losing shapes name the flag the caller actually typed.
 */
internal fun albumSelectorRefusal(selector: AlbumSelector.Unreadable): String =
    if (selector.flag == "--id") {
        "android-photos album: --id '${selector.raw}' is not a bucket id (numbers only)\n"
    } else {
        "android-photos album: --name must not be blank (an empty pattern matches every photo)\n"
    }

/**
 * android-photos — query and manage the device photo library via MediaStore.
 *
 * Mirrors apple-photos (NativeOffloads/PhotosOffload.m) — same subcommand
 * names and flag conventions so prompts and the agent loop are platform-
 * agnostic. Some iOS concepts have no native Android equivalent (smart
 * albums; stable per-asset string identifiers); these are documented at
 * each subcommand.
 *
 * Usage:
 *   android-photos list [--type photo|video|all] [--start ISO --end ISO | --days N]
 *                       [--limit N]
 *   android-photos stats
 *   android-photos near --lat L --lon N [--radius KM] [--limit N]
 *   android-photos albums [--type user|smart|all]
 *   android-photos album [--id <bucket_id> | --name <bucket_name>] [--limit N]
 *   android-photos export --id <id> [--size thumb|medium|original]
 *   android-photos import --path <file> [--album-name <name>]
 *   android-photos create-album --name <name>
 *   android-photos favorite --id <id>
 *   android-photos delete --ids <id1,id2,...> --confirm
 *
 * Backwards compatibility:
 *   - `near <lat> <lon>` legacy positional form still works.
 *   - `--max` is accepted as an alias for `--limit`.
 *   - A `--limit` that is not an integer, or is outside the subcommand's
 *     documented range (list/near 1..100, album 1..200), is a usage error
 *     (exit 2): it is never clamped and never replaced by the default.
 */
class PhotosOffloadHandler(private val context: Context) : NativeOffloadHandler {
    // [sweep1014-B22b] SimpleDateFormat is not thread-safe (its internal
    // Calendar is clobbered under concurrent access) and NativeOffloadServer
    // dispatches to this handler from a thread pool. Replace the shared
    // instance with a thread-safe java.time formatter (minSdk 26 ≥ API 26).
    private val dateTimeFormat =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun formatDateTime(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(dateTimeFormat)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("confirm"))
        // `--help` is a flag, so `positional` is empty for it too: the exit
        // code has to be decided on the flag, or `--help` answers 2.
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.hasFlag("h", "help")) 0 else 2, HELP)
        }
        // T330: tri-state agent gate before requesting media permissions.
        OffloadGate.enforce("photos", "android-photos", args, request)?.let { return it }

        ensureMediaPermission(args)?.let { return it }

        return try {
            when (val sub = args.positional[0]) {
                "list" -> handleList(args)
                "stats" -> NativeOffloadResult(0, OffloadOutput.formatBody(photoStats(), args) + "\n")
                "near" -> handleNear(args)
                "albums" -> handleAlbums(args)
                "album" -> handleAlbum(args)
                "export" -> handleExport(args)
                "import", "save" -> handleImport(args, request)
                "create-album" -> handleCreateAlbum(args)
                "favorite" -> handleFavorite(args)
                "delete" -> handleDelete(args)
                else -> NativeOffloadResult(2, "android-photos: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "SecurityException: ${e.message}")
            val body = JSONObject().put("error", "mediastore_blocked")
                .put("message", "Media access refused by the system. ${permissionHint()}. Underlying: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = JSONObject().put("error", "mediastore_error")
                .put("message", e.message ?: "MediaStore query failed")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── list (T59: + --type, --start, --end, --days, --limit) ────────────

    private fun handleList(args: OffloadArgs): NativeOffloadResult {
        // [dv29-A3 A-05] Shared page-size contract: `--limit 0` used to be
        // clamped to 1 row and `--limit abc` to the default 20, both at exit 0.
        val limit = when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.PHOTOS)) {
            is PageSizeOutcome.Ok -> size.value
            is PageSizeOutcome.Invalid ->
                return NativeOffloadResult(2, "android-photos list: ${size.message}\n")
        }
        val type = (args.get("type") ?: "all").lowercase()
        if (type !in setOf("photo", "video", "all")) {
            return NativeOffloadResult(2, "android-photos list: --type must be photo|video|all\n")
        }
        // [da1032-C] ISSUE-04: a supplied-but-unreadable `--start`/`--end` (or
        // an inverted window) refuses the command. It used to resolve to a null
        // bound → "no filter" → the command answered the entire library.
        val range = when (val r = resolveDateRange(args)) {
            is PhotoRange.Window -> r
            is PhotoRange.Invalid ->
                return NativeOffloadResult(2, "android-photos list: invalid ${r.flag} '${r.raw}'\n")
        }
        val startMs = range.startMs
        val endMs = range.endMs
        AppLogger.info(TAG, "list: type=$type limit=$limit range=${startMs?.let { formatIso(it) }}..${endMs?.let { formatIso(it) }}")
        // [sweep1014-B22a] Query photos and videos independently (each capped
        // at [limit]) and merge by time in memory. The old shared-quota form
        // (limit - arr.length()) starved the video query once ≥20 photos
        // existed, and even below the cap produced photo-first output instead
        // of a time-ordered mix.
        val photos = if (type == "photo" || type == "all") queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo", startMs, endMs, limit) else emptyList()
        val videos = if (type == "video" || type == "all") queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", startMs, endMs, limit) else emptyList()
        val arr = JSONArray()
        mergeMediaLimit(photos, videos, limit).forEach { arr.put(it.json) }
        val data = JSONObject()
            .put("media", arr)
            .put("count", arr.length())
            .put("type", type)
        if (startMs != null) data.put("range_start", formatIso(startMs))
        if (endMs != null) data.put("range_end", formatIso(endMs))
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    /**
     * MediaStore.Images and MediaStore.Video share the same column names for
     * the fields we care about, so one helper handles both. DATE_TAKEN is
     * milliseconds since epoch; we filter on it when --start/--end/--days
     * supply a window, and fall back to the (cheaper) implicit "all rows"
     * query when no range is set.
     */
    private fun queryMedia(uri: Uri, kind: String, startMs: Long?, endMs: Long?, max: Int): List<MediaEntry> {
        if (max <= 0) return emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if (startMs != null) {
            selectionParts.add("${MediaStore.Images.Media.DATE_TAKEN} >= ?")
            selectionArgs.add(startMs.toString())
        }
        if (endMs != null) {
            selectionParts.add("${MediaStore.Images.Media.DATE_TAKEN} <= ?")
            selectionArgs.add(endMs.toString())
        }
        val selection = if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND ")
        val selArgs = if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray()
        val out = mutableListOf<MediaEntry>()
        context.contentResolver.query(uri, projection, selection, selArgs, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n < max) {
                val p = JSONObject()
                    .put("id", c.getLong(0))
                    .put("name", c.getString(1) ?: "")
                    .put("media_type", kind)
                val dt = c.getLong(2)
                if (dt > 0) p.put("date", formatDateTime(dt)).put("date_iso", formatIso(dt))
                p.put("width", c.getInt(3))
                    .put("height", c.getInt(4))
                    .put("size_bytes", c.getLong(5))
                    .put("mime_type", c.getString(6) ?: "")
                    .put("bucket_id", c.getLong(7))
                    .put("bucket_name", c.getString(8) ?: "")
                out.add(MediaEntry(dt, p))
                n++
            }
        }
        return out
    }

    // ── near (T59: + --lat/--lon flags + --limit) ────────────────────────

    private fun handleNear(args: OffloadArgs): NativeOffloadResult {
        // Flag form (--lat/--lon, mirrors apple-photos near) preferred;
        // legacy positional <lat> <lon> kept as alias.
        val lat = args.getDouble("lat") ?: args.positional.getOrNull(1)?.toDoubleOrNull()
        val lon = args.getDouble("lon", "lng") ?: args.positional.getOrNull(2)?.toDoubleOrNull()
        if (lat == null || lon == null) {
            return NativeOffloadResult(
                2,
                "android-photos near: --lat <lat> --lon <lon> required (positional <lat> <lon> also accepted)\n",
            )
        }
        ensureMediaLocationPermission(args)?.let { return it }
        val radius = args.getDouble("radius") ?: 1.0
        // [dv29-A3 A-05] Same contract as `list` — an empty "near" page used to
        // be indistinguishable from "no photos around this point".
        val max = when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.PHOTOS)) {
            is PageSizeOutcome.Ok -> size.value
            is PageSizeOutcome.Invalid ->
                return NativeOffloadResult(2, "android-photos near: ${size.message}\n")
        }
        AppLogger.info(TAG, "near: lat=$lat lon=$lon radius=${radius}km max=$max")
        return NativeOffloadResult(0, OffloadOutput.formatBody(nearPhotos(lat, lon, radius, max), args) + "\n")
    }

    private fun hasMediaPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) return true
        // [da1034-A3] ISSUE-06: A13+ gates each media class separately and the
        // check above asks about images only, so a user who granted ONLY
        // READ_MEDIA_VIDEO was refused every video subcommand with a hint to go
        // grant the photos permission they had already granted. Either class
        // satisfies the gate; the queries themselves stay filtered by the
        // system (an ungranted collection reads back empty rows, not an error).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
        ) return true
        // A14+ partial access counts
        if (Build.VERSION.SDK_INT >= 34) {
            val partial = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            if (ContextCompat.checkSelfPermission(context, partial) == PackageManager.PERMISSION_GRANTED) return true
        }
        return false
    }

    private fun permissionHint(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "READ_MEDIA_IMAGES (or partial READ_MEDIA_VISUAL_USER_SELECTED on A14+) not granted. Ask the user to grant Photos/Media permission in Android settings."
        } else {
            "READ_EXTERNAL_STORAGE not granted. Ask the user to grant Storage permission in Android settings."
        }
    }

    /**
     * Drive the system-dialog → settings-gate flow. On A13+ we ask for the
     * granular media permissions; A14+ treats visual-user-selected as partial
     * access, which [hasMediaPermission] already accepts.
     */
    private fun ensureMediaPermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasMediaPermission()) return null
        AppLogger.warning(TAG, "media permission not granted — routing through permission flow")
        val requestList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                // [da1034-A3] ISSUE-06: ask for the video class too. Without it
                // the dialog could never produce a grant that the video path
                // accepts, so re-granting "Photos" left clips invisible.
                add(Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= 34) add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            }
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(requestList)
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasMediaPermission() })
            ) {
                AppLogger.info(TAG, "Photos permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = "photos_media",
                        title = "Photos permission needed",
                        message = "RikkaMinis needs media permission to read your photo library. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasMediaPermission() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "permission_denied")
                        .put("message", "The user declined the photos/media permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the photos/media permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun hasMediaLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, "android.permission.ACCESS_MEDIA_LOCATION",
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * `near` reads GPS EXIF; without ACCESS_MEDIA_LOCATION the MediaStore
     * strips it from the streams we open and the result set is always empty.
     * On A10+ it's a runtime permission — manifest entry alone is not enough.
     * Pre-Q the permission doesn't exist; treat it as granted.
     */
    private fun ensureMediaLocationPermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasMediaLocationPermission()) return null
        AppLogger.warning(TAG, "ACCESS_MEDIA_LOCATION not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(
                listOf("android.permission.ACCESS_MEDIA_LOCATION"),
            )
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasMediaLocationPermission() })
            ) {
                AppLogger.info(TAG, "Media location permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = "ACCESS_MEDIA_LOCATION",
                        title = "Photo location needed",
                        message = "RikkaMinis needs photo-location permission to read GPS EXIF for the `near` query. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasMediaLocationPermission() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "permission_denied")
                        .put("message", "The user declined the photo-location permission. Without it, GPS EXIF is redacted and `near` cannot match any photos.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the photo-location permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    // ── albums (T64) ─────────────────────────────────────────────────────

    /**
     * List buckets (Android's equivalent of iOS PHAssetCollection — every
     * media row carries BUCKET_ID/BUCKET_DISPLAY_NAME). MediaStore has no
     * native "smart album" concept, so apple-photos's user/smart distinction
     * collapses on Android: --type smart returns an empty list with a
     * structured note; --type user and --type all are equivalent.
     */
    private fun handleAlbums(args: OffloadArgs): NativeOffloadResult {
        val type = (args.get("type") ?: "all").lowercase()
        if (type !in setOf("user", "smart", "all")) {
            return NativeOffloadResult(2, "android-photos albums: --type must be user|smart|all\n")
        }
        if (type == "smart") {
            val data = JSONObject()
                .put("albums", JSONArray())
                .put("count", 0)
                .put("note", "MediaStore has no smart-album concept on Android. Use --type user or --type all to list buckets.")
            return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        }
        val items = JSONArray()
        // Aggregate over the IMAGES table; videos share the same bucket
        // namespace so we union by id afterwards.
        val seen = mutableSetOf<Long>()
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .forEach { uri ->
                val proj = arrayOf(
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                )
                context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val bid = c.getLong(0)
                        if (bid == 0L || bid in seen) continue
                        seen.add(bid)
                        val name = c.getString(1) ?: ""
                        items.put(JSONObject().put("id", bid).put("name", name).put("type", "user"))
                    }
                }
            }
        AppLogger.info(TAG, "albums: type=$type count=${items.length()}")
        val data = JSONObject().put("albums", items).put("count", items.length())
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    /**
     * List media inside one bucket. --id (Long) wins; --name does
     * case-insensitive contains lookup, mirroring apple-photos. Returns
     * the same shape as `list` so downstream tooling can treat them the
     * same.
     *
     * [dv18-A A-03] The selector decision is [resolveAlbumSelector]'s, so a
     * non-numeric `--id` and a blank `--name` are refused (exit 2) instead of
     * downgrading to the other branch.
     */
    private fun handleAlbum(args: OffloadArgs): NativeOffloadResult {
        // [dv18-A A-03] ONE decision for both selectors: `--id` that is not a
        // number and `--name` that is blank are REFUSED here (exit 2) instead of
        // falling through to the name branch / the `LIKE '%%'` full-library scan.
        val selection: String
        val selArgs: Array<String>
        val bucketId: Long?
        val bucketName: String?
        when (val selector = resolveAlbumSelector(args.get("id"), args.get("name"))) {
            is AlbumSelector.ById -> {
                bucketId = selector.id
                bucketName = null
                selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
                selArgs = arrayOf(selector.id.toString())
            }
            is AlbumSelector.ByName -> {
                bucketId = null
                bucketName = selector.name
                selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"
                selArgs = arrayOf("%${selector.name}%")
            }
            is AlbumSelector.Unreadable -> return NativeOffloadResult(2, albumSelectorRefusal(selector))
            AlbumSelector.Absent -> return NativeOffloadResult(
                2,
                "android-photos album: --id <bucket_id> or --name <bucket_name> is required\n",
            )
        }
        val limit = when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.PHOTOS_ALBUM)) {
            is PageSizeOutcome.Ok -> size.value
            is PageSizeOutcome.Invalid ->
                return NativeOffloadResult(2, "android-photos album: ${size.message}\n")
        }
        // [sweep1014-B22a-parity] Query photos and videos independently (each
        // capped at [limit]) and merge with the shared mergeMediaLimit, same
        // shape as handleList. The old shared-quota form (photos fill the
        // limit first, leftover quota goes to videos) starved album videos
        // once the album held ≥limit photos. The inline projection stays:
        // queryMedia hides selection behind the start/end filters.
        // [da1032-C] ISSUE-04: the projection must carry WIDTH/HEIGHT like
        // queryMedia does — the album rows were the only media surface without
        // dimensions (width/height came back absent, i.e. 0 to the caller).
        // Column order is an implicit contract with the positional cursor
        // reads below; the indices follow the list.
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        fun queryBucket(uri: Uri, kind: String): List<MediaEntry> {
            val out = mutableListOf<MediaEntry>()
            context.contentResolver.query(uri, proj, selection, selArgs, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val p = JSONObject()
                        .put("id", c.getLong(0))
                        .put("name", c.getString(1) ?: "")
                        .put("media_type", kind)
                    val dt = c.getLong(2)
                    if (dt > 0) p.put("date", formatDateTime(dt)).put("date_iso", formatIso(dt))
                    p.put("width", c.getInt(3))
                        .put("height", c.getInt(4))
                        .put("size_bytes", c.getLong(5))
                        .put("mime_type", c.getString(6) ?: "")
                        .put("bucket_id", c.getLong(7))
                        .put("bucket_name", c.getString(8) ?: "")
                    out.add(MediaEntry(dt, p))
                }
            }
            return out
        }
        val photos = queryBucket(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo")
        val videos = queryBucket(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
        val arr = JSONArray()
        mergeMediaLimit(photos, videos, limit).forEach { arr.put(it.json) }
        AppLogger.info(TAG, "album: id=$bucketId name='$bucketName' count=${arr.length()}")
        val data = JSONObject()
            .put("media", arr)
            .put("count", arr.length())
            .put("album", JSONObject().apply {
                if (bucketId != null) put("id", bucketId)
                if (bucketName != null) put("name", bucketName)
            })
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── export (T64) ─────────────────────────────────────────────────────

    /**
     * Copy the asset bytes to the host filesystem at
     * `<filesDir>/photos-export/<id>_<safe-name>.<ext>` and report the
     * absolute host path. iOS uses the Linux-mounted `/var/minis/offloads/`
     * but Android's offloads dir is bind-mounted per session and the
     * handler doesn't see the session id — we report `host_path` instead
     * so the agent has a concrete location even if Linux-side access
     * requires copying through a shell tool.
     *
     * --size thumb / medium re-encode to JPEG at 256px / 1024px max edge
     * via BitmapFactory; original copies the resource bytes as-is.
     *
     * [da1034-A3] Video assets are ALWAYS a verbatim copy, whatever `--size`
     * says (BitmapFactory cannot decode video), so their output keeps the real
     * container extension, `format` reports `original`, and `size_note` records
     * that the requested size was ignored.
     */
    private fun handleExport(args: OffloadArgs): NativeOffloadResult {
        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-photos export: --id <asset_id> is required\n")
        val size = (args.get("size") ?: "original").lowercase()
        if (size !in setOf("thumb", "medium", "original")) {
            return NativeOffloadResult(2, "android-photos export: --size must be thumb|medium|original\n")
        }

        // Try Images first then Video.
        var srcUri: Uri? = null
        var mediaType = "photo"
        var mimeType = ""
        var displayName = "export"
        var width = 0
        var height = 0
        for ((uri, kind) in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "photo",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video",
        )) {
            val proj = arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
            )
            context.contentResolver.query(uri, proj, "${MediaStore.Images.Media._ID} = ?", arrayOf(id.toString()), null)?.use { c ->
                if (c.moveToFirst()) {
                    srcUri = ContentUris.withAppendedId(uri, id)
                    mediaType = kind
                    displayName = c.getString(0) ?: "export"
                    mimeType = c.getString(1) ?: ""
                    width = c.getInt(2); height = c.getInt(3)
                }
            }
            if (srcUri != null) break
        }
        val src = srcUri ?: return NativeOffloadResult(
            1,
            OffloadOutput.formatBody(
                JSONObject().put("error", "not_found").put("id", id)
                    .put("message", "No asset with id=$id (already deleted, or not visible to this app).")
                    .toString(),
                args,
            ) + "\n",
        )

        val outDir = File(context.filesDir, "photos-export").also { it.mkdirs() }
        // [da1028-C] filesDir, not cacheDir: nothing reclaims this dir, and the
        // export path had no prune point — a long-lived install grew it without
        // bound. Same 24h contract as cache/share. Handler runs on the offload
        // server's worker thread, so the dir walk is off the main thread.
        com.rikkaminis.app.ui.components.pruneShareDir(outDir)
        val safeName = exportSafeName(displayName)
        val ext = exportExt(displayName, mediaType)
        // [da1034-A3] ISSUE-05: a video is copied verbatim for EVERY --size
        // (BitmapFactory cannot decode video), so the output must not be named
        // `…_thumb_x.jpg` — that lied twice, in the size marker and in the
        // container, about bytes that are an untouched mp4.
        val copiesSourceBytes = exportCopiesSourceBytes(size, mediaType)
        val outFile = File(outDir, exportFileName(id, size, mediaType, safeName, ext))

        return try {
            val bytesWritten = if (copiesSourceBytes) {
                copyStream(src, outFile)
            } else {
                val maxEdge = if (size == "thumb") 256 else 1024
                copyResized(src, outFile, maxEdge)
            }
            AppLogger.info(TAG, "export: id=$id size=$size bytes=$bytesWritten path=${outFile.absolutePath}")
            val data = JSONObject()
                .put("id", id)
                .put("host_path", outFile.absolutePath)
                .put("size_bytes", bytesWritten)
                .put("media_type", mediaType)
                .put("format", exportFormatLabel(size, mediaType))
                .put("export_size", size)
                .put("note", "Written to host filesDir; reachable as `host_path` via shell tools. Linux-side bind-mount path varies per session.")
            // [da1034-A3] ISSUE-05: `export_size` records the size the caller
            // ASKED for, which for a video is not applied at all. Spelled out
            // in its own key rather than folded into `note` (that constant is
            // about where the file landed) because the fact is conditional on
            // the media kind — a photo export has nothing to say here.
            if (mediaType == "video") {
                data.put("size_note", "video assets are always copied as-is; --size is ignored for videos")
            }
            if (width > 0) data.put("width", width)
            if (height > 0) data.put("height", height)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "export_failed").put("id", id)
                .put("message", e.message ?: "Failed to copy asset bytes")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun copyStream(src: Uri, dst: File): Long {
        var written = 0L
        context.contentResolver.openInputStream(src)?.use { input ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n <= 0) break
                    out.write(buf, 0, n); written += n
                }
            }
        } ?: throw java.io.IOException("Could not open source asset stream")
        return written
    }

    /**
     * Re-encode an image asset to JPEG with max edge [maxEdge].
     *
     * [debt-1026 B3] Both halves of the SOURCE side were unbounded:
     *
     *  * `openInputStream(src)?.use { it.readBytes() }` materialised the whole
     *    asset with no ceiling, so a user-picked multi-hundred-MB file OOM'd
     *    before any decode ran. The read now stops one byte past
     *    [MAX_RESIZE_SOURCE_BYTES] (`readAtMost` — the same bounded primitive the
     *    SAF imports and the home-icon picker use) and an oversized source is
     *    refused loudly instead of silently exported at a bogus size.
     *  * the hand-rolled `maxEdge * 2` sampling loop left `sample` at 1 for
     *    anything below 2x the target (a 4000x3000 photo is ~48 MB of
     *    ARGB_8888), then `createScaledBitmap` shrank it afterwards. The decode
     *    now goes through the shared [ImageDecoding.decodeBytesBounded], which
     *    probes the header first and bounds the bitmap at (2 * maxEdge)^2.
     *
     * The encode half is unchanged on purpose: [deep-audit-1048 A-02] already
     * routes the `compress` verdict through `saveOutcome`, and the export caller
     * answers `export_failed` when this throws.
     */
    private fun copyResized(src: Uri, dst: File, maxEdge: Int): Long {
        val bytes = context.contentResolver.openInputStream(src)?.use { input ->
            readAtMost(input, MAX_RESIZE_SOURCE_BYTES)
                ?: throw java.io.IOException(
                    "Source image exceeds the ${MAX_RESIZE_SOURCE_BYTES / (1024 * 1024)} MB resize-read cap"
                )
        } ?: throw java.io.IOException("Could not read source bytes")
        val decoded = ImageDecoding.decodeBytesBounded(bytes, maxEdge)
            ?: throw java.io.IOException("Failed to decode source image")
        val bmp = decoded.bitmap
        try {
            val encoded = dst.outputStream().use { os ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, os)
            }
            // [deep-audit-1048 A-02] `Bitmap.compress(...)` returns FALSE when
            // the JPEG encoder could not write the pixels — it does not throw.
            // Dropped, `export` answered `size_bytes: 0` with success and left
            // a 0-byte file in the export dir. This helper already reports
            // failure by throwing (see copyStream's IOException): the export
            // caller catches it and answers `export_failed`.
            if (!com.rikkaminis.app.util.saveOutcome(streamOpened = true, compressed = encoded)) {
                throw java.io.IOException("JPEG encoder wrote no bytes (compress returned false)")
            }
        } finally {
            // [sweep1014-P8] The decode bound no longer hands back a second
            // (scaled) bitmap, so there is exactly one to release.
            bmp.recycle()
        }
        return dst.length()
    }

    // ── import (T64) ─────────────────────────────────────────────────────

    /**
     * Copy a *sandbox* file into MediaStore. Mirrors apple-photos import.
     * `--album-name` creates the bucket implicitly via RELATIVE_PATH
     * (Android API 29+) — there's no "create empty album" API.
     *
     * [deep-audit-1016c FIX-B A] `--path` is a GUEST path — that is the ONLY
     * shape HELP documents (`import --path <file>`, examples under
     * /var/minis/...), and the shape every other file-taking android-* flag
     * uses. It used to be handed straight to `File(path)`, which broke the
     * command twice over:
     *   • functionally — PRoot's bind layout is not the host layout, so a guest
     *     file never exists at the raw host path and EVERY import answered
     *     `no_file`;
     *   • as a read escape — a host-absolute path (e.g.
     *     `/data/data/<pkg>/databases/minis.db`, which is exactly what the
     *     host-side process CAN read) was opened as a plain host File and
     *     copied into the public MediaStore, i.e. out of the app sandbox.
     * Same bug class as [SpeechOffloadHandler.resolveSourcePath]
     * ([fix/sweep1013-security]) and the same remedy: normalize against the
     * guest cwd ([GuestPathMapper.absoluteGuestPath]), resolve through the
     * session-aware resolver, then FAIL CLOSED. There is deliberately no
     * `?: File(path)` tail — an unresolvable path is an error, not a guess.
     */
    private fun handleImport(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val rawPath = args.get("path")
            ?: return NativeOffloadResult(2, "android-photos import: --path <file> is required\n")
        val guestPath = GuestPathMapper.absoluteGuestPath(request.cwd, rawPath)
            ?: return importPathError(
                args, rawPath, "path_outside_sandbox",
                "Path climbs above the sandbox root; --path must name a file inside the " +
                    "sandbox (e.g. /var/minis/workspace/<file>).",
            )
        val src = PRootKernel.resolvePathForSession(request.sessionId, guestPath, context)
            ?: return importPathError(
                args, rawPath, "no_file",
                "No such file inside the sandbox: '$guestPath' is not reachable through any " +
                    "mounted tree. Check the path (relative paths resolve against the shell " +
                    "cwd) and that the file was written by this session.",
                guestPath,
            )
        if (!src.exists() || !src.isFile) {
            return importPathError(
                args, rawPath, "no_file",
                "Source file does not exist or is not a regular file.",
                guestPath,
            )
        }
        val albumName = args.get("album-name")
        val ext = src.extension.lowercase()
        val isVideo = ext in setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm")
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4v" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> if (isVideo) "video/*" else "image/*"
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                         else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rel = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val path = if (albumName != null) "$rel/${albumName.replace('/', '_')}" else rel
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        var insertedUri: Uri? = null
        return try {
            val uri = context.contentResolver.insert(collection, values)
                ?: return NativeOffloadResult(
                    1,
                    OffloadOutput.formatBody(
                        JSONObject().put("error", "insert_failed").put("message", "ContentResolver.insert returned null").toString(),
                        args,
                    ) + "\n",
                )
            insertedUri = uri
            val written = context.contentResolver.openOutputStream(uri)?.use { os ->
                FileInputStream(src).use { input ->
                    var n = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf); if (r <= 0) break
                        os.write(buf, 0, r); n += r
                    }
                    n
                }
            } ?: throw java.io.IOException("openOutputStream returned null")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, finishValues, null, null)
            }
            val newId = ContentUris.parseId(uri)
            AppLogger.info(TAG, "import: path='${src.name}' id=$newId bytes=$written album='$albumName'")
            val data = JSONObject()
                .put("id", newId)
                .put("name", src.name)
                .put("media_type", if (isVideo) "video" else "photo")
                .put("size_bytes", written)
                .put("mime_type", mime)
            if (albumName != null) data.put("album", albumName)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: Throwable) {
            // [deep-audit-1016c FIX-B A] An insert that succeeded but whose
            // write/finish step failed left an orphan IS_PENDING row in
            // MediaStore — every retry would stack another ghost row. Roll the
            // insert back; a failed cleanup is logged, never masked.
            insertedUri?.let { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (cleanup: Throwable) {
                    AppLogger.warning(TAG, "import rollback failed for $uri: ${cleanup.message}")
                }
            }
            val body = JSONObject().put("error", "import_failed").put("path", rawPath)
                .put("message", e.message ?: "Failed to insert media")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    /**
     * Structured refusal for `import --path` ([deep-audit-1016c FIX-B A]).
     * Keeps the historical `no_file` contract (exit 1 + `path` + `message`)
     * while distinguishing the two failure halves the model needs to tell
     * apart: a path that is not a sandbox path at all (`path_outside_sandbox`)
     * vs. one that is simply not there / not readable by this session
     * (`no_file`). `guest_path` is echoed back when known so the model can
     * self-correct against the shell's own view of the tree.
     */
    private fun importPathError(
        args: OffloadArgs,
        rawPath: String,
        error: String,
        message: String,
        guestPath: String? = null,
    ): NativeOffloadResult {
        val body = JSONObject()
            .put("error", error)
            .put("path", rawPath)
            .put("message", message)
        if (guestPath != null) body.put("guest_path", guestPath)
        return NativeOffloadResult(1, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    // ── create-album (T64) ───────────────────────────────────────────────

    /**
     * Android has no "create empty album" API — buckets are created
     * implicitly when files land in them. We return a structured note so
     * the model knows to use `import --album-name` instead of failing
     * silently. Mirrors apple-photos create-album shape (id/name/note).
     */
    private fun handleCreateAlbum(args: OffloadArgs): NativeOffloadResult {
        val name = args.get("name")
            ?: return NativeOffloadResult(2, "android-photos create-album: --name <name> is required\n")
        AppLogger.info(TAG, "create-album: name='$name' (Android implicit-create)")
        val data = JSONObject()
            .put("name", name)
            .put("created", false)
            .put("warning", "android_implicit_album")
            .put("message", "Android MediaStore creates album buckets implicitly on first file insert. Use `android-photos import --path <file> --album-name '$name'` to create '$name' as a side-effect of saving a file. The bucket id is assigned by the system at that point.")
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── favorite (T64) ───────────────────────────────────────────────────

    /**
     * Toggle MediaStore.MediaColumns.IS_FAVORITE (Android 11+). Mirrors
     * apple-photos favorite — toggles the flag and returns the new state.
     * On API < 30 returns a structured error since the column doesn't
     * exist.
     */
    private fun handleFavorite(args: OffloadArgs): NativeOffloadResult {
        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-photos favorite: --id <asset_id> is required\n")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val body = JSONObject().put("error", "not_supported")
                .put("message", "Favorite flag requires Android 11 (API 30) or later. This device runs API ${Build.VERSION.SDK_INT}.")
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        // Find the row in either Images or Video.
        var srcUri: Uri? = null
        var currentlyFavorite = false
        for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_FAVORITE),
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(id.toString()), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    srcUri = ContentUris.withAppendedId(uri, id)
                    currentlyFavorite = c.getInt(0) == 1
                }
            }
            if (srcUri != null) break
        }
        val target = srcUri ?: return NativeOffloadResult(
            1,
            OffloadOutput.formatBody(
                JSONObject().put("error", "not_found").put("id", id)
                    .put("message", "No asset with id=$id.").toString(),
                args,
            ) + "\n",
        )
        val newState = !currentlyFavorite
        return try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (newState) 1 else 0) }
            val rows = context.contentResolver.update(target, values, null, null)
            AppLogger.info(TAG, "favorite: id=$id was=$currentlyFavorite now=$newState rows=$rows")
            val data = JSONObject()
                .put("id", id)
                .put("favorite", newState)
                .put("rows_updated", rows)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: SecurityException) {
            // RecoverableSecurityException on A11+ when the user hasn't
            // explicitly granted write access to this asset. We can't
            // launch the system dialog from a CLI sandbox.
            val body = JSONObject().put("error", "write_denied")
                .put("id", id)
                .put("message", "Android requires explicit user consent to modify another app's media. Open the asset in the system Photos app and toggle favorite there. Underlying: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── delete (T64) ─────────────────────────────────────────────────────

    /**
     * Delete one or more assets. --ids comma-separated, --confirm
     * required (mirrors apple-photos delete). On A11+ MediaStore raises
     * RecoverableSecurityException when the asset wasn't created by us;
     * we surface that as a structured error since the CLI sandbox can't
     * show the system consent dialog.
     */
    private fun handleDelete(args: OffloadArgs): NativeOffloadResult {
        val idsStr = args.get("ids")
            ?: return NativeOffloadResult(2, "android-photos delete: --ids <id1,id2,...> is required\n")
        if (!args.hasFlag("confirm")) {
            return NativeOffloadResult(2, "android-photos delete: --confirm flag is required to delete assets\n")
        }
        val ids = idsStr.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
        if (ids.isEmpty()) {
            return NativeOffloadResult(2, "android-photos delete: --ids must contain at least one numeric id\n")
        }
        val deletedIds = JSONArray()
        val failed = JSONArray()
        for (id in ids) {
            // Try Images then Video.
            var rows = 0
            for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                try {
                    rows = context.contentResolver.delete(
                        uri,
                        "${MediaStore.Images.Media._ID} = ?",
                        arrayOf(id.toString()),
                    )
                    if (rows > 0) break
                } catch (e: SecurityException) {
                    failed.put(JSONObject().put("id", id).put("error", "write_denied").put("message", e.message ?: "RecoverableSecurityException"))
                    break
                } catch (e: Throwable) {
                    failed.put(JSONObject().put("id", id).put("error", "delete_failed").put("message", e.message ?: "unknown"))
                    break
                }
            }
            if (rows > 0) deletedIds.put(id)
            else if (failed.length() == 0 || failed.getJSONObject(failed.length() - 1).optLong("id") != id) {
                failed.put(JSONObject().put("id", id).put("error", "not_found").put("message", "No asset with this id"))
            }
        }
        AppLogger.info(TAG, "delete: requested=${ids.size} deleted=${deletedIds.length()} failed=${failed.length()}")
        val data = JSONObject()
            .put("deleted_ids", deletedIds)
            .put("deleted_count", deletedIds.length())
            .put("requested_count", ids.size)
        if (failed.length() > 0) data.put("failed", failed)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Mirror apple-photos resolve_date_range: --days N (last N days
     * through now) wins over --start/--end. Returns (startMs?, endMs?)
     * as a Pair; either side may be null when no flag set.
     *
     * [deep-audit-1016c FIX-B C] Two date-shaped bugs lived here:
     *   • a date-only `--end 2026-10-16` parsed to that day's 00:00:00, so
     *     `--start 2026-10-16 --end 2026-10-16` was a zero-width window that
     *     matched nothing — and `--end <today>` silently excluded everything
     *     the user took a photo of today. A date-only bound means the whole
     *     day; it is aligned to 23:59:59.999 ([alignDateOnlyEndMs]).
     *   • `--days N` did `now - N * 24h` and then truncated to local midnight.
     *     Across a DST transition that hour of drift can land the truncation on
     *     the wrong DAY (one extra or one missing day of results). It now walks
     *     the calendar ([daysAgoStartOfDayMs]) so the wall clock is preserved.
     */
    /**
     * [da1032-C] ISSUE-04: the `list` window, resolved fail-closed.
     *
     * Absent flags are not an error — they mean "no filter on that side", and
     * so do `--days`, `--start` alone and `--end` alone. What is an error is a
     * flag the caller DID supply that this command cannot read as a date: the
     * old shape returned null and `handleList` read null as "no filter", so
     * `android-photos list --start 2026-13-40` answered the ENTIRE library —
     * a typo silently widened the query instead of failing it. An inverted
     * window (`--end` before `--start`) is refused for the same reason: the
     * caller named a range that cannot contain anything.
     */
    private fun resolveDateRange(args: OffloadArgs): PhotoRange =
        resolvePhotoRange(
            nowMs = System.currentTimeMillis(),
            zone = TimeZone.getDefault(),
            daysRaw = args.getInt("days"),
            startRaw = args.get("start"),
            endRaw = args.get("end"),
            daysText = args.get("days"),
        ) { parseDate(it) }

    private fun parseDate(s: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd",
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = if (p.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
                    // [dv16-A A-01] Strict, like the sessions funnel
                    // (parseSessionsDate): the default lenient mode silently
                    // ROLLS an out-of-range field over (`2026-02-30` becomes
                    // March 2, `2026-13-01` next January), so a mistyped bound
                    // answered for a DIFFERENT day instead of being refused.
                    // Only overflow/roll-over changes: digit counts, the
                    // pattern list, zone handling and the date-only form are
                    // untouched.
                    isLenient = false
                }
                return sdf.parse(s)?.time
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun formatIso(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(Date(ms))
    }

    private fun photoStats(): String {
        var count = 0L
        var earliest = Long.MAX_VALUE
        var latest = 0L
        var totalSize = 0L
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.SIZE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                count++
                val d = c.getLong(0)
                if (d > 0) {
                    if (d < earliest) earliest = d
                    if (d > latest) latest = d
                }
                totalSize += c.getLong(1)
            }
        }
        // [da1034-A3] ISSUE-06: the second half of the A13+ media pair. Rows
        // come back empty (not an error) when READ_MEDIA_VIDEO is not granted,
        // which is exactly what `hasMediaPermission` now accepts.
        var videoCount = 0L
        var videoSize = 0L
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.SIZE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                videoCount++
                videoSize += c.getLong(0)
            }
        }
        val json = JSONObject()
            .put("total_photos", count)
            .put("total_size_mb", String.format("%.1f", totalSize / 1e6))
            // [da1034-A3] ISSUE-06: this function only ever queried
            // MediaStore.Images, so a device full of clips answered
            // `total_photos: 0` with nothing to say that videos had been left
            // out of the count. Videos now report in their OWN keys:
            // `total_size_mb` keeps pairing with `total_photos` (folding video
            // bytes in would make the per-photo average a lie) and
            // `earliest`/`latest` below keep their images-only meaning.
            .put("total_videos", videoCount)
            .put("total_video_size_mb", String.format("%.1f", videoSize / 1e6))
        if (earliest < Long.MAX_VALUE) json.put("earliest", formatDateTime(earliest))
        if (latest > 0) json.put("latest", formatDateTime(latest))
        return json.toString(2)
    }

    /**
     * `near` reads EXIF from each candidate photo rather than relying on the
     * MediaStore LATITUDE/LONGITUDE columns — those were deprecated in API 29
     * and now return 0 on most OEM Gallery implementations. Requires
     * ACCESS_MEDIA_LOCATION on A10+ and setRequireOriginal() to retrieve
     * un-redacted EXIF.
     *
     * NOTE: this is O(N) disk reads; we scan at most the newest 500 photos
     * to keep runtime bounded. Photos in encrypted "Secure Folder" / MIUI
     * private album / Huawei vault are not exposed to MediaStore and will
     * be invisible — we note that in the response when zero results come back.
     */
    private fun nearPhotos(lat: Double, lon: Double, radiusKm: Double, max: Int): String {
        val photos = JSONArray()
        val scanLimit = 500
        // ensureMediaLocationPermission() already guaranteed this on the
        // happy path; if a caller bypasses the gate we still degrade safely.
        val hasMediaLoc = hasMediaLocationPermission()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        var scanned = 0
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            while (c.moveToNext() && photos.length() < max && scanned < scanLimit) {
                scanned++
                val id = c.getLong(0)
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val uriToRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaLoc) {
                    runCatching { MediaStore.setRequireOriginal(contentUri) }.getOrDefault(contentUri)
                } else contentUri
                val coords = try {
                    context.contentResolver.openInputStream(uriToRead)?.use { input ->
                        val exif = ExifInterface(input)
                        val ll = FloatArray(2)
                        if (exif.getLatLong(ll)) doubleArrayOf(ll[0].toDouble(), ll[1].toDouble()) else null
                    }
                } catch (_: Throwable) { null } ?: continue

                val dist = haversine(lat, lon, coords[0], coords[1])
                if (dist <= radiusKm) {
                    val p = JSONObject()
                        .put("id", id)
                        .put("name", c.getString(1) ?: "")
                    val dt = c.getLong(2)
                    if (dt > 0) p.put("date", formatDateTime(dt))
                    p.put("latitude", coords[0])
                        .put("longitude", coords[1])
                        .put("distance_km", String.format("%.2f", dist))
                    photos.put(p)
                }
            }
        }
        // [da1033-D ISSUE-04] ONE envelope for the empty and the non-empty case.
        // The old shape was a bare JSONArray when photos matched and a bare
        // English sentence when none did, so the top-level type flipped with the
        // result and `jq` / `json.loads` died on the empty branch — the most
        // common outcome for a radius nobody has photos inside. Keys mirror the
        // sibling readers (`list` / `album` above); `count` makes empty vs
        // non-empty explicit, and `near` is no longer the only read subcommand
        // that hands back a bare array.
        val data = JSONObject()
            .put("media", photos)
            .put("count", photos.length())
            .put("lat", lat)
            .put("lon", lon)
            .put("radius_km", radiusKm)
        if (photos.length() == 0) {
            // Diagnostics only; the three sources are unchanged (redacted EXIF
            // without ACCESS_MEDIA_LOCATION, or the bounded scan window). Now a
            // JSON field instead of a sentence suffix, hence trimmed.
            val hint = if (!hasMediaLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "GPS EXIF is redacted without the ACCESS_MEDIA_LOCATION permission; add it to AndroidManifest and request it from the user."
            } else if (scanned == scanLimit) {
                "scanned the newest $scanLimit photos; no matches in that window"
            } else ""
            if (hint.isNotEmpty()) data.put("hint", hint)
        }
        return data.toString(2)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        private const val TAG = "PhotosOffload"

        /**
         * [debt-1026 B3] Ceiling on the source bytes [copyResized] reads before
         * decoding. Far above any real photo (a 200 MP JPEG is ~30 MB) yet low
         * enough to refuse the multi-hundred-MB file that OOMs the heap BEFORE
         * the decode bound can help: that bound limits the BITMAP, not the
         * encoded array that has to be materialised to build it.
         */
        private const val MAX_RESIZE_SOURCE_BYTES = 64L * 1024 * 1024

        /** [sweep1014-B22a] One queried media row: raw sort key + output JSON. */
        data class MediaEntry(val ts: Long, val json: JSONObject)

        /**
         * [sweep1014-B22a] Merge photo and video result lists — each already
         * sorted by DATE_TAKEN DESC and capped at [limit] by MediaStore —
         * into a single time-ordered list capped at [limit]. Entries with
         * ts <= 0 (unknown date) sort after dated ones. Pure function so the
         * quota/merge behavior is host-testable.
         */
        fun mergeMediaLimit(photos: List<MediaEntry>, videos: List<MediaEntry>, limit: Int): List<MediaEntry> {
            if (limit <= 0) return emptyList()
            val merged = ArrayList<MediaEntry>(photos.size + videos.size)
            var i = 0
            var j = 0
            while (merged.size < limit && (i < photos.size || j < videos.size)) {
                val takePhoto = when {
                    i >= photos.size -> false
                    j >= videos.size -> true
                    else -> photos[i].ts >= videos[j].ts
                }
                if (takePhoto) { merged.add(photos[i]); i++ } else { merged.add(videos[j]); j++ }
            }
            return merged
        }

        private const val HELP = """android-photos — query and manage device photos & videos
                              (mirrors apple-photos)

Usage:
  android-photos list [--type photo|video|all]
                      [--start ISO --end ISO | --days N] [--limit N]
  android-photos stats
  android-photos near --lat L --lon N [--radius KM] [--limit N]
                      (legacy: near <lat> <lon>)
  android-photos albums [--type user|smart|all]
  android-photos album --id <bucket_id> | --name <bucket_name> [--limit N]
  android-photos export --id <asset_id> [--size thumb|medium|original]
  android-photos import --path <file> [--album-name <name>]   (alias: save)
  android-photos create-album --name <name>
  android-photos favorite --id <asset_id>                     (Android 11+)
  android-photos delete --ids <id1,id2,...> --confirm

Aliases for backwards compatibility:
  --max ↔ --limit
  --lng ↔ --lon
  positional `near <lat> <lon>` (flag form preferred)

Android edge cases vs apple-photos:
  - No native smart-album concept. `albums --type smart` returns empty
    with a structured note. `--type user` and `--type all` are equivalent.
  - No "create empty album" API. `create-album` returns an instructional
    note pointing at `import --album-name <name>`.
  - On Android 11+ modifying or deleting another app's media triggers
    RecoverableSecurityException. Surfaced as `error: write_denied`
    since the CLI sandbox can't show the system consent dialog.
  - Export writes to host filesDir; the JSON returns `host_path`. The
    Linux-side bind-mount path varies per session, so we don't mirror
    iOS `data.path = /var/minis/offloads/...` exactly.
  - Import reads a SANDBOX path (`--path /var/minis/workspace/x.png`;
    relative paths resolve against the shell's cwd). Host paths such as
    /data/data/... are refused (`path_outside_sandbox` / `no_file`)
    instead of being read straight off the host filesystem.

Errors return JSON: {"error":"...","message":"..."}.
"""
    }
}

// ── [deep-audit-1016c FIX-B C] pure date helpers ─────────────────────────────
// Top-level (not members of the Android-coupled handler) so the JVM test source
// set can pin them with zero Android deps — the same split as
// GuestPathMapper.normalizeDotSegments / PRootKernel.hasDotDotSegment.
// Expectations live in PhotosDateRangePureTest as written literals.

private val DATE_ONLY_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")

/** True when [raw] is exactly a calendar date (`2026-10-16`) — no time part. */
internal fun isDateOnly(raw: String): Boolean = DATE_ONLY_SHAPE.matches(raw.trim())

/**
 * End-of-day for the local day [ms] falls in: 23:59:59.999 in [zone].
 * Goes through Calendar fields, not ±24h arithmetic, so a DST transition day
 * keeps its full local wall clock (23:00:00.000…22:59:59.999 where the clock
 * falls back / springs forward).
 */
internal fun alignDateOnlyEndMs(ms: Long, zone: TimeZone): Long {
    val cal = Calendar.getInstance(zone).apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }
    return cal.timeInMillis
}

/**
 * Local midnight N days before [nowMs] (for `--days N`: "last N days through
 * now"). Calendar.add(DAY_OF_YEAR, -n) keeps the wall clock across DST; the
 * old `now - N * 24h` form drifted an hour per transition and could truncate
 * onto the wrong day.
 */
internal fun daysAgoStartOfDayMs(nowMs: Long, days: Int, zone: TimeZone): Long {
    val cal = Calendar.getInstance(zone).apply {
        timeInMillis = nowMs
        add(Calendar.DAY_OF_YEAR, -days)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/**
 * [da1032-C] ISSUE-04: outcome of resolving the `list` window from
 * `--days` / `--start` / `--end`.
 *
 * [Window] carries the resolved bounds (either side may be null = no filter on
 * that side). [Invalid] names the flag the caller got wrong — the previous
 * shape collapsed this case into a null bound, which the query path read as
 * "no filter" and answered the whole library for.
 */
internal sealed class PhotoRange {
    data class Window(val startMs: Long?, val endMs: Long?) : PhotoRange()
    data class Invalid(val flag: String, val raw: String) : PhotoRange()
}

/**
 * [da1032-C] Pure decision core for the `android-photos list` date window.
 * Same three-part contract as CalendarOffloadHandler's resolveRangePure:
 * absent flags stay absent, a supplied-but-unreadable flag is refused by name,
 * and the day boundaries keep the calendar-walk semantics (bare-date `--end`
 * covers the whole day via [alignDateOnlyEndMs], `--days N` walks local days
 * via [daysAgoStartOfDayMs]) that deep-audit-1016c fixed here.
 *
 * [dv14-A3] ISSUE-05: `--days` is read both as an integer ([daysRaw]) and as
 * the raw string ([daysText]) so a supplied-but-unreadable value ("7d",
 * "last-week") is refused by name instead of reading as absent and answering
 * the ENTIRE library.
 *
 * [parse] is injected so the core stays free of the handler's SimpleDateFormat
 * list and can be driven with a fixed clock in JVM tests.
 */
internal fun resolvePhotoRange(
    nowMs: Long,
    zone: TimeZone,
    daysRaw: Int?,
    startRaw: String?,
    endRaw: String?,
    daysText: String?,
    parse: (String) -> Long?,
): PhotoRange {
    // [dv14-A3] ISSUE-05: `--days 7d` / `--days last-week` used to read as
    // "absent" (getInt → toIntOrNull → null) and resolved to Window(null, null)
    // → "no filter" → the whole photo library. Refuse it by name, same contract
    // as --start/--end below. Absent (`--days` given no value) and
    // non-positive integers keep their historical readings (degrade to 7).
    if (daysText != null && daysText.isNotBlank() && daysText.toIntOrNull() == null) {
        return PhotoRange.Invalid("--days", daysText)
    }
    if (daysRaw != null) {
        val n = if (daysRaw <= 0) 7 else daysRaw
        return PhotoRange.Window(daysAgoStartOfDayMs(nowMs, n, zone), nowMs)
    }
    val startMs = if (startRaw.isNullOrBlank()) {
        null
    } else {
        parse(startRaw) ?: return PhotoRange.Invalid("--start", startRaw)
    }
    val endMs = if (endRaw.isNullOrBlank()) {
        null
    } else {
        val ms = parse(endRaw) ?: return PhotoRange.Invalid("--end", endRaw)
        if (isDateOnly(endRaw)) alignDateOnlyEndMs(ms, zone) else ms
    }
    if (startMs != null && endMs != null && endMs < startMs) {
        return PhotoRange.Invalid("--end", endRaw.orEmpty())
    }
    return PhotoRange.Window(startMs, endMs)
}

/**
 * [dv14-A3] ISSUE-05 back-compat overload: the pre-daysText positional shape
 * (`…, endRaw, parse`), kept so every existing call site compiles unchanged.
 * It carries no raw string and therefore holds no refusal — the core above is
 * the one that decides. Declared AFTER the core so shape-based readers (the
 * scan gates slice a member by its first declaration) still see the core.
 */
internal fun resolvePhotoRange(
    nowMs: Long,
    zone: TimeZone,
    daysRaw: Int?,
    startRaw: String?,
    endRaw: String?,
    parse: (String) -> Long?,
): PhotoRange = resolvePhotoRange(nowMs, zone, daysRaw, startRaw, endRaw, null, parse)

// ── [da1034-A3] ISSUE-05 pure export-shape helpers ────────────────────────────
// Same split as the date helpers above: top-level, zero Android deps, so the
// JVM test source set pins them directly — and the naming/format decisions have
// ONE home instead of being spelled inline at the export call site.

/**
 * File-name stem for `export`: every character outside `[A-Za-z0-9._-]` folds
 * to `_`, capped at 64 chars. Behaviour unchanged — extracted verbatim so the
 * video path below shares it instead of re-copying the regex.
 */
internal fun exportSafeName(displayName: String): String =
    displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

/**
 * Container extension to write for an export: the asset's own trailing
 * extension, else `mp4` for a video / `jpg` for a photo.
 */
internal fun exportExt(displayName: String, mediaType: String): String =
    displayName.substringAfterLast('.', "")
        .ifEmpty { if (mediaType == "video") "mp4" else "jpg" }

/**
 * True when `export` writes the source bytes untouched.
 *
 * Videos are verbatim for EVERY `--size` — BitmapFactory cannot decode them, so
 * there is no frame to re-encode; photos are verbatim only at `original`.
 */
internal fun exportCopiesSourceBytes(size: String, mediaType: String): Boolean =
    size == "original" || mediaType == "video"

/** `format` field of the export JSON: `original` iff the bytes were not re-encoded. */
internal fun exportFormatLabel(size: String, mediaType: String): String =
    if (exportCopiesSourceBytes(size, mediaType)) "original" else "jpeg"

/**
 * Output file name for an export.
 *
 * A re-encoded photo (`thumb` / `medium`) is always JPEG, hence the forced
 * `.jpg`. Everything else keeps the asset's real extension — the old form
 * applied `.jpg` to every non-`original` export, so a video's untouched bytes
 * landed in a file named `…_thumb_clip.jpg`.
 */
internal fun exportFileName(
    id: Long,
    size: String,
    mediaType: String,
    safeName: String,
    ext: String,
): String {
    val sizeSuffix = if (size == "original") "" else "_$size"
    val copiesSource = exportCopiesSourceBytes(size, mediaType)
    val base = "${id}${sizeSuffix}_$safeName${if (copiesSource) "" else ".jpg"}"
    // Verbatim copies keep the real container extension; the forced `.jpg`
    // above only applies to re-encoded photos.
    return if (copiesSource && !base.endsWith(".$ext", ignoreCase = true)) "$base.$ext" else base
}
