package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
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
        val cleared = ScheduledTaskTransitions.clearRunningMarker(doc, "t1")
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
            ScheduledTaskTransitions.clearRunningMarker(doc, "t1"),
        )
    }

    @Test
    fun `an absent marker is a no-op`() {
        val doc = ScheduledTasksFile(tasks = listOf(task("t1")))
        assertSame(doc, ScheduledTaskTransitions.clearRunningMarker(doc, "t1"))
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

        val compensated = ScheduledTaskTransitions.clearRunningMarker(doc, "t1")
        val after = ScheduledTaskTransitions.crashFailure(compensated, "t1")
        assertEquals("with the marker cleared there is no crash to recover", null, after.task)
        assertEquals(0, compensated.tasks.single().failStreak)
    }

    @Test
    fun `finalizeLocked drops the running marker when the settlement throws`() {
        assertTrue(
            "finalizeLocked's catch must clear the persisted marker",
            clearsMarkerOnFailure(runnerSource()),
        )
    }

    @Test
    fun `negative control - dropping the compensation is caught`() {
        val source = runnerSource()
        val mutated = source.replace(
            "ScheduledTasksStore.clearRunningMarker(app, taskId)",
            "Unit",
        )
        assertTrue("the negative control must actually mutate the source", mutated != source)
        assertTrue(
            "removing the compensation must be detected",
            !clearsMarkerOnFailure(mutated),
        )
    }

    // ── the check as a function of the source text ───────────────────────────

    private fun clearsMarkerOnFailure(source: String): Boolean {
        val body = bodyOf(source, "private suspend fun finalizeLocked(")
        val catchAt = body.indexOf("catch (t: Throwable)")
        if (catchAt < 0) return false
        val tail = body.substring(catchAt)
        val call = tail.indexOf("ScheduledTasksStore.clearRunningMarker(app, taskId)")
        if (call < 0) return false
        // The compensation must land BEFORE the in-memory lock is released, or
        // the next beat can still observe "no run + marker" in its window.
        val release = tail.indexOf("if (runningTaskId == taskId) runningTaskId = null")
        return release < 0 || call < release
    }

    private fun runnerSource(): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTaskRunner.kt")

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
