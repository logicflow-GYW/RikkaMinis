package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r2-backup-1119 F-20] A restore that blew up mid-way comes back as a
 * normal-looking `ImportResult` with `fatal` set (the partial write stays on
 * disk so the user can roll back). The in-app report already treats that as a
 * failure; the tray notification must too, otherwise a user who left the
 * screen reads "restored" for a half-applied config and never rolls back.
 *
 * Both directions are pinned: a fix that simply hard-codes "failed" would be
 * just as wrong, since every successful restore would then look broken.
 */
class BackupRestoreNotifyOutcomeTest {

    private val headline = "Restore failed mid-way — roll back from a snapshot."

    @Test
    fun `fatal import turns the notification into a failure that carries the reason`() {
        val out = restoreNotifyOutcome(
            summary = "Restored 3 providers and 1 groups.",
            fatalHeadline = headline,
            fatal = "SQLiteException: disk I/O error",
        )
        assertFalse("a fatal restore must not report success", out.ok)
        assertTrue("body must carry the rollback hint: ${out.body}", out.body.contains(headline))
        assertTrue(
            "body must carry the fatal reason: ${out.body}",
            out.body.contains("SQLiteException: disk I/O error"),
        )
        assertTrue(
            "the success summary must not survive a fatal restore: ${out.body}",
            !out.body.contains("Restored 3 providers"),
        )
    }

    @Test
    fun `clean import keeps the success summary`() {
        val out = restoreNotifyOutcome(
            summary = "Restored 3 providers and 1 groups.",
            fatalHeadline = headline,
            fatal = null,
        )
        assertTrue("a clean restore is a success", out.ok)
        assertEquals("Restored 3 providers and 1 groups.", out.body)
    }

    @Test
    fun `non null blank fatal still requires rollback`() {
        for (fatal in listOf("", "  ")) {
            val out = restoreNotifyOutcome(
                summary = "Restored 0 providers and 0 groups.",
                fatalHeadline = headline,
                fatal = fatal,
            )
            assertFalse(out.ok)
            assertTrue(out.body.contains("roll back"))
        }
    }
}
