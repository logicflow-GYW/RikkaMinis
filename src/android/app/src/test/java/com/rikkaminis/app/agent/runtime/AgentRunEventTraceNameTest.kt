package com.rikkaminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §16 [backlog]: [AgentRunEvent.traceName] keeps the reducer-rejection line
 * readable in release builds. The observer used to print
 * `event::class.simpleName`, which R8 renames to a single letter — the 09-15
 * log shows `T7-D reducer REJECTED b: ...` across three different phases, i.e.
 * unreadable exactly where auditing needs it.
 *
 * The exhaustive `when` inside traceName() already binds the mapping to the
 * sealed hierarchy at compile time (a new event cannot be forgotten). What is
 * left to pin is the other half: no two events collapse onto one name, and no
 * name is a bare letter.
 */
class AgentRunEventTraceNameTest {

    private fun everyName(): List<String> = listOf(
        AgentRunEvent.RunStarted("run-1").traceName(),
        AgentRunEvent.ProviderAttemptStarted.traceName(),
        AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS).traceName(),
        AgentRunEvent.RetryRequested().traceName(),
        AgentRunEvent.FallbackSelected().traceName(),
        AgentRunEvent.FallbackExhausted.traceName(),
        AgentRunEvent.ToolStarted("shell_execute").traceName(),
        AgentRunEvent.ToolFinished("shell_execute", resultKnown = true).traceName(),
        AgentRunEvent.CompactionStarted().traceName(),
        AgentRunEvent.CompactionFinished().traceName(),
        AgentRunEvent.WorkCompleted.traceName(),
        AgentRunEvent.UserCancelled().traceName(),
        AgentRunEvent.DeadlineReached().traceName(),
        AgentRunEvent.ProcessInterrupted().traceName(),
        AgentRunEvent.PersistenceFailed().traceName(),
        AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED).traceName(),
    )

    @Test
    fun `every event carries a distinct readable name`() {
        val names = everyName()
        assertTrue("expected one name per event, saw ${names.size}", names.size == 16)
        assertTrue("two events share a name: $names", names.toSet().size == names.size)
        for (n in names) {
            // A single letter is exactly what R8 produced; anything this short
            // would defeat the point of the mapping.
            assertTrue("name too short to be readable: '$n'", n.length > 3)
        }
    }

    @Test
    fun `the names name their own event`() {
        assertEquals("RunStarted", AgentRunEvent.RunStarted("r").traceName())
        assertEquals("WorkCompleted", AgentRunEvent.WorkCompleted.traceName())
        assertEquals("RunFinalized", AgentRunEvent.RunFinalized(AgentTerminal.FAILED).traceName())
    }
}
