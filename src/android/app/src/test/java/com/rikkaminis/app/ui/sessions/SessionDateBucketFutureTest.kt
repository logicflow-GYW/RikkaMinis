package com.rikkaminis.app.ui.sessions

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [R4-V F-14] Session-date buckets when the timestamp is in the FUTURE.
 *
 * Both functions compared `now - timestamp` against positive thresholds, so a
 * negative difference satisfied *all* of them: a session dated tomorrow was
 * filed under "This Week" (a past bucket) and labelled "Just now", and the
 * weekday/date arms (`days < 7`) could never be reached for it either.
 *
 * The rule pinned here: a future timestamp is the NEWEST bucket in the section
 * list ([DatePeriod.TODAY], i.e. it cannot slide down into a past section) and
 * the ABSOLUTE date in the label (never "Just now" — that is the one label we
 * can prove false).
 *
 * Cases are anchored at local noon of "today" and stepped in whole days, so
 * they hold in any time zone without DST arithmetic leaking into the table.
 */
class SessionDateBucketFutureTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val hourMs = 60L * 60 * 1000

    /** Local noon today — far from every day boundary. */
    private fun noonToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ── the defect itself ────────────────────────────────────────────────

    @Test
    fun `a future timestamp is the newest section, not a past one`() {
        val now = noonToday()
        assertEquals(DatePeriod.TODAY, datePeriod(now + dayMs, now))
        assertEquals(DatePeriod.TODAY, datePeriod(now + 60_000L, now))
        // 30 days ahead used to fall through to THIS_MONTH as well
        assertEquals(DatePeriod.TODAY, datePeriod(now + 30 * dayMs, now))
    }

    @Test
    fun `a future timestamp never reads just now`() {
        val now = noonToday()
        assertEquals(RelativeDateBucket.ABSOLUTE_DATE, relativeDateBucket(now, now + 60_000L))
        assertEquals(RelativeDateBucket.ABSOLUTE_DATE, relativeDateBucket(now, now + 5 * 60_000L))
        assertEquals(RelativeDateBucket.ABSOLUTE_DATE, relativeDateBucket(now, now + 5 * hourMs))
        assertEquals(RelativeDateBucket.ABSOLUTE_DATE, relativeDateBucket(now, now + 3 * dayMs))
    }

    // ── the past table must not move ─────────────────────────────────────

    @Test
    fun `past sections keep their boundaries`() {
        val now = noonToday()
        assertEquals(DatePeriod.TODAY, datePeriod(now, now))
        assertEquals(DatePeriod.YESTERDAY, datePeriod(now - dayMs, now))
        assertEquals(DatePeriod.THIS_WEEK, datePeriod(now - 3 * dayMs, now))
        assertEquals(DatePeriod.THIS_MONTH, datePeriod(now - 10 * dayMs, now))
        assertEquals(DatePeriod.EARLIER, datePeriod(now - 40 * dayMs, now))
    }

    @Test
    fun `past labels keep their boundaries`() {
        val now = noonToday()
        assertEquals(RelativeDateBucket.JUST_NOW, relativeDateBucket(now, now - 30_000L))
        assertEquals(RelativeDateBucket.MINUTES_AGO, relativeDateBucket(now, now - 5 * 60_000L))
        assertEquals(RelativeDateBucket.HOURS_AGO, relativeDateBucket(now, now - 5 * hourMs))
        assertEquals(RelativeDateBucket.YESTERDAY, relativeDateBucket(now, now - dayMs))
        assertEquals(RelativeDateBucket.WEEKDAY, relativeDateBucket(now, now - 3 * dayMs))
        assertEquals(RelativeDateBucket.ABSOLUTE_DATE, relativeDateBucket(now, now - 40 * dayMs))
    }
}
