package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RC-U01] JVM test for the automatic-backup outcome classification.
 *
 * The defect this pins: `AutoBackupManager.runNow` returned `Unit` and each of
 * its four failure points (concurrent skip / unreadable export fields /
 * external mirror / WebDAV push) only wrote a `Log.w`. The settings screen then
 * popped the same "Backup saved" toast for all of them, so a user learned the
 * backup was incomplete only on restore day.
 *
 * The manager itself needs a `MinisApp` and cannot run on the JVM, so the
 * decision it delegates to — [AutoBackupRunOutcomePolicy.classify] — is graded
 * here with hard-coded expectations (never derived from production constants:
 * a mutation that changes the thresholds must flip these arms).
 */
class AutoBackupRunOutcomeTest {

    @Test
    fun cleanRunIsSuccess() {
        assertEquals(
            AutoBackupRunOutcome.Success,
            AutoBackupRunOutcomePolicy.classify(
                unreadableFields = 0,
                externalMirrorFailed = false,
                remotePushFailed = false,
            ),
        )
    }

    @Test
    fun unreadableFieldsAloneMakeItPartial() {
        val outcome = AutoBackupRunOutcomePolicy.classify(
            unreadableFields = 3,
            externalMirrorFailed = false,
            remotePushFailed = false,
        )
        assertTrue("a document that lost fields is not Success", outcome is AutoBackupRunOutcome.Partial)
        outcome as AutoBackupRunOutcome.Partial
        assertEquals(3, outcome.unreadableFields)
        assertFalse(outcome.externalMirrorFailed)
        assertFalse(outcome.remotePushFailed)
    }

    @Test
    fun externalMirrorFailureAloneMakesItPartial() {
        val outcome = AutoBackupRunOutcomePolicy.classify(
            unreadableFields = 0,
            externalMirrorFailed = true,
            remotePushFailed = false,
        )
        assertTrue(outcome is AutoBackupRunOutcome.Partial)
        outcome as AutoBackupRunOutcome.Partial
        assertEquals(0, outcome.unreadableFields)
        assertTrue(outcome.externalMirrorFailed)
        assertFalse(outcome.remotePushFailed)
    }

    @Test
    fun remotePushFailureAloneMakesItPartial() {
        val outcome = AutoBackupRunOutcomePolicy.classify(
            unreadableFields = 0,
            externalMirrorFailed = false,
            remotePushFailed = true,
        )
        assertTrue(outcome is AutoBackupRunOutcome.Partial)
        outcome as AutoBackupRunOutcome.Partial
        assertFalse(outcome.externalMirrorFailed)
        assertTrue(outcome.remotePushFailed)
    }

    @Test
    fun everyDegradedLayerIsReportedNotJustTheFirst() {
        // Reporting only the first failure is the same class of defect as
        // swallowing them: the user fixes the remote push and still has a
        // document missing fields.
        val outcome = AutoBackupRunOutcomePolicy.classify(
            unreadableFields = 7,
            externalMirrorFailed = true,
            remotePushFailed = true,
        )
        outcome as AutoBackupRunOutcome.Partial
        assertEquals(7, outcome.unreadableFields)
        assertTrue(outcome.externalMirrorFailed)
        assertTrue(outcome.remotePushFailed)
    }

    @Test
    fun skippedAndFailedAreDistinctOutcomes() {
        // "nothing happened because another run holds the gate" and "it broke"
        // owe the user different messages; folding them together is what made
        // the toast lie in the first place.
        assertNotEquals(
            AutoBackupRunOutcome.Skipped,
            AutoBackupRunOutcome.Failed("disk full"),
        )
        assertNotEquals(AutoBackupRunOutcome.Skipped, AutoBackupRunOutcome.Success)
        assertNotEquals(
            AutoBackupRunOutcome.Failed("disk full") as Any,
            AutoBackupRunOutcome.Failed("permission denied") as Any,
        )
        assertEquals(
            "the reason travels with the failure so the UI can show it",
            "disk full",
            (AutoBackupRunOutcome.Failed("disk full") as AutoBackupRunOutcome.Failed).reason,
        )
    }

    @Test
    fun aNegativeFieldCountIsNotReported() {
        // exportToWriter cannot answer negative, but a coerced-at-display value
        // is cheaper than a message reading "-1 field(s) could not be read".
        val outcome = AutoBackupRunOutcomePolicy.classify(
            unreadableFields = -4,
            externalMirrorFailed = false,
            remotePushFailed = false,
        )
        assertEquals(AutoBackupRunOutcome.Success, outcome)
    }
}
