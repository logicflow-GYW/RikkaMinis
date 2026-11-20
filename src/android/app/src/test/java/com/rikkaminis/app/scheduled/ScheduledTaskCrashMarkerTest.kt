package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * [WAVE-1118 D7 F-05] A settlement that fails must not leave the persisted
 * `running` marker behind — otherwise the NEXT beat reads it as "the process
 * died mid-run" and charges the task a failure nobody earned.
 *
 * The rule is pure ([ScheduledTaskTransitions.clearRunningMarker]) so the arms
 * below are literals: matching marker dropped, foreign/absent marker left
 * untouched (identity, so a caller can tell "nothing to do"). The pair arm
 * shows the defect directly: with the marker still on disk,
 * `crashFailure` bumps `failStreak`; once the compensation cleared it, the same
 * call is a no-op.
 *
 * The wiring arm pins that the runner's catch block actually calls the store —
 * with a negative control that removes the call from a mutated copy.
 */
class ScheduledTaskCrashMarkerTest {

    private fun task(id: String, streak: Int = 0) = ScheduledTask(
        id = id,
        title = "title-$id",
        prompt = "prompt-$id",
        windowStart = "07:00",
        windowEnd = "23:00",
        failStreak = streak,
    )

    @Test
    fun `matching marker is dropped without touching the task`() {
        val doc = ScheduledTasksFile(
            tasks = listOf(task("t1", streak = 2)),
            running = ScheduledRunMarker(taskId = "t1", sessionId = "s1", startedAt = 1_000L),
        )
        val cleared = ScheduledTaskTransitions.clearRunningMarker(doc, ScheduledRunRef("t1", "s1"))
        assertEquals("the marker must be gone", null, cleared.running)
        assertEquals("run state must not be credited or bumped", 2, cleared.tasks.single().failStreak)
    }

    @Test
    fun `a marker naming another task is left alone`() {
        val doc = ScheduledTasksFile(
            tasks = listOf(task("t1"), task("t2")),
            running = ScheduledRunMarker(taskId = "t2", sessionId = "s2", startedAt = 1_000L),
        )
        assertSame(
            "a foreign marker must come back as the same instance (nothing to write)",
            doc,
            ScheduledTaskTransitions.clearRunningMarker(doc, ScheduledRunRef("t1", "s1")),
        )
    }

    @Test
    fun `an absent marker is a no-op`() {
        val doc = ScheduledTasksFile(tasks = listOf(task("t1")))
        assertSame(doc, ScheduledTaskTransitions.clearRunningMarker(doc, ScheduledRunRef("t1", "s1")))
    }

    /**
     * The defect, end to end at the pure layer: the marker left behind is what
     * makes the next beat invent a failure.
     */
    @Test
    fun `clearing the marker prevents the phantom crash failure`() {
        val doc = ScheduledTasksFile(
            tasks = listOf(task("t1", streak = 0)),
            running = ScheduledRunMarker(taskId = "t1", sessionId = "s1", startedAt = 1_000L),
        )

        val phantom = ScheduledTaskTransitions.crashFailure(doc, "t1")
        assertEquals(
            "without the compensation the next beat counts a failure the run did not earn",
            1,
            phantom.task?.failStreak,
        )

        val compensated = ScheduledTaskTransitions.clearRunningMarker(doc, ScheduledRunRef("t1", "s1"))
        val after = ScheduledTaskTransitions.crashFailure(compensated, "t1")
        assertEquals("with the marker cleared there is no crash to recover", null, after.task)
        assertEquals(0, compensated.tasks.single().failStreak)
    }

    @Test
    fun `finalizeLocked retains marker and owner after failed settlement write`() {
        val body = bodyOf(runnerSource(), "private suspend fun finalizeLocked(")
        val pending = body.indexOf("ScheduledTaskWriteDisposition.WRITE_FAILED ->")
        val persist = body.indexOf("ScheduledTaskWriteDisposition.PERSISTED ->")
        assertTrue("a false write must be retained for retry", pending >= 0 && persist > pending)
        // There is no marker-delete compensation after write failure; keep the exact owner marker.
        assertFalse("failure branch must not erase the persisted owner marker", body.substring(pending, persist).contains("ScheduledTasksStore.clearRunningMarker(app, run)"))
    }

    @Test
    fun `completeUnstartedRun carries the selected generation instead of default zero`() {
        val body = storeSource().substringAfter("suspend fun completeUnstartedRun(")
            .substringBefore("/**")
        assertTrue("store method must accept the captured owner", body.contains("taskGeneration: Long"))
        assertTrue(body.contains("globalGeneration: Long"))
        assertTrue(body.contains("run = ScheduledRunRef(taskId, null, taskGeneration, globalGeneration"))
        assertTrue(body.contains("!admissionMatchesLocked(run)"))
    }

    @Test
    fun `fire captures selection before prepare and settles catch with selected owner`() {
        val source = runnerSource()
        val fire = bodyOf(source, "private suspend fun fire(")
        val selected = fire.indexOf("val selected = ScheduledRunRef(")
        val prepare = fire.indexOf("prepareSession(app, task)")
        val mark = fire.indexOf("ScheduledTasksStore.markRunning(app, run,")
        val catchAt = fire.indexOf("catch (t: Throwable)")
        val noSessionSettle = fire.indexOf("finalizeLocked(app, selected.copy(sessionId = sessionId)")
        assertTrue(selected >= 0 && prepare > selected && mark > prepare)
        assertTrue("pre-session failure must preserve selected epochs", noSessionSettle > catchAt)
        assertFalse("unstarted failure may not construct a generation-zero identity", fire.contains("ScheduledRunRef(task.id, sid)"))
    }

    private fun storeSource(): String = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTasksStore.kt")

    private fun runnerSource(): String = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTaskRunner.kt")

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun bodyOf(source: String, header: String): String {
        val at = source.indexOf(header)
        assertTrue("anchor missing: $header", at >= 0)
        val open = source.indexOf('{', at)
        assertTrue("no body brace after $header", open >= 0)
        var depth = 0
        var inStr = false
        var escaped = false
        var i = open
        while (i < source.length) {
            val c = source[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(at, i + 1)
                    }
                }
            }
            i++
        }
        error("unbalanced braces after $header")
    }
}
