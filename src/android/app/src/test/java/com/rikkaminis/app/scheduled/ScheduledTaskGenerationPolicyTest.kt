package com.rikkaminis.app.scheduled

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real production predicate, previously covered only by an out-of-repo probe. */
class ScheduledTaskGenerationPolicyTest {
    private val run = ScheduledRunRef(
        taskId = "task",
        sessionId = "session",
        taskGeneration = 3,
        globalGeneration = 4,
        documentGeneration = 5,
    )

    @Test
    fun `admission requires all three captured generations`() {
        assertTrue(ScheduledTaskGenerationPolicy.admits(run, 3, 4, 5))
        assertFalse(ScheduledTaskGenerationPolicy.admits(run, 2, 4, 5))
        assertFalse(ScheduledTaskGenerationPolicy.admits(run, 3, 3, 5))
        assertFalse(ScheduledTaskGenerationPolicy.admits(run, 3, 4, 6))
    }

    @Test
    fun `an older document is invalidated even when the task still exists`() {
        val watermark = ScheduledTaskRemovalWatermark(emptyMap(), 4, 6)
        assertTrue(ScheduledTaskGenerationPolicy.invalidated(run, null, watermark))
        assertFalse(ScheduledTaskGenerationPolicy.invalidated(run, null, watermark.copy(documentGeneration = 5)))
    }

    @Test
    fun `delete invalidates only runs from an older generation of that task`() {
        val watermark = ScheduledTaskRemovalWatermark(mapOf("task" to 4L), 4, 5)
        assertTrue(ScheduledTaskGenerationPolicy.invalidated(run, setOf("task"), watermark))
        assertFalse(ScheduledTaskGenerationPolicy.invalidated(run.copy(taskGeneration = 4), setOf("task"), watermark))
        assertFalse(ScheduledTaskGenerationPolicy.invalidated(run.copy(taskId = "other"), setOf("task"), watermark))
        assertFalse(ScheduledTaskGenerationPolicy.invalidated(run, emptySet(), watermark.copy(documentGeneration = 5)))
    }
}
