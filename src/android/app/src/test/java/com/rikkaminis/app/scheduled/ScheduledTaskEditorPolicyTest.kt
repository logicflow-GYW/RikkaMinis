package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RC-U13] JVM test for the editor's save-time decisions.
 *
 * Two friction reports, one file:
 *  - U-13 "得做两遍": a brand-new task was hard-coded OFF (§5.4, deliberate) and
 *    the editor had no switch, so creating a task meant save → back → row switch
 *    (→ master switch on a fresh install).
 *  - U-14 "不知道做没做成": a refused write handed back the PRE-write document,
 *    which still contains an edited task, so the editor's existence check said
 *    "saved" and closed on a discarded change.
 *
 * The default stays OFF — that part is the design, not the defect. These arms
 * pin that the opt-in is the ONLY thing that flips it, and that an edit never
 * silently re-arms a task the user (or the fuse) turned off.
 */
class ScheduledTaskEditorPolicyTest {

    @Test
    fun aNewTaskStaysOffUnlessTheUserOptedIn() {
        assertFalse(ScheduledTaskEditorPolicy.enabledOnSave(priorEnabled = null, enableAfterSave = false))
        assertTrue(ScheduledTaskEditorPolicy.enabledOnSave(priorEnabled = null, enableAfterSave = true))
    }

    @Test
    fun anEditKeepsTheStoredStateAndIgnoresTheNewTaskSwitch() {
        // The switch is not even rendered for an edit; if it were wired in
        // anyway, saving an edit of a disabled task would arm it — the exact
        // "silently spends quota" failure §5.4 exists to prevent.
        assertFalse(ScheduledTaskEditorPolicy.enabledOnSave(priorEnabled = false, enableAfterSave = true))
        assertTrue(ScheduledTaskEditorPolicy.enabledOnSave(priorEnabled = true, enableAfterSave = false))
    }

    @Test
    fun aGlobalOffSaveSaysSoEvenWhenTheTaskItselfIsOn() {
        // The wider gate wins: with the master switch off, telling the user
        // "the task is off" sends them to a row that already looks enabled.
        assertEquals(
            ScheduledTaskEditorPolicy.SaveNotice.GLOBAL_OFF,
            ScheduledTaskEditorPolicy.noticeAfterSave(taskEnabled = true, globalEnabled = false),
        )
    }

    @Test
    fun aSavedButDisabledTaskIsCalledOut() {
        assertEquals(
            ScheduledTaskEditorPolicy.SaveNotice.TASK_OFF,
            ScheduledTaskEditorPolicy.noticeAfterSave(taskEnabled = false, globalEnabled = true),
        )
    }

    @Test
    fun anEnabledTaskUnderAnEnabledMasterSaysNothing() {
        assertEquals(
            ScheduledTaskEditorPolicy.SaveNotice.NONE,
            ScheduledTaskEditorPolicy.noticeAfterSave(taskEnabled = true, globalEnabled = true),
        )
    }

    @Test
    fun bothOffIsReportedAsTheGlobalProblem() {
        // Two hints would be noise; the master switch is the one that has to
        // move first, and fixing it reveals the task-level one anyway.
        assertEquals(
            ScheduledTaskEditorPolicy.SaveNotice.GLOBAL_OFF,
            ScheduledTaskEditorPolicy.noticeAfterSave(taskEnabled = false, globalEnabled = false),
        )
    }
}
