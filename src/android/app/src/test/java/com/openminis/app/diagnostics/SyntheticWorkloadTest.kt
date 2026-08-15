package com.openminis.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

/**
 * T9-performance-baseline: unit tests for SyntheticWorkload scenario definitions.
 *
 * Pure JVM — no Android dependency.
 */
class SyntheticWorkloadTest {

    @Test
    fun `all scenarios have descriptions`() {
        for (scenario in SyntheticWorkload.Scenario.values()) {
            assertTrue(scenario.description.isNotEmpty())
            assertTrue(scenario.id.isNotEmpty())
            assertTrue(scenario.recommendedRuns > 0)
        }
    }

    @Test
    fun `cold start scenario has kill and launch steps`() {
        val steps = SyntheticWorkload.getSteps(SyntheticWorkload.Scenario.COLD_START)
        assertTrue(steps.any { it.action == "kill_process" })
        assertTrue(steps.any { it.action == "launch" })
        assertTrue(steps.any { it.action == "measure" })
    }

    @Test
    fun `simple qa scenario has send message and measure steps`() {
        val steps = SyntheticWorkload.getSteps(SyntheticWorkload.Scenario.SIMPLE_QA)
        assertTrue(steps.any { it.action == "send_message" })
        assertTrue(steps.any { it.action == "measure" })
        assertFalse(steps.any { it.action == "kill_process" })
    }

    @Test
    fun `tool chain scenario has at least one send_message with expect_tools=true`() {
        val steps = SyntheticWorkload.getSteps(SyntheticWorkload.Scenario.TOOL_CHAIN)
        val sendSteps = steps.filter { it.action == "send_message" }
        assertTrue(sendSteps.isNotEmpty())
        assertTrue(sendSteps.any { it.params["expect_tools"] == "true" })
    }

    @Test
    fun `multi session scenario uses multiple session ids`() {
        val steps = SyntheticWorkload.getSteps(SyntheticWorkload.Scenario.MULTI_SESSION)
        val sendSteps = steps.filter { it.action == "send_message" }
        assertTrue(sendSteps.size >= 5)
        val sessionIds = sendSteps.mapNotNull { it.params["session_id"] }.distinct()
        assertTrue(sessionIds.size >= 5)
    }

    @Test
    fun `compact trigger scenario has trigger_compact step`() {
        val steps = SyntheticWorkload.getSteps(SyntheticWorkload.Scenario.COMPACT_TRIGGER)
        assertTrue(steps.any { it.action == "trigger_compact" })
        assertTrue(steps.any { it.action == "send_message" })
    }

    @Test
    fun `memory pressure scenario has tool-using send messages`() {
        val steps = SyntheticWorkload.getSteps(SyntheticWorkload.Scenario.MEMORY_PRESSURE)
        val sendSteps = steps.filter { it.action == "send_message" }
        assertTrue(sendSteps.size >= 3)
        assertTrue(sendSteps.any { it.params["expect_tools"] == "true" })
        assertTrue(steps.any { it.action == "measure" })
    }

    @Test
    fun `describe produces readable output`() {
        for (scenario in SyntheticWorkload.Scenario.values()) {
            val desc = SyntheticWorkload.describe(scenario)
            assertTrue(desc.contains(scenario.id))
            assertTrue(desc.contains(scenario.description))
            assertTrue(desc.contains("Steps"))
        }
    }

    @Test
    fun `generate collect script has correct header`() {
        val config = SyntheticWorkload.CollectionConfig(
            deviceModel = "Test Device",
            commitHash = "abc123",
            collectorDir = "/data/local/tmp/perf-baseline",
        )
        val script = SyntheticWorkload.generateCollectScript(
            SyntheticWorkload.Scenario.SIMPLE_QA,
            config,
            runCount = 3,
        )
        assertTrue(script.contains("Test Device"))
        assertTrue(script.contains("abc123"))
        assertTrue(script.contains("simple_qa"))
        assertTrue(script.contains("Runs: 3"))
        assertTrue(script.contains("for i in"))
    }

    @Test
    fun `recommended runs total is reasonable`() {
        var total = 0
        for (scenario in SyntheticWorkload.Scenario.values()) {
            total += scenario.recommendedRuns
        }
        // Expect around 48 runs total across all scenarios
        assertTrue(total in 20..100)
    }
}