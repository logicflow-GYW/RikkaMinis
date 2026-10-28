package com.rikkaminis.app.sandbox.offload

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.offload.OffloadPermissionManager
import com.rikkaminis.app.sandbox.NativeOffloadHandler
import com.rikkaminis.app.sandbox.NativeOffloadRequest
import com.rikkaminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * android-calendar — list, create, update, delete events; query free/busy.
 *
 * Mirrors apple-calendar (NativeOffloads/CalendarOffload.m) — same flag
 * names and subcommands so prompts and the agent loop are platform-
 * agnostic. Aliases are preserved for backwards compatibility with older
 * prompts (--max ↔ --limit, --description ↔ --notes).
 *
 * Usage:
 *   android-calendar list [--today | --days N | --start S --end E]
 *                          [--limit N] [--calendar NAME]
 *   android-calendar create --title T --start S [--end E]
 *                          [--notes N] [--location L] [--all-day]
 *                          [--alarm <minutes>] [--calendar NAME | --calendar-id ID]
 *   android-calendar update --id <event_id> [--title ...] [--start ...] [--end ...]
 *                          [--notes ...] [--location ...] [--all-day]
 *                          [--alarm <minutes>] [--calendar NAME | --calendar-id ID]
 *   android-calendar delete --id <event_id>
 *   android-calendar freebusy --start <ISO> --end <ISO>
 *   android-calendar calendars                List writable calendars
 */
// ── [da1034-A4] ISSUE-07: an explicit calendar selector never silently misses ──
//
// `--calendar <name>` / `--calendar-id <id>` on a WRITE used to fall back to the
// auto-pick heuristic whenever the selector matched no writable calendar. Two
// user-visible outcomes: `create` wrote the event into a calendar the caller
// never named, and `update` silently MOVED an existing event into the
// auto-picked calendar (so a typo in a script re-filed the user's events). The
// mapping from (selector, writable calendars) to an outcome is pure, so it
// lives here — top-level, not a companion member — and is asserted off-device
// (CalendarSelectorTest, plus a standalone slice run in the JVM harness).
//
// Read paths keep the old tolerance on purpose: `list --calendar NAME` is a
// FILTER, so "matches nothing" is a legitimate empty answer, not a typo to
// refuse. Only the write paths consume [CalendarSelection.Unmatched].

/** The parts of a writable calendar that explicit-selector matching needs. */
internal data class CalendarRef(val id: Long, val displayName: String)

/** Outcome of matching an explicit selector against the writable calendars. */
internal sealed class CalendarSelection {
    /** The selector matched this writable calendar. */
    data class Matched(val id: Long) : CalendarSelection()

    /** The selector was supplied but matched no writable calendar. */
    object Unmatched : CalendarSelection()
}

/**
 * Resolve an explicit `--calendar-id` / `--calendar` selector against [cals].
 *
 * `--calendar-id` wins over `--calendar` (the order callers already rely on),
 * and the name match stays case-insensitive `contains` (apple-calendar
 * parity). Returns null when NEITHER flag was supplied — the caller then owns
 * the auto-pick decision, which is what makes an unqualified
 * `android-calendar create` keep working. A non-null [CalendarSelection]
 * is a decision the caller must honour: [CalendarSelection.Unmatched] means
 * refuse, not "pick something for them".
 */
internal fun resolveExplicitCalendar(
    cals: List<CalendarRef>,
    calendarId: Long?,
    calendarName: String?,
): CalendarSelection? {
    if (calendarId == null && calendarName == null) return null
    if (calendarId != null) {
        return if (cals.any { it.id == calendarId }) {
            CalendarSelection.Matched(calendarId)
        } else {
            CalendarSelection.Unmatched
        }
    }
    if (calendarName == null) return null
    val hit = cals.firstOrNull { it.displayName.contains(calendarName, ignoreCase = true) }
    return if (hit != null) CalendarSelection.Matched(hit.id) else CalendarSelection.Unmatched
}

/**
 * [dv15-A2] A-03: what the caller actually WROTE for the calendar selector.
 *
 * "Was the flag written?" and "is the value an id?" used to be answered by two
 * different readers of the same argument — an existence test
 * (`args.get("calendar-id") == null`) on the update path and
 * `getLong("calendar-id")` on the create path. They disagree on a non-numeric
 * value: `--calendar-id work` reads as "written" (update therefore treats the
 * selector as explicit) while parsing to `null` (create sees no explicit
 * selector at all). Classifying the raw string once keeps the two answers apart
 * and gives [Unreadable] a decision of its own: refuse it, do not guess that the
 * caller meant a display name.
 */
internal sealed class CalendarSelector {
    /** Neither `--calendar-id` nor `--calendar` was supplied. */
    object Absent : CalendarSelector()

    /** A numeric `--calendar-id`. */
    data class ById(val id: Long) : CalendarSelector()

    /** A `--calendar` display-name substring. */
    data class ByName(val name: String) : CalendarSelector()

    /** `--calendar-id` was supplied but its value is not a number. */
    data class Unreadable(val raw: String) : CalendarSelector()
}

/**
 * [dv15-A2] A-03: single source for "which selector did the caller write?".
 * Precedence is unchanged (`--calendar-id` before `--calendar`); a
 * `--calendar-id` that does not parse as a number is [CalendarSelector.Unreadable]
 * and is never retried as a name (refuse-instead-of-guess).
 */
internal fun resolveCalendarSelector(rawId: String?, rawName: String?): CalendarSelector {
    if (rawId != null) {
        val id = rawId.toLongOrNull()
        return if (id != null) CalendarSelector.ById(id) else CalendarSelector.Unreadable(rawId)
    }
    if (rawName != null) return CalendarSelector.ByName(rawName)
    return CalendarSelector.Absent
}

/**
 * [dv18-A A-04] What the caller wrote for `--alarm` (reminder lead time in
 * minutes).
 *
 * `args.getInt("alarm")` answered ONE question for two callers: "is the value a
 * number?" — so "the flag was not given" and "the flag was given with -5 or
 * `abc`" both became `null`, and the two writers read that null differently:
 *
 *   - `create` skipped the reminder and still answered exit 0 (an alarm silently
 *     dropped);
 *   - `update` skipped the reminder, skipped every column write (nothing else to
 *     change) and still answered `{"updated": true, "rows": 0}` — a lie, because
 *     its own honesty comment reasoned about `values == 0 && alarm == null` and
 *     never imagined `values == 0 && alarm < 0`.
 *
 * Same shape as [CalendarSelector]: classify the raw string once, give the
 * unusable case a decision of its own (refuse, never ignore), and let both
 * writers read the same verdict.
 */
internal sealed class AlarmSelector {
    /** `--alarm` was not supplied: no reminder is written or replaced. */
    object Absent : AlarmSelector()

    /** A whole number of minutes ≥ 0. */
    data class Minutes(val value: Int) : AlarmSelector()

    /** `--alarm` was supplied but is not a whole minute count ≥ 0 (`-5`, `abc`). */
    data class Unreadable(val raw: String) : AlarmSelector()
}

/**
 * [dv18-A A-04] Pure form of the `--alarm` decision (no Context), shared by
 * `create` and `update` so the two boundaries can never disagree again.
 */
internal fun resolveAlarmSelector(raw: String?): AlarmSelector {
    if (raw == null) return AlarmSelector.Absent
    val minutes = raw.toIntOrNull()
    return if (minutes != null && minutes >= 0) AlarmSelector.Minutes(minutes)
    else AlarmSelector.Unreadable(raw)
}

/**
 * [dv18-A A-04] The one refusal both writers emit for an unusable `--alarm`, so
 * `create` and `update` cannot word the same defect differently.
 */
internal fun alarmRefusalMessage(verb: String, raw: String): String =
    "android-calendar $verb: --alarm must be a whole number of minutes >= 0 (got '$raw')\n"

class CalendarOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("today", "all-day"))
        // [T-offload-defaults-batch-android] Help only on explicit
        // --help/-h; no subcommand defaults to `list` (resolveDateRange
        // already defaults to the last-24h..now window with no flags).
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        // T330: tri-state agent gate before any work.
        OffloadGate.enforce("calendar", "android-calendar", args, request)?.let { return it }

        return try {
            when (val sub = args.positional.firstOrNull() ?: "list") {
                "list" -> doList(args)
                "create" -> doCreate(args)
                "update" -> doUpdate(args)
                "delete" -> doDelete(args)
                "freebusy" -> doFreebusy(args)
                "calendars" -> doListCalendars(args)
                else -> NativeOffloadResult(2, "android-calendar: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString(), args) + "\n",
            )
        }
    }

    private fun hasRead() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
    private fun hasWrite() = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * Request one or more calendar permissions if not yet granted, blocking
     * the tool invocation until the user responds (or permanently denies).
     * Mirrors the location handler's pattern: system dialog → settings gate.
     * Returns null if permission ends up granted; otherwise a ready-to-return
     * error [NativeOffloadResult] describing why we gave up.
     */
    private fun ensurePermission(
        permissions: List<String>,
        satisfied: () -> Boolean,
        humanLabel: String,
        settingsId: String,
        args: OffloadArgs,
    ): NativeOffloadResult? {
        if (satisfied()) return null
        AppLogger.warning(TAG, "$humanLabel not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(permissions)
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant(satisfied)
            ) {
                AppLogger.info(TAG, "Calendar $humanLabel permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = settingsId,
                        title = "Calendar permission needed",
                        message = "RikkaMinis needs $humanLabel permission to $humanLabel your calendar. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { satisfied() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject()
                        .put("error", "permission_denied")
                        .put("message", "The user declined the $humanLabel permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject()
                        .put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the $humanLabel permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun doList(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }

        val (startMs, endMs) = resolveDateRange(args)
            ?: return NativeOffloadResult(
                2,
                "android-calendar list: invalid or missing date range. Use --today, --days N, or --start/--end (ISO 8601 or YYYY-MM-DD).\n",
            )
        // --limit is the apple-calendar name; --max kept as alias.
        // [dv29-A3 A-05] Resolved through the shared contract: a spelled page
        // size the handler cannot honour is a usage error, not a default. The
        // old `getInt("limit") ?: getInt("max") ?: DEFAULT_LIMIT` answered
        // `--limit -5` with exit 0, `"events": []` and a `_warning` claiming
        // "-5 of 0 total records" were returned — `total > limit` is true for
        // ANY total once the limit is negative, so an empty window was
        // announced as truncated.
        val limit = when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.CALENDAR)) {
            is PageSizeOutcome.Ok -> size.value
            is PageSizeOutcome.Invalid ->
                return NativeOffloadResult(2, "android-calendar list: ${size.message}\n")
        }
        // [da1034-A4] ISSUE-07: `list --calendar NAME` is a FILTER, not a target
        // selection — "matches nothing" is a legitimate empty answer (below),
        // so the write paths' new calendar_not_found refusal deliberately does
        // NOT apply here. Same contains/ignoreCase semantics as the write-side
        // name match.
        val calFilter = args.get("calendar")

        // [sweep1014-B25a] Query CalendarContract.Instances instead of Events:
        // Instances auto-expands RRULE recurrences, and the BEGIN/END
        // window-intersection selection below (same shape as doFreebusy's
        // `DTSTART < end AND DTEND > start`) keeps multi-day and in-progress
        // events visible. The old Events query only matched rows whose
        // *start* fell inside the window, silently dropping those.
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val (selection, selArgs) = instancesWindowSelection(startMs, endMs)
        val sort = "${CalendarContract.Instances.BEGIN} ASC"

        val cursor = try {
            // [sweep1014-B25a-fix] CalendarProvider2's UriMatcher only registers
            // `instances/when/#/#` (begin/end path segments, ms). The bare
            // CONTENT_URI (`instances/when`) falls through to the matcher's
            // default branch and the provider throws
            // IllegalArgumentException: Unknown URL content://com.android.calendar/instances/when
            // (verified on-device via shizuku content query). startMs/endMs
            // are Longs from resolveDateRange and appendPath percent-encodes,
            // so no injection surface into the URI path.
            val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(startMs.toString())
                .appendPath(endMs.toString())
                .build()
            context.contentResolver.query(
                instancesUri, projection, selection, selArgs, sort,
            )
        } catch (e: SecurityException) {
            return NativeOffloadResult(77, providerBlockedJson("READ_CALENDAR", e, args))
        } catch (e: Throwable) {
            return NativeOffloadResult(1, providerErrorJson(e, args))
        } ?: return NativeOffloadResult(1, providerErrorJson(null, args))

        // Two-pass: gather all matches (so we can compute total_available
        // before truncating to --limit, matching apple-calendar's
        // _warning + total_available shape).
        val matches = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val calName = it.getString(8) ?: ""
                if (calFilter != null && !calName.contains(calFilter, ignoreCase = true)) continue
                matches.put(
                    JSONObject()
                        .put("id", it.getLong(0))
                        .put("title", it.getString(1) ?: "")
                        .put("start", formatIso(it.getLong(2)))
                        .put("start_ms", it.getLong(2))
                        .put("end", formatIso(it.getLong(3)))
                        .put("end_ms", it.getLong(3))
                        .put("location", it.getString(4) ?: "")
                        .put("notes", it.getString(5) ?: "")
                        .put("description", it.getString(5) ?: "")
                        .put("all_day", it.getInt(6) == 1)
                        .put("calendar_id", it.getLong(7))
                        .put("calendar", calName),
                )
            }
        }

        val total = matches.length()
        val truncated = total > limit
        val events = JSONArray()
        for (i in 0 until minOf(total, limit)) events.put(matches.getJSONObject(i))

        if (events.length() == 0) {
            // Check whether the user actually has any writable calendar account —
            // if not, the "no events" answer is misleading. Report the real reason.
            val accounts = listWritableCalendars()
            if (accounts.length() == 0) {
                return NativeOffloadResult(
                    0,
                    OffloadOutput.formatBody(
                        JSONObject()
                            .put("events", JSONArray())
                            .put("warning", "calendar_no_account")
                            .put("message", "No calendar account is configured on this device. Ask the user to add a Google or Exchange account in system Settings → Accounts.")
                            .toString(),
                        args,
                    ) + "\n",
                )
            }
        }
        val data = JSONObject()
            .put("events", events)
            .put("range", JSONObject().put("start", formatIso(startMs)).put("end", formatIso(endMs)))
            .put("count", events.length())
        if (truncated) {
            data.put("_warning", "Results truncated by --limit. Returned $limit of $total total records. Use a larger --limit to retrieve more data.")
            data.put("total_available", total)
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    private fun doCreate(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val title = args.get("title")
            ?: return NativeOffloadResult(2, "android-calendar create: --title is required\n")
        val startStr = args.get("start")
            ?: return NativeOffloadResult(2, "android-calendar create: --start is required\n")
        // [sweep1014-B25b] All-day events must be anchored at UTC midnight
        // (EVENT_TIMEZONE = "UTC" below per CalendarContract docs) — parse
        // zone-less dates in UTC, not the device zone. In UTC+8 the old
        // local-midnight parse landed a 2026-10-14 all-day event on 10-13.
        val isAllDay = args.hasFlag("all-day")
        val startMs = (if (isAllDay) parseDateUtc(startStr) else parseDate(startStr))
            ?: return NativeOffloadResult(2, "android-calendar: invalid --start '$startStr'\n")
        // [da1032-C] ISSUE-04: an EXPLICIT `--end` that fails to parse must be
        // refused, not silently replaced by the 1h default — `--end 2026-13-40`
        // used to write a one-hour event at the caller's start and answer
        // success. [createEndMs] keeps the two cases apart: absent → the 1h
        // default (unchanged), present-but-unparseable → null → refusal, the
        // same shape doUpdate/doFreebusy use.
        val rawEnd = args.get("end")
        val endMs = createEndMs(rawEnd, startMs) { if (isAllDay) parseDateUtc(it) else parseDate(it) }
            ?: return NativeOffloadResult(2, "android-calendar create: invalid --end '$rawEnd'\n")
        // [da1031-C] ISSUE-05 B: an explicit --end at or before --start used to
        // be written as-is → DTSTART > DTEND, and the provider then hides the
        // row from list/instances/freebusy (the all-day half of this shape was
        // fixed in da1017-D2). [meta1028-D2 F-04] The predicate now also covers
        // an INVERTED all-day request (`--all-day --start D20 --end D15` used
        // to answer created:true and write the hidden row); an all-day end
        // EQUAL to its start stays legal — that is the canonical single-day
        // shape ([allDayEndRepairMs]), which is why the guard cannot simply
        // drop its all-day branch.
        if (invertedTimedRange(isAllDay, startMs, endMs)) {
            return NativeOffloadResult(2, "android-calendar create: --end must be after --start\n")
        }
        // Notes — apple-calendar uses --notes; --description kept as alias.
        val notes = args.get("notes") ?: args.get("description")
        // [dv18-A A-04] One classification for both writers: `--alarm` that is
        // not a whole minute count >= 0 is refused HERE, before any write, so
        // `create --alarm -5` cannot answer exit 0 with the reminder silently
        // dropped (the old `alarmMinutes >= 0` test only guarded the reminder
        // call and left the rest of create reporting success).
        val alarm = when (val selector = resolveAlarmSelector(args.get("alarm"))) {
            is AlarmSelector.Unreadable -> return NativeOffloadResult(2, alarmRefusalMessage("create", selector.raw))
            else -> selector
        }
        val alarmMinutes = (alarm as? AlarmSelector.Minutes)?.value

        val calendarId = when (val target = requireCalendarTarget(args)) {
            is CalendarTarget.Id -> target.value
            is CalendarTarget.Refused -> return target.result
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            args.get("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (args.hasFlag("all-day")) put(CalendarContract.Events.ALL_DAY, 1)
            // ALL_DAY events must have UTC midnight + EVENT_TIMEZONE = "UTC"
            // per CalendarContract docs, otherwise Google Calendar rejects.
            if (args.hasFlag("all-day")) put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                NativeOffloadResult(1, OffloadOutput.formatBody(JSONObject().put("error", "insert_failed").put("message", "ContentResolver.insert returned null").toString(), args) + "\n")
            } else {
                val eventId = uri.lastPathSegment?.toLongOrNull()
                if (eventId != null && alarmMinutes != null) {
                    insertReminder(eventId, alarmMinutes)
                }
                AppLogger.info(TAG, "create: id=$eventId title='$title' alarm=$alarmMinutes")
                val data = JSONObject()
                    .put("id", eventId ?: -1L)
                    .put("title", title)
                    .put("start", formatIso(startMs))
                    .put("end", formatIso(endMs))
                    .put("calendar_id", calendarId)
                if (notes != null) data.put("notes", notes)
                if (alarmMinutes != null) data.put("alarm_minutes_before", alarmMinutes)
                NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
            }
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── update ──────────────────────────────────────────────────────────

    private fun doUpdate(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-calendar update: --id <event_id> is required\n")

        // [da1017-D2] All-day handling (was: silently ignored).
        //
        //  - `--all-day` used to be parsed as a boolean flag and then dropped:
        //    the event stayed TIMED and the call still answered
        //    {"updated":true,"rows":1} (measured on-device, event stayed
        //    allDay=0 with eventTimezone=Asia/Singapore).
        //  - `--start`/`--end` were always parsed in the device zone, but the
        //    provider stores all-day events at UTC midnight: fixAllDayTime()
        //    zeroes h/m/s in UTC and instance expansion forces TZ=UTC for
        //    allDay rows (CalendarInstancesHelper: "in the events table,
        //    allDay events start at midnight"). Re-parsing a bare date in
        //    UTC+8 then truncated the value back a day — "move it to 11-26"
        //    left the event on 11-25 while answering rows=1 (measured).
        //
        // The stored row decides the semantics even when the caller omitted
        // the flag (moving an existing all-day event), and its day-span keeps
        // DTEND consistent — see allDayEndRepairMs for why DTSTART cannot be
        // written alone.
        val explicitAllDay = args.hasFlag("all-day")
        val stored = findEventRow(id)
        val isAllDay = updateUsesAllDaySemantics(explicitAllDay, stored?.allDay)
        fun parseInput(s: String): Long? = if (isAllDay) parseDateUtc(s) else parseDate(s)

        // Build a sparse ContentValues with only the fields the caller supplied.
        val values = ContentValues()
        args.get("title")?.let { values.put(CalendarContract.Events.TITLE, it) }
        val newStartMs = args.get("start")?.let { s ->
            parseInput(s) ?: return NativeOffloadResult(2, "android-calendar update: invalid --start '$s'\n")
        }
        val newEndMs = args.get("end")?.let { e ->
            parseInput(e) ?: return NativeOffloadResult(2, "android-calendar update: invalid --end '$e'\n")
        }
        newStartMs?.let { values.put(CalendarContract.Events.DTSTART, it) }
        newEndMs?.let { values.put(CalendarContract.Events.DTEND, it) }
        args.get("location")?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        (args.get("notes") ?: args.get("description"))?.let {
            values.put(CalendarContract.Events.DESCRIPTION, it)
        }
        if (explicitAllDay) {
            // Same stored shape as doCreate: ALL_DAY=1 rides with
            // EVENT_TIMEZONE="UTC" (Google Calendar rejects a local-tz
            // all-day event, and the provider derives all-day boundaries in
            // UTC regardless of the stored timezone).
            values.put(CalendarContract.Events.ALL_DAY, 1)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        // [da1017-D2] An all-day event whose DTSTART moves without --end must
        // not leave the stale DTEND behind: instance expansion drops any row
        // whose dtend precedes the query window, so the "moved" event would
        // silently disappear from both list and freebusy (measured on-device:
        // an inverted all-day row, dtstart=11-26T00Z > dtend=11-25T00Z, was
        // absent from instances/when over 11-25T23:00Z..11-27T00:00Z and
        // `android-calendar list` answered events=[] for that day). Keep the
        // stored day-span instead — 0 for the canonical single-day row, where
        // the provider itself stores dtstart == dtend.
        if (isAllDay && newStartMs != null && newEndMs == null) {
            allDayEndRepairMs(stored?.startMs, stored?.endMs, newStartMs)?.let {
                values.put(CalendarContract.Events.DTEND, it)
            }
        }
        // [da1031-C] ISSUE-05 B: same stale-DTEND hazard for TIMED events —
        // moving --start without --end used to leave the old DTEND behind, and
        // the provider hides any row whose dtend precedes its dtstart. Keep the
        // stored duration; degenerate/unreadable stored span falls back to
        // doCreate's 1h default (timedEndRepairMs).
        if (!isAllDay && newStartMs != null && newEndMs == null) {
            timedEndRepairMs(stored?.startMs, stored?.endMs, newStartMs)?.let {
                values.put(CalendarContract.Events.DTEND, it)
            }
        }
        // --calendar / --calendar-id: move event to a different calendar.
        // optionalCalendarTarget keeps "flag absent ⇒ no move" (a move would
        // mean "always move" — not what update should do) while refusing an
        // explicit selector that matches nothing: the old fall-through MOVED
        // the event into the auto-picked calendar.
        when (val target = optionalCalendarTarget(args)) {
            null -> Unit
            is CalendarTarget.Id -> values.put(CalendarContract.Events.CALENDAR_ID, target.value)
            is CalendarTarget.Refused -> return target.result
        }
        // [dv18-A A-04] The SAME classification create uses: an `--alarm` that is
        // not a whole minute count >= 0 is refused here instead of read as
        // "absent", which used to let `--alarm -5` fall through with nothing else
        // to change, skip every write and still answer {"updated": true}.
        val alarm = when (val selector = resolveAlarmSelector(args.get("alarm"))) {
            is AlarmSelector.Unreadable -> return NativeOffloadResult(2, alarmRefusalMessage("update", selector.raw))
            else -> selector
        }
        val alarmMinutes = (alarm as? AlarmSelector.Minutes)?.value

        if (values.size() == 0 && alarmMinutes == null) {
            return NativeOffloadResult(2, "android-calendar update: nothing to update — supply at least one field flag\n")
        }

        // [da1031-C] ISSUE-05 B: an --id that matches no row used to fall
        // through with rows=0 and STILL (a) write the orphan reminder and
        // (b) answer {"updated": true} with exit 0 — a silent lie. Same
        // {error: not_found} shape doDelete uses (findEventRow also returns
        // null when the row exists but this app cannot read it — no
        // READ_CALENDAR / OEM privacy layer — and guessing the stored
        // semantics is what da1017-D2 was about, so "not found" is the honest
        // answer there too).
        if (stored == null) {
            val body = JSONObject().put("error", "not_found")
                .put("id", id)
                .put("message", "No event with id=$id (already deleted, or not visible to this app).")
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }

        // [da1031-C] ISSUE-05 B: refuse a range that still ends at or before it
        // starts once the request is resolved — an explicitly inverted --end, or
        // an unreadable/degenerate stored row the repairs above could not
        // replace. Fires only when the caller actually touched the range
        // (--start / --end present): a title-only update of a pre-existing
        // inverted row keeps working (editing notes must not be blocked by
        // legacy data this app itself once wrote).
        // [meta1028-D2 F-04] All-day updates answer to the same guard now; only
        // their zero-width half is legal (`invertedTimedRange` KDoc).
        if (newStartMs != null || newEndMs != null) {
            val effectiveStart = newStartMs ?: stored.startMs
            val effectiveEnd = newEndMs ?: values.getAsLong(CalendarContract.Events.DTEND) ?: stored.endMs
            if (invertedTimedRange(isAllDay, effectiveStart, effectiveEnd)) {
                return NativeOffloadResult(2, "android-calendar update: --end must be after --start\n")
            }
        }

        return try {
            val updated = if (values.size() > 0) {
                val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                    .appendPath(id.toString()).build()
                context.contentResolver.update(uri, values, null, null)
            } else 0
            // [da1031-C] ISSUE-05 B: an --id that matches no row used to fall
            // through with rows=0 and STILL (a) write the orphan reminder and
            // (b) answer {"updated": true} with exit 0 — a silent lie. Report
            // the same {error: not_found} shape doDelete uses, before any
            // reminder write.
            if (values.size() > 0 && updated <= 0) {
                val body = JSONObject().put("error", "not_found")
                    .put("id", id)
                    .put("message", "No event with id=$id (already deleted, or not visible to this app).")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
            }
            if (alarmMinutes != null) {
                replaceReminder(id, alarmMinutes)
            }
            AppLogger.info(TAG, "update: id=$id rows=$updated alarm=$alarmMinutes")
            val data = JSONObject()
                .put("id", id)
                // Reachable set here: values>0 → updated>0 (checked above), or
                // values==0 with a valid --alarm (values==0 && no alarm returned 2
                // earlier — and an UNUSABLE --alarm is refused before this point by
                // the same classification create uses) — so true is honest, unlike
                // the old value that a rows=0 `--alarm -5` fall-through satisfied
                // too.
                .put("updated", true)
                .put("rows", updated)
            if (alarmMinutes != null) data.put("alarm_minutes_before", alarmMinutes)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── delete ──────────────────────────────────────────────────────────

    private fun doDelete(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-calendar delete: --id <event_id> is required\n")

        return try {
            val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendPath(id.toString()).build()
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows <= 0) {
                val body = JSONObject().put("error", "not_found")
                    .put("id", id)
                    .put("message", "No event with id=$id (already deleted, or not visible to this app).")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
            }
            AppLogger.info(TAG, "delete: id=$id rows=$rows")
            val data = JSONObject().put("id", id).put("deleted", true).put("rows", rows)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── freebusy ────────────────────────────────────────────────────────

    private fun doFreebusy(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }

        val startStr = args.get("start") ?: return NativeOffloadResult(2, "android-calendar freebusy: --start is required\n")
        val endStr = args.get("end") ?: return NativeOffloadResult(2, "android-calendar freebusy: --end is required\n")
        val nowMs = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val startMs = parseDateInZone(startStr, nowMs, zone)
            ?: return NativeOffloadResult(2, "android-calendar: invalid --start '$startStr'\n")
        // [da1016c-F F-B] A bare "YYYY-MM-DD" --end covers the whole day
        // (inclusive 23:59:59.999): `--start D --end D` used to build a
        // zero-width window and answer empty for a day the caller named
        // explicitly. Same treatment as the list window (alignBareDateEnd).
        val endMs = parseDateInZone(endStr, nowMs, zone)
            ?.let { alignBareDateEnd(it, endStr, zone) }
            ?: return NativeOffloadResult(2, "android-calendar: invalid --end '$endStr'\n")
        if (endMs <= startMs) {
            return NativeOffloadResult(2, "android-calendar freebusy: --end must be after --start\n")
        }

        // [da1017-D1] Query CalendarContract.Instances, not Events.
        //
        // Recurring events carry RRULE + DURATION and leave Events.DTEND NULL:
        // the provider rejects the two together (CalendarProvider2
        // validateEventData: "Cannot have both DTEND and DURATION in an
        // event"), and the documented insert contract is "dtend if the event
        // is non-recurring / duration if the event is recurring". A NULL
        // DTEND makes the old `DTEND > ?` predicate UNKNOWN — never true —
        // for *every* recurring event, so weekly stand-ups, birthdays and
        // holidays were all reported as free.
        //
        // Measured on-device (2026-10-16, Android 15) with a probe row
        // FREQ=WEEKLY;COUNT=6 (dtend=NULL, duration=P1H): the Events
        // predicate returned "No result found" for a window containing an
        // occurrence, and `android-calendar freebusy` answered busy=[] with
        // the whole window marked free, while `android-calendar list` (which
        // already reads Instances) showed the occurrence. Instances expands
        // RRULE rows and carries BEGIN/END per occurrence — the same source
        // doList migrated to in [sweep1014-B25a].
        val projection = freebusyInstanceProjection()
        val (selection, selArgs) = instancesWindowSelection(startMs, endMs)
        val sort = "${CalendarContract.Instances.BEGIN} ASC"

        val cursor = try {
            // Same UriMatcher constraint as doList ([sweep1014-B25a-fix]): the
            // bare instances CONTENT_URI has no registered match and the
            // provider throws IllegalArgumentException — the window must ride
            // as the two trailing path segments (instances/when/<start>/<end>).
            // startMs/endMs are Longs, so no URI-injection surface.
            val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(startMs.toString())
                .appendPath(endMs.toString())
                .build()
            context.contentResolver.query(instancesUri, projection, selection, selArgs, sort)
        } catch (e: SecurityException) {
            return NativeOffloadResult(77, providerBlockedJson("READ_CALENDAR", e, args))
        } catch (e: Throwable) {
            return NativeOffloadResult(1, providerErrorJson(e, args))
        } ?: return NativeOffloadResult(1, providerErrorJson(null, args))

        val busy = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val isAllDay = it.getInt(3) == 1
                if (isAllDay) continue // skip all-day per apple-calendar
                busy.put(
                    JSONObject()
                        .put("title", it.getString(0) ?: "")
                        .put("start", formatIso(it.getLong(1)))
                        .put("end", formatIso(it.getLong(2))),
                )
            }
        }

        // Compute free slots in [startMs, endMs] from the busy list.
        // The busy list is already sorted by start; we still cap each busy
        // segment to the query window so an event that straddles the window
        // doesn't poison the cursor walk.
        val free = JSONArray()
        var cursorMs = startMs
        for (i in 0 until busy.length()) {
            val b = busy.getJSONObject(i)
            val bStart = parseDate(b.getString("start")) ?: continue
            val bEnd = parseDate(b.getString("end")) ?: continue
            val segStart = maxOf(bStart, startMs)
            val segEnd = minOf(bEnd, endMs)
            if (cursorMs < segStart) {
                free.put(JSONObject().put("start", formatIso(cursorMs)).put("end", formatIso(segStart)))
            }
            if (segEnd > cursorMs) cursorMs = segEnd
        }
        if (cursorMs < endMs) {
            free.put(JSONObject().put("start", formatIso(cursorMs)).put("end", formatIso(endMs)))
        }

        AppLogger.info(TAG, "freebusy: busy=${busy.length()} free=${free.length()}")
        val data = JSONObject().put("busy", busy).put("free", free)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── Reminders helpers (calendar alarms) ─────────────────────────────

    /**
     * Insert a single reminder row for [eventId] at [minutesBefore]. Mirrors
     * iOS EKAlarm.alarmWithRelativeOffset(-N * 60). Reminders.METHOD_ALERT
     * is the standard reminder type; ContentResolver.insert returns null on
     * provider failure, which we silently swallow — the event itself is
     * already created/updated.
     */
    private fun insertReminder(eventId: Long, minutesBefore: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        try {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "insertReminder failed for event=$eventId: ${e.message}")
        }
    }

    /** Remove existing reminders for [eventId] then insert one at [minutesBefore]. */
    private fun replaceReminder(eventId: Long, minutesBefore: Int) {
        try {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
            )
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "replaceReminder delete failed for event=$eventId: ${e.message}")
        }
        insertReminder(eventId, minutesBefore)
    }

    private fun doListCalendars(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }
        val arr = listWritableCalendars()
        return NativeOffloadResult(0, OffloadOutput.formatBody(arr.toString(2), args) + "\n")
    }

    // ── Range / calendar resolution ─────────────────────────────────────

    /**
     * Mirror apple-calendar resolve_date_range: --today (today only, [00:00,
     * next-day 00:00) via Calendar day arithmetic — DST-safe, no 24h constant)
     * → --days N (last N calendar days through now, start at 00:00 via
     * add(DAY_OF_YEAR, -N)) → --start/--end (explicit; a bare "YYYY-MM-DD"
     * --end is INCLUSIVE of that whole day — through 23:59:59.999, same 口径
     * as SessionsOffloadHandler.parseEndDate — while datetime inputs keep
     * their explicit instant; missing sides default to last 24h on either
     * side, unchanged). Returns null only when --start/--end are supplied but
     * unparseable, or when --days is supplied but unreadable ([dv14-A3]
     * ISSUE-05: "7d" / "last-week" used to read as absent and silently degrade
     * to the last-24h window — doList's exit-2 answer now covers it).
     *
     * [deep-audit-1016c ISSUE-09B] The decision core is [resolveRangePure]
     * (companion, JVM-tested with pinned DST-day literals); this shell only
     * supplies device-zone inputs.
     */
    private fun resolveDateRange(args: OffloadArgs): Pair<Long, Long>? =
        resolveRangePure(
            nowMs = System.currentTimeMillis(),
            zone = TimeZone.getDefault(),
            todayFlag = args.hasFlag("today"),
            daysRaw = args.getInt("days"),
            startRaw = args.get("start"),
            endRaw = args.get("end"),
            daysText = args.get("days"),
        ) { s, nowMs, z -> parseDateInZone(s, nowMs, z) }

    /** Writable calendars as the pure selector matcher sees them. */
    private fun writableCalendarRefs(cals: JSONArray): List<CalendarRef> {
        val out = ArrayList<CalendarRef>(cals.length())
        for (i in 0 until cals.length()) {
            val c = cals.getJSONObject(i)
            out.add(CalendarRef(id = c.optLong("id", -1L), displayName = c.optString("display_name")))
        }
        return out
    }

    /**
     * [da1034-A4] ISSUE-07: the target calendar for a WRITE. Resolution order
     * is unchanged — an explicit `--calendar-id`, then a `--calendar` name
     * (case-insensitive contains), then the auto-pick heuristic when NEITHER
     * flag was supplied — but an explicit selector that matches nothing now
     * returns a refusal ([CalendarTarget.Refused]) instead of writing into the
     * auto-picked calendar. The device having no writable calendar at all keeps
     * the historical `calendar_no_account` answer.
     *
     * [dv15-A2] A-03: "the flag was not written" and "the flag was written but
     * is not an id" are DIFFERENT answers now. [resolveCalendarSelector]
     * classifies the raw string before any id is parsed, so `--calendar-id work`
     * refuses (exit 2, naming the raw value) instead of reading as "no selector"
     * and silently writing into the auto-picked calendar. The auto-pick itself
     * is untouched: it still runs only when neither flag was supplied.
     */
    private fun requireCalendarTarget(args: OffloadArgs): CalendarTarget {
        val cals = listWritableCalendars()
        val selector = resolveCalendarSelector(args.get("calendar-id"), args.get("calendar"))
        if (selector is CalendarSelector.Unreadable) {
            return CalendarTarget.Refused(unreadableSelectorError(selector, args))
        }
        val id = (selector as? CalendarSelector.ById)?.id
        val name = (selector as? CalendarSelector.ByName)?.name
        when (val sel = resolveExplicitCalendar(writableCalendarRefs(cals), id, name)) {
            is CalendarSelection.Matched -> return CalendarTarget.Id(sel.id)
            is CalendarSelection.Unmatched ->
                return CalendarTarget.Refused(
                    if (cals.length() == 0) missingCalendarError(args) else calendarNotFoundError(args, cals),
                )
            null -> Unit
        }
        val picked = pickWritableCalendar(cals)
            ?: return CalendarTarget.Refused(missingCalendarError(args))
        return CalendarTarget.Id(picked)
    }

    /** [requireCalendarTarget] but null when NEITHER flag was supplied, so
     *  update keeps its "absence of the flag doesn't move the event" contract.
     *  An explicit selector that matches nothing still refuses — and so does an
     *  explicit `--calendar-id` whose value is not an id ([dv15-A2] A-03: the
     *  old existence test here read `--calendar-id work` as "written", so update
     *  MOVED the event into the auto-picked calendar with exit 0). */
    private fun optionalCalendarTarget(args: OffloadArgs): CalendarTarget? {
        if (resolveCalendarSelector(args.get("calendar-id"), args.get("calendar")) is CalendarSelector.Absent) return null
        return requireCalendarTarget(args)
    }

    /** Resolution outcome for a write: a calendar id, or a ready-to-send refusal. */
    private sealed class CalendarTarget {
        data class Id(val value: Long) : CalendarTarget()
        data class Refused(val result: NativeOffloadResult) : CalendarTarget()
    }

    /**
     * [dv15-A2] A-03: `--calendar-id` was written but its value is not an id.
     * Refuse (exit 2, nothing written) and name the raw value: the pre-fix code
     * either auto-picked a calendar (create) or MOVED the event into the
     * auto-picked one (update), both with exit 0 and no hint that the flag had
     * been ignored. Reading `work` as a display name would be a second guess of
     * the same kind, so that is not attempted.
     */
    private fun unreadableSelectorError(
        selector: CalendarSelector.Unreadable,
        args: OffloadArgs,
    ): NativeOffloadResult =
        NativeOffloadResult(
            2,
            OffloadOutput.formatBody(
                JSONObject()
                    .put("error", "calendar_bad_selector")
                    .put("selector", selector.raw)
                    .put(
                        "message",
                        "--calendar-id '${selector.raw}' is not a calendar id. Nothing was written — pass a numeric id (run `android-calendar calendars` for the list), or use --calendar <name> to match a display name.",
                    )
                    .toString(),
                args,
            ) + "\n",
        )

    /**
     * [da1034-A4] ISSUE-07: an explicit `--calendar` / `--calendar-id` that
     * matches no writable calendar. Refusing (exit 1, structured) is the fix:
     * the previous fall-through wrote the event into whatever calendar the
     * auto-pick heuristic preferred. `available` carries the ids the caller
     * could have meant, so the next call can be correct without a round trip.
     *
     * [dv15-A2] A-03: the clause that names the selector is now built from the
     * SAME [resolveCalendarSelector] classification the routing used, so it can
     * no longer print a bare `null` for a selector that was never written.
     */
    private fun calendarNotFoundError(args: OffloadArgs, cals: JSONArray): NativeOffloadResult {
        val idSel = args.getLong("calendar-id")
        val nameSel = args.get("calendar")
        val available = JSONArray()
        for (i in 0 until cals.length()) {
            val c = cals.getJSONObject(i)
            available.put(
                JSONObject()
                    .put("id", c.optLong("id", -1L))
                    .put("display_name", c.optString("display_name"))
                    .put("account_name", c.optString("account_name")),
            )
        }
        val described = when (val sel = resolveCalendarSelector(args.get("calendar-id"), args.get("calendar"))) {
            is CalendarSelector.ById -> "--calendar-id ${sel.id}"
            is CalendarSelector.ByName -> "--calendar '${sel.name}'"
            is CalendarSelector.Unreadable -> "--calendar-id '${sel.raw}'"
            CalendarSelector.Absent -> "the requested selector"
        }
        val body = JSONObject()
            .put("error", "calendar_not_found")
            .put(
                "message",
                "No writable calendar matches $described. Nothing was written — pick an id from `available`, or drop the flag to use the auto-picked calendar. Run `android-calendar calendars` to list them.",
            )
            .put("available", available)
        if (idSel != null) body.put("calendar_id", idSel)
        if (nameSel != null) body.put("calendar", nameSel)
        return NativeOffloadResult(1, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    private fun missingCalendarError(args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(
            1,
            OffloadOutput.formatBody(
                JSONObject()
                    .put("error", "calendar_no_account")
                    .put("message", "No writable calendar found. Ask the user to add a Google, Exchange, or local calendar account, or pass --calendar <name> / --calendar-id <id>. Run `android-calendar calendars` to list available calendars.")
                    .toString(),
                args,
            ) + "\n",
        )

    /** ISO 8601 with offset (e.g. 2026-04-26T13:51:00+08:00). Matches
     *  apple-calendar noff_format_date so cross-platform consumers see
     *  identical strings. */
    private fun formatIso(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(java.util.Date(ms))
    }

    // ── Calendar account selection ───────────────────────────────────────

    /**
     * Enumerate writable calendars (CALENDAR_ACCESS_LEVEL >= 500 = OWNER/CONTRIBUTOR).
     * Used by `create` for pick-best and by `calendars` subcommand for debugging.
     */
    private fun listWritableCalendars(): JSONArray {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val arr = JSONArray()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    arr.put(
                        JSONObject()
                            .put("id", c.getLong(0))
                            .put("display_name", c.getString(1) ?: "")
                            .put("account_name", c.getString(2) ?: "")
                            .put("account_type", c.getString(3) ?: "")
                            .put("access_level", c.getInt(4))
                            .put("is_primary", c.getInt(5) == 1),
                    )
                }
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "listWritableCalendars: ${e.message}")
        }
        return arr
    }

    /**
     * Pick the best calendar for new events:
     *   1. IS_PRIMARY=1 on a com.google account (avoids Samsung-Calendar-only inserts on One UI)
     *   2. Any com.google account
     *   3. Any IS_PRIMARY=1
     *   4. First writable calendar
     *
     * Takes the already-enumerated [cals] ([requireCalendarTarget] needs the
     * same list for its refusal payload), so a write performs one enumeration.
     */
    private fun pickWritableCalendar(cals: JSONArray): Long? {
        if (cals.length() == 0) return null
        fun score(obj: JSONObject): Int {
            var s = 0
            if (obj.optString("account_type") == "com.google") s += 100
            if (obj.optBoolean("is_primary")) s += 50
            // Prefer owner over contributor
            if (obj.optInt("access_level") >= CalendarContract.Calendars.CAL_ACCESS_OWNER) s += 10
            return s
        }
        var bestIdx = 0
        var bestScore = Int.MIN_VALUE
        for (i in 0 until cals.length()) {
            val s = score(cals.getJSONObject(i))
            if (s > bestScore) { bestScore = s; bestIdx = i }
        }
        return cals.getJSONObject(bestIdx).optLong("id", -1L).takeIf { it > 0 }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun providerBlockedJson(perm: String, e: SecurityException, args: OffloadArgs): String {
        val body = JSONObject().put("error", "provider_blocked")
            .put("message", "$perm is granted but the calendar provider refused the query (likely an OEM privacy layer like MIUI 'Other Permissions'). Ask the user to open app info and allow calendar access there. Underlying: ${e.message}")
            .toString()
        return OffloadOutput.formatBody(body, args) + "\n"
    }

    private fun providerErrorJson(e: Throwable?, args: OffloadArgs): String {
        val body = JSONObject().put("error", "provider_error")
            .put("message", e?.message ?: "Calendar provider failed or missing on this device.")
            .toString()
        return OffloadOutput.formatBody(body, args) + "\n"
    }

    /**
     * [da1017-D2] Snapshot of the stored event row used by update: whether it
     * is all-day (which decides the date-parsing zone) and its current UTC
     * span (which decides the DTEND repair). Null when the row cannot be read
     * at all — a missing READ_CALENDAR grant, an OEM privacy layer, or an id
     * that is not visible to this app. Callers must then fall back to the
     * flag-only behaviour rather than guess.
     */
    private data class StoredEventRow(val allDay: Boolean, val startMs: Long?, val endMs: Long?)

    private fun findEventRow(id: Long): StoredEventRow? = try {
        val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
            .appendPath(id.toString()).build()
        context.contentResolver.query(uri, EVENT_ROW_PROJECTION, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                StoredEventRow(
                    allDay = c.getInt(0) == 1,
                    startMs = if (c.isNull(1)) null else c.getLong(1),
                    endMs = if (c.isNull(2)) null else c.getLong(2),
                )
            } else null
        }
    } catch (e: Throwable) {
        // Not fatal: update proceeds with the flag-only interpretation.
        AppLogger.warning(TAG, "update: could not read event $id (${e.message})")
        null
    }

    private fun parseDate(s: String): Long? =
        parseDateInZone(s, System.currentTimeMillis(), TimeZone.getDefault())

    /**
     * [sweep1014-B25b] parseDate variant that resolves zone-less inputs
     * against UTC instead of the device zone. All-day events must be
     * anchored at UTC midnight (EVENT_TIMEZONE = "UTC" per CalendarContract
     * docs); parsing "2026-10-14" with the device zone in UTC+8 produced
     * local midnight (= 10-13T16:00Z), shifting the all-day event a day.
     */
    private fun parseDateUtc(s: String): Long? =
        parseDateInZone(s, System.currentTimeMillis(), TimeZone.getTimeZone("UTC"))

    /** Core date parser; [zone] only affects inputs without an explicit
     *  offset ('Z' and ±HH:MM formats always honor their own zone).
     *  internal + nowMs injected so it can be JVM-tested verbatim. */
    internal fun parseDateInZone(s: String, nowMs: Long, zone: TimeZone): Long? {
        // Relative offsets (apple-calendar parity): "-7d", "-2h", "+30m".
        val rel = Regex("""^([+-]?)(\d+)([dhm])$""").matchEntire(s.trim())
        if (rel != null) {
            val sign = if (rel.groupValues[1] == "-") -1L else 1L
            val n = rel.groupValues[2].toLong()
            val unitMs = when (rel.groupValues[3]) {
                "d" -> 24 * 60 * 60 * 1000L
                "h" -> 60 * 60 * 1000L
                "m" -> 60 * 1000L
                else -> return null
            }
            return nowMs + sign * n * unitMs
        }
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd",
        )
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US).apply {
                    timeZone = if (f.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else zone
                    // [dv16-A A-01] Strict, like the sessions funnel
                    // (parseSessionsDate): lenient mode silently ROLLS an
                    // out-of-range field over (`2026-02-30` → March 2), so a
                    // mistyped `--start` / `--end` answered for a DIFFERENT
                    // day instead of being refused. The relative shorthands
                    // (`-7d`) and the pattern list are untouched.
                    isLenient = false
                }
                return sdf.parse(s)?.time
            } catch (_: Throwable) {}
        }
        return null
    }

    companion object {
        private const val TAG = "CalendarOffload"


        // [da1016c-F F-B] A date-only input: exactly "YYYY-MM-DD" (no time, no
        // zone suffix, not a relative shorthand). Only this form gets the
        // end-of-day boundary treatment — "2026-10-16T10:00" and "-7d" keep
        // their explicit instants.
        private val DATE_ONLY: Regex = Regex("""^\d{4}-\d{2}-\d{2}$""")

        // [da1016c-F F-B] Local 00:00:00.000 of the day containing [ms].
        internal fun startOfLocalDay(ms: Long, zone: TimeZone): Long =
            Calendar.getInstance(zone).apply {
                timeInMillis = ms
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        // [da1016c-F F-B] Inclusive end of the day containing [ms]: 23:59:59.999.
        // add(DAY_OF_YEAR, 1) then -1 ms — the SessionsOffloadHandler.parseEndDate
        // idiom, DST-safe where a 24h-1ms constant is not (a 23/25-hour day is
        // covered in full).
        internal fun endOfLocalDay(ms: Long, zone: TimeZone): Long {
            val cal = Calendar.getInstance(zone).apply {
                timeInMillis = ms
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis - 1
        }

        // [da1016c-F F-B] Calendar day arithmetic (add(DAY_OF_YEAR)) — keeps the
        // wall-clock day stable across DST transitions where an n*24h offset
        // does not.
        internal fun addLocalDays(ms: Long, days: Int, zone: TimeZone): Long {
            val cal = Calendar.getInstance(zone).apply {
                timeInMillis = ms
                add(Calendar.DAY_OF_YEAR, days)
            }
            return cal.timeInMillis
        }

        // [da1016c-F F-B] End boundary alignment for a BARE-DATE --end: the
        // whole day is covered (23:59:59.999, inclusive) instead of the parsed
        // 00:00:00.000 — a bare-date --start --end X --end X used to be a
        // zero-width window that answered an empty day. Datetime/relative
        // inputs keep their explicit instant.
        internal fun alignBareDateEnd(parsedMs: Long, raw: String, zone: TimeZone): Long =
            if (DATE_ONLY.matches(raw.trim())) endOfLocalDay(parsedMs, zone) else parsedMs

        /**
         * [da1016c-F F-B] Pure decision core of [resolveDateRange] — device
         * inputs injected ([nowMs], [zone], parser), no clock/zone reads, so
         * the DST-day arms are pinned with literals in JVM tests.
         *
         * - `--today` → [today 00:00, next-day 00:00) via Calendar day
         *   arithmetic (the old +24h constant clipped/overshot a 23/25-hour
         *   DST day by exactly 1h).
         * - `--days N` → [N calendar days back at 00:00, now) via
         *   add(DAY_OF_YEAR, -N); N <= 0 degrades to 7 (unchanged). A
         *   supplied-but-unreadable `--days` ("7d", "last-week") returns null
         *   ([dv14-A3] ISSUE-05 — it used to read as absent and silently
         *   degrade to the last-24h window); [daysText] carries the raw string
         *   so the caller's `args.getInt` miss can be told apart from "the
         *   flag was never given".
         * - `--start/--end` explicit; missing sides default to last 24h
         *   (unchanged); parse failure → null (error semantics unchanged);
         *   start > end → swapped (unchanged).
         * - a bare-date --end is inclusive end-of-day via [alignBareDateEnd].
         */
        internal fun resolveRangePure(
            nowMs: Long,
            zone: TimeZone,
            todayFlag: Boolean,
            daysRaw: Int?,
            startRaw: String?,
            endRaw: String?,
            daysText: String? = null,
            parse: (String, Long, TimeZone) -> Long?,
        ): Pair<Long, Long>? {
            if (todayFlag) {
                val s = startOfLocalDay(nowMs, zone)
                val e = startOfLocalDay(addLocalDays(nowMs, 1, zone), zone)
                return s to e
            }
            // [dv14-A3] ISSUE-05: a supplied-but-unreadable `--days` ("7d",
            // "last-week") used to read as absent (getInt → toIntOrNull → null)
            // and silently degraded to the last-24h window, dropping the days
            // the caller named. Absent (`--days` given no value, i.e. the flag
            // parsed as boolean) and non-positive integers keep their
            // historical readings (N <= 0 degrades to 7).
            if (daysText != null && daysText.isNotBlank() && daysText.toIntOrNull() == null) {
                return null
            }
            daysRaw?.let { rawDays ->
                val n = if (rawDays <= 0) 7 else rawDays
                return startOfLocalDay(addLocalDays(nowMs, -n, zone), zone) to nowMs
            }
            val startMs = if (startRaw != null) parse(startRaw, nowMs, zone) ?: return null
                          else nowMs - 24 * 60 * 60 * 1000L
            val endMs = if (endRaw != null) {
                val parsed = parse(endRaw, nowMs, zone) ?: return null
                alignBareDateEnd(parsed, endRaw, zone)
            } else nowMs
            // EventStore predicates don't tolerate start > end on iOS; mirror that.
            return if (startMs > endMs) endMs to startMs else startMs to endMs
        }

        // [sweep1014-B25a] Pure window-intersection predicate for a calendar
        // instance (or event) against the query window: the occurrence
        // overlaps the window iff it starts before the window ends AND ends
        // after the window starts — same shape as doFreebusy's
        // `DTSTART < ?end AND DTEND > ?start`. Internal+pure so the
        // semantics are JVM-testable; [instancesWindowSelection] must stay
        // in sync with this predicate.
        internal fun instanceOverlapsWindow(
            instStartMs: Long,
            instEndMs: Long,
            winStartMs: Long,
            winEndMs: Long,
        ): Boolean = instStartMs < winEndMs && instEndMs > winStartMs

        // [sweep1014-B25a] Selection + selectionArgs for the Instances
        // window-intersection query. Args order is load-bearing: winEndMs
        // binds to BEGIN's placeholder, winStartMs to END's.
        internal fun instancesWindowSelection(winStartMs: Long, winEndMs: Long): Pair<String, Array<String>> =
            "${CalendarContract.Instances.BEGIN} < ? AND ${CalendarContract.Instances.END} > ?" to
                arrayOf(winEndMs.toString(), winStartMs.toString())

        // [da1017-D1] Projection for the freebusy Instances query. The index
        // order is an implicit contract with the positional cursor reads in
        // doFreebusy: 0 TITLE, 1 BEGIN, 2 END, 3 ALL_DAY. Kept as a function
        // so the exact column list can be executed against a fixture SQLite
        // schema in JVM probes (a wrong column name fails there loudly instead
        // of coming back null on-device).
        internal fun freebusyInstanceProjection(): Array<String> = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )

        // [da1017-D2] Projection for the stored-row snapshot read by update:
        // 0 ALL_DAY, 1 DTSTART, 2 DTEND (positional reads in findEventRow).
        private val EVENT_ROW_PROJECTION = arrayOf(
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
        )

        /**
         * [da1017-D2] Which date semantics an update must use: an explicit
         * `--all-day` flag, or the stored event already being all-day (moving
         * an all-day event has to stay on UTC-midnight boundaries even when
         * the caller left the flag out). [storedAllDay] is null when the row
         * could not be read — then only the flag decides, i.e. the previous
         * behaviour, never a guess. Pure so the truth table is JVM-testable.
         */
        internal fun updateUsesAllDaySemantics(explicitAllDay: Boolean, storedAllDay: Boolean?): Boolean =
            explicitAllDay || storedAllDay == true

        /**
         * [dv14-A3] ISSUE-06: the write-path `satisfied` predicate. Mirrors
         * ContactsOffloadHandler.hasPermission(needsWrite): a WRITE needs READ
         * too, because every calendar write first READS the provider —
         * requireCalendarTarget → listWritableCalendars queries
         * Calendars.CONTENT_URI and findEventRow queries Events.CONTENT_URI,
         * and with only WRITE_CALENDAR granted those queries throw
         * SecurityException that the read helpers swallow into an empty
         * answer ⇒ the write paths used to report `calendar_no_account` /
         * `not_found` while satisfied() claimed the permission was fine.
         * Pure so the truth table is JVM-testable.
         */
        internal fun calendarSatisfied(read: Boolean, write: Boolean, needsWrite: Boolean): Boolean =
            if (needsWrite) read && write else read

        /**
         * [da1017-D2] DTEND to write when an all-day event's DTSTART moves
         * without `--end`. Returns null when DTSTART did not move (no repair
         * needed).
         *
         * Writing DTSTART alone leaves DTEND behind, and the provider then
         * hides the event: instance expansion skips any row whose END precedes
         * the window start (CalendarInstancesHelper: `if (dtendMillis < begin)
         * continue`), so an all-day event pushed later than its old DTEND
         * disappears from both `list` and `freebusy` — measured on-device with
         * an inverted probe row (dtstart=11-26T00:00Z > dtend=11-25T00:00Z):
         * no instance in the 11-25T23:00Z..11-27T00:00Z window, `list`
         * answered events=[].
         *
         * The stored day-span is preserved rather than collapsed: the provider
         * stores the canonical single-day row as dtstart == dtend, so a span of
         * 0 reproduces exactly the shape doCreate + fixAllDayTime() produce,
         * while a multi-day all-day event keeps its length. Unknown span
         * (unreadable row) degrades to 0 — the canonical single-day shape.
         */
        internal fun allDayEndRepairMs(storedStartMs: Long?, storedEndMs: Long?, newStartMs: Long?): Long? {
            if (newStartMs == null) return null
            val span = if (storedStartMs != null && storedEndMs != null && storedEndMs > storedStartMs) {
                storedEndMs - storedStartMs
            } else 0L
            return newStartMs + span
        }

        /**
         * [da1031-C] TIMED counterpart of [allDayEndRepairMs]: DTEND to write
         * when a timed event's DTSTART moves without `--end`. Same shape —
         * keep the stored duration; a degenerate stored span (end <= start,
         * e.g. a legacy inverted row) falls back to doCreate's 1h default
         * rather than manufacturing a 0-duration row. A row whose DTSTART or
         * DTEND could not be read returns null: the caller then leaves DTEND
         * alone instead of inventing a window we cannot verify.
         */
        internal fun timedEndRepairMs(storedStartMs: Long?, storedEndMs: Long?, newStartMs: Long): Long? {
            if (storedStartMs == null || storedEndMs == null) return null
            val span = storedEndMs - storedStartMs
            if (span <= 0) return newStartMs + 60L * 60 * 1000
            return newStartMs + span
        }

        /**
         * [da1031-C] A TIMED `[start, end)` is contradictory when it ends at or
         * before it starts — the provider then hides the row from
         * list/instances/freebusy (same filter as the all-day case fixed in
         * [da1017-D2]).
         *
         * [meta1028-D2 F-04] ALL-DAY rows are NOT exempt from the guard, only
         * from its ZERO-WIDTH half: the canonical single-day row is stored as
         * dtstart == dtend ([allDayEndRepairMs]), so `end == start` is correct
         * there — but `end < start` is exactly the inverted row [da1017-D2]
         * measured disappearing from `list`/`freebusy`, and the old blanket
         * `!isAllDay` exemption let `create --all-day --start D20 --end D15`
         * answer `created: true` while writing that row. Verified against both
         * producers: `createEndMs` gives an all-day caller-without-`--end` a
         * +1h span (accepted), and a caller-stated end is used verbatim.
         *
         * Pure so the truth table is JVM-testable next to
         * [updateUsesAllDaySemantics].
         */
        internal fun invertedTimedRange(isAllDay: Boolean, startMs: Long?, endMs: Long?): Boolean {
            if (startMs == null || endMs == null) return false
            // All-day: dtstart == dtend is the canonical row; only a NEGATIVE
            // span is a contradiction.
            if (isAllDay) return endMs < startMs
            // Timed: an empty `[start, start)` range is degenerate too.
            return endMs <= startMs
        }

        /**
         * [da1032-C] doCreate's DTEND resolution. Three cases, and the whole
         * point is that the middle two cannot collapse:
         *
         *  • [rawEnd] carries no value — the caller left `--end` OUT (OffloadArgs
         *    hands back null for a flag with no usable value, including a bare
         *    `--end` followed by another flag) → the documented 1h default.
         *    Unchanged from before this fix, and pinned by
         *    CalendarFreebusyInstancesTest + timedEndRepairMs.
         *  • [rawEnd] has a value and [parse] answers — the caller's exact instant.
         *  • [rawEnd] has a value and [parse] answers null — the caller TYPED an
         *    end and it is not a date this command accepts → null, which the
         *    caller turns into a refusal (the shape doUpdate/doFreebusy use).
         *
         * The old `args.get("end")?.let { parse(it) } ?: (startMs + 1h)` merged
         * the first and third case: a typo'd `--end` silently created a
         * one-hour event at the start and answered success. Pure so the truth
         * table is JVM-testable next to [timedEndRepairMs].
         */
        internal fun createEndMs(rawEnd: String?, startMs: Long, parse: (String) -> Long?): Long? {
            if (rawEnd.isNullOrBlank()) return startMs + 60L * 60 * 1000
            return parse(rawEnd)
        }


        private const val HELP = """android-calendar — list, create, update, delete events; query free/busy
                                    (mirrors apple-calendar)

Usage:
  android-calendar                  Same as `list` (default subcommand)
  android-calendar list [--today | --days N | --start S --end E]
                        [--limit N] [--calendar NAME]
  android-calendar create --title T --start S [--end E]
                         [--notes N] [--location L] [--all-day]
                         [--alarm <minutes>] [--calendar NAME | --calendar-id ID]
  android-calendar update --id <event_id> [--title ...] [--start ...] [--end ...]
                         [--notes ...] [--location ...] [--all-day] [--alarm <minutes>]
                         [--calendar NAME | --calendar-id ID]
  android-calendar delete --id <event_id>
  android-calendar freebusy --start <ISO> --end <ISO>
  android-calendar calendars             List writable calendars (debugging)

Dates accept ISO 8601 (YYYY-MM-DDThh:mm[:ss][Z|±HH:MM]) or relative
shorthand (-7d, -2h, +30m). YYYY-MM-DD is treated as midnight local,
except for all-day events: those are stored on UTC-midnight boundaries
(create --all-day, or update of an event that is already all-day), so
their dates are parsed in UTC instead. For list/freebusy windows a
bare YYYY-MM-DD --end covers the WHOLE day (inclusive, through
23:59:59.999 local), so `--start D --end D` returns that day.
--today spans the local calendar day (DST-safe); --days N spans the
last N calendar days.

Aliases for backwards compatibility:
  --max ↔ --limit
  --description ↔ --notes

Errors return JSON: {"error":"...","message":"..."}.
"""
    }
}
