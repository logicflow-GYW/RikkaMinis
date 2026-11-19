package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [fix/r2-backup-1119 F-21] A skill's enabled flag is user intent that lives
 * outside SKILL.md, so a Stage-5 restore has to reapply the backup's value.
 *
 * The old code wrote only the disable direction (`isEnabled == false`), so a
 * backup that said "enabled" could never turn a locally disabled skill back
 * on — a one-way ratchet that made "restore this backup" fail to reproduce the
 * state it was taken from. Both directions are pinned here on purpose: a fix
 * that just flips the hard-coded direction would pass a one-sided test and
 * ratchet the other way instead.
 */
class SkillEnableRestoreTest {

    @Test
    fun `locally disabled skill is re-enabled by a backup that says enabled`() {
        assertEquals(true, skillEnableWriteTarget(current = false, backupSaysEnabled = true))
    }

    @Test
    fun `locally enabled skill is disabled by a backup that says disabled`() {
        assertEquals(false, skillEnableWriteTarget(current = true, backupSaysEnabled = false))
    }

    @Test
    fun `a matching flag writes nothing`() {
        assertNull(skillEnableWriteTarget(current = true, backupSaysEnabled = true))
        assertNull(skillEnableWriteTarget(current = false, backupSaysEnabled = false))
    }
}
