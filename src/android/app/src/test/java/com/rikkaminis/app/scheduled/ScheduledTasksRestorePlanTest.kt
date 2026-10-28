package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-1028 / A-16 = B-5] The restore floor for the backup's tasks section.
 *
 * Two walkthroughs found the same defect independently: a package whose
 * "scheduledTasks" section is present-but-empty (or whose entries the codec
 * drops) overwrote this device's live task list and reported success, and the
 * section's `version` was write-only.
 *
 * Every expectation below is a literal — deliberately NOT derived from
 * `ScheduledTasksCodec.VERSION`, so bumping the version cannot silently keep a
 * test green (the JVM harness self-reference trap: expectations computed from
 * the constant under test mutate together with it).
 */
class ScheduledTasksRestorePlanTest {

    private val supported = 1

    @Test
    fun `empty incoming set with live tasks is refused`() {
        val outcome = ScheduledTasksRestorePlan.decide(
            declaredVersion = 1,
            supportedVersion = supported,
            rawEntries = 0,
            parsedTasks = 0,
            liveTasks = 2,
        )
        assertFalse("an empty section must not clear the device's tasks", outcome.applied)
        assertNotNull("the refusal has to reach the user", outcome.notice)
        assertTrue(outcome.notice!!.contains("2 task"))
    }

    @Test
    fun `entries that all fail to parse count as empty and are refused`() {
        val outcome = ScheduledTasksRestorePlan.decide(
            declaredVersion = 1,
            supportedVersion = supported,
            rawEntries = 3,      // three entries present…
            parsedTasks = 0,     // …none usable (blank ids drop in the codec)
            liveTasks = 2,
        )
        assertFalse(outcome.applied)
        assertTrue(outcome.notice!!.contains("3 entries"))
    }

    @Test
    fun `empty incoming set on an empty device applies`() {
        val outcome = ScheduledTasksRestorePlan.decide(
            declaredVersion = 1,
            supportedVersion = supported,
            rawEntries = 0,
            parsedTasks = 0,
            liveTasks = 0,
        )
        assertTrue("first restore onto a fresh install must behave like before", outcome.applied)
        assertNull(outcome.notice)
    }

    @Test
    fun `unknown future version is refused and never parsed as current`() {
        val outcome = ScheduledTasksRestorePlan.decide(
            declaredVersion = 2,
            supportedVersion = supported,
            rawEntries = 3,
            parsedTasks = 3,
            liveTasks = 2,
        )
        assertFalse(outcome.applied)
        assertTrue(outcome.notice!!.contains("v2"))
    }

    @Test
    fun `unknown lower version is refused too`() {
        val outcome = ScheduledTasksRestorePlan.decide(
            declaredVersion = 0,
            supportedVersion = supported,
            rawEntries = 3,
            parsedTasks = 3,
            liveTasks = 0,
        )
        assertFalse(outcome.applied)
    }

    @Test
    fun `missing version key is treated as legacy and parsed`() {
        val outcome = ScheduledTasksRestorePlan.decide(
            declaredVersion = null,
            supportedVersion = supported,
            rawEntries = 2,
            parsedTasks = 2,
            liveTasks = 7,
        )
        assertTrue(outcome.applied)
        assertNull(outcome.notice)
    }

    @Test
    fun `non-empty incoming set replaces live tasks and reports dropped entries`() {
        val clean = ScheduledTasksRestorePlan.decide(
            declaredVersion = 1,
            supportedVersion = supported,
            rawEntries = 2,
            parsedTasks = 2,
            liveTasks = 5,
        )
        assertTrue(clean.applied)
        assertNull(clean.notice)

        val lossy = ScheduledTasksRestorePlan.decide(
            declaredVersion = 1,
            supportedVersion = supported,
            rawEntries = 3,
            parsedTasks = 1,
            liveTasks = 5,
        )
        assertTrue(lossy.applied)
        assertTrue("a partially parsed section must say so", lossy.notice!!.contains("2 unparseable"))
    }

    @Test
    fun `supported version constant is the one this test documented`() {
        // Guards the pairing above: if the codec ever moves to v2, the plan's
        // "refuse v2" case becomes "accept v2" and the refusal test above would
        // be testing the wrong thing — so this fails loudly instead.
        assertEquals(1, ScheduledTasksCodec.VERSION)
    }
}
