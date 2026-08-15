package com.openminis.app.diagnostics

/**
 * T9-performance-baseline: synthetic workload definitions for reproducible
 * performance baselines.
 *
 * Each workload scenario defines a set of steps that can be executed by
 * a workload runner (either automated test or manual trigger).
 *
 * The scenarios mirror the baseline sampling protocol from
 * docs/stability/performance-baseline.md §4.
 *
 * Usage (automated):
 *   SyntheticWorkload.run(SyntheticWorkload.Scenario.COLD_START, ...)
 * Usage (manual):
 *   SyntheticWorkload.describe(Scenario.COLD_START) → prints steps
 */
object SyntheticWorkload {

    /** Standard workload scenarios. */
    enum class Scenario(
        val id: String,
        val description: String,
        val recommendedRuns: Int,
        val tags: List<String>,
    ) {
        COLD_START(
            id = "cold_start",
            description = "Cold start: kill process, relaunch, measure to_idle_ms and config_load_ms",
            recommendedRuns = 5,
            tags = listOf("startup", "baseline"),
        ),
        SIMPLE_QA(
            id = "simple_qa",
            description = "Simple question-answer (no tools): send message, record first-token latency and stream turn metrics",
            recommendedRuns = 20,
            tags = listOf("streaming", "baseline", "high-volume"),
        ),
        TOOL_CHAIN(
            id = "tool_chain",
            description = "Tool-intensive run (≥3 tool calls): e.g. file_read → shell_execute → browser_use",
            recommendedRuns = 10,
            tags = listOf("tool", "execution", "baseline"),
        ),
        MULTI_SESSION(
            id = "multi_session",
            description = "5 concurrent sessions: measure queue wait, peak heap, active session count",
            recommendedRuns = 5,
            tags = listOf("concurrency", "pressure", "low-volume"),
        ),
        COMPACT_TRIGGER(
            id = "compact_trigger",
            description = "Long conversation that triggers context compaction: measure compact duration and memory after",
            recommendedRuns = 5,
            tags = listOf("memory", "compact", "low-volume"),
        ),
        MEMORY_PRESSURE(
            id = "memory_pressure",
            description = "Rapid tool calls in multiple sessions: measure peak RSS, thread count, OOM proximity",
            recommendedRuns = 3,
            tags = listOf("memory", "pressure", "stress"),
        ),
    }

    /** Conditions under which the workload should be collected. */
    data class CollectionConfig(
        val deviceModel: String = "Redmi Note 12 Turbo (marble)",
        val osVersion: String = "Android 15 + HyperOS 3.0",
        val networkCondition: String = "VPN proxy (HTTP @ 127.52.18.23:39443)",
        val apkVersion: String = "",
        val commitHash: String = "",
        val collectorDir: String = "",
    )

    /** A single step in a workload scenario. */
    data class Step(
        val action: String,        // "send_message" | "wait" | "measure" | "kill_process" | "launch" | "trigger_compact"
        val params: Map<String, String> = emptyMap(),
    )

    /**
     * Describe a scenario in human-readable format.
     */
    fun describe(scenario: Scenario): String = buildString {
        appendLine("## ${scenario.id}: ${scenario.description}")
        appendLine()
        appendLine("Recommended runs: ${scenario.recommendedRuns}")
        appendLine("Tags: ${scenario.tags.joinToString(", ")}")
        appendLine()
        appendLine("### Steps")
        val steps = getSteps(scenario)
        steps.forEachIndexed { i, step ->
            appendLine("  ${i + 1}. ${step.action}${if (step.params.isNotEmpty()) " (${step.params})" else ""}")
        }
    }

    /**
     * Get the steps for a scenario.
     */
    fun getSteps(scenario: Scenario): List<Step> = when (scenario) {
        Scenario.COLD_START -> listOf(
            Step("kill_process", mapOf("method" to "force_stop")),
            Step("wait", mapOf("duration_ms" to "2000")),
            Step("launch", mapOf("from" to "launcher")),
            Step("measure", mapOf("metric" to "cold_start_to_idle_ms")),
            Step("measure", mapOf("metric" to "config_load_ms")),
            Step("wait", mapOf("duration_ms" to "5000")),
        )
        Scenario.SIMPLE_QA -> listOf(
            Step("send_message", mapOf("content" to "Hello, what is 2+2?", "expect_tools" to "false")),
            Step("measure", mapOf("metric" to "first_token_latency_ms")),
            Step("measure", mapOf("metric" to "stream_turn_summary")),
            Step("wait", mapOf("duration_ms" to "2000")),
        )
        Scenario.TOOL_CHAIN -> listOf(
            Step("send_message", mapOf("content" to "Read /var/minis/workspace/info.txt and tell me what's in it", "expect_tools" to "true")),
            Step("measure", mapOf("metric" to "tool_duration_ms")),
            Step("measure", mapOf("metric" to "run_duration_ms")),
            Step("wait", mapOf("duration_ms" to "5000")),
        )
        Scenario.MULTI_SESSION -> listOf(
            Step("send_message", mapOf("session_id" to "s1", "content" to "Write a poem about AI", "expect_tools" to "false")),
            Step("send_message", mapOf("session_id" to "s2", "content" to "What is the capital of France?", "expect_tools" to "false")),
            Step("send_message", mapOf("session_id" to "s3", "content" to "Explain quantum computing in simple terms", "expect_tools" to "false")),
            Step("send_message", mapOf("session_id" to "s4", "content" to "Write a Python script to sort a list", "expect_tools" to "true")),
            Step("send_message", mapOf("session_id" to "s5", "content" to "Summarize the theory of relativity", "expect_tools" to "false")),
            Step("measure", mapOf("metric" to "multi_session_summary")),
            Step("wait", mapOf("duration_ms" to "30000")),
            Step("measure", mapOf("metric" to "multi_session_after")),
        )
        Scenario.COMPACT_TRIGGER -> listOf(
            Step("send_message", mapOf("content" to "Long conversation prefix...", "expect_tools" to "false")),
            Step("send_message", mapOf("content" to "Continue with more text...", "expect_tools" to "false")),
            // Repeat several times to build up context
            Step("send_message", mapOf("content" to "Add more context to trigger compaction...", "expect_tools" to "false")),
            Step("send_message", mapOf("content" to "Keep going...", "expect_tools" to "false")),
            Step("send_message", mapOf("content" to "Almost there...", "expect_tools" to "false")),
            Step("trigger_compact", mapOf()),
            Step("measure", mapOf("metric" to "compact_duration_ms")),
            Step("measure", mapOf("metric" to "memory_after_compact")),
        )
        Scenario.MEMORY_PRESSURE -> listOf(
            Step("send_message", mapOf("session_id" to "s1", "content" to "Run: python3 -c 'import time; [i**i for i in range(10000)]'", "expect_tools" to "true")),
            Step("send_message", mapOf("session_id" to "s2", "content" to "Run: cat /proc/self/status", "expect_tools" to "true")),
            Step("send_message", mapOf("session_id" to "s3", "content" to "Run: find /var -name '*.kt' | head -100", "expect_tools" to "true")),
            Step("measure", mapOf("metric" to "peak_rss_mb")),
            Step("measure", mapOf("metric" to "thread_count")),
            Step("wait", mapOf("duration_ms" to "15000")),
            Step("measure", mapOf("metric" to "memory_after_release")),
        )
    }

    /**
     * Generate a baseline collection script stub for a given scenario.
     * Returns a shell script that outputs the steps for manual execution.
     */
    fun generateCollectScript(scenario: Scenario, config: CollectionConfig, runCount: Int = scenario.recommendedRuns): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# T9 Performance Baseline Collection")
        appendLine("# Scenario: ${scenario.id}")
        appendLine("# Runs: $runCount")
        appendLine("# Device: ${config.deviceModel}")
        appendLine("# OS: ${config.osVersion}")
        appendLine("# APK: ${config.apkVersion} (${config.commitHash})")
        appendLine("# Collector dir: ${config.collectorDir}")
        appendLine("# Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine()
        appendLine("echo \"=== Baseline Collection: ${scenario.id} ===\"")
        appendLine("echo \"Runs: $runCount\"")
        appendLine()
        appendLine("for i in \$(seq 1 $runCount); do")
        appendLine("  echo \"--- Run \$i of $runCount ---\"")
        val steps = getSteps(scenario)
        for (step in steps) {
            when (step.action) {
                "send_message" -> {
                    val content = step.params["content"] ?: ""
                    val sessionId = step.params["session_id"]
                    val sidFlag = if (sessionId != null) " --session $sessionId" else ""
                    appendLine("  # Send: $content")
                    appendLine("  minis-sessions-cli send --text \"$content\"$sidFlag")
                    appendLine("  sleep 2")
                }
                "wait" -> {
                    val ms = step.params["duration_ms"] ?: "1000"
                    appendLine("  sleep ${ms.toInt() / 1000}")
                }
                "measure" -> {
                    appendLine("  # [measure] ${step.params}")
                }
                "kill_process" -> {
                    appendLine("  am force-stop com.openminis.app")
                }
                "launch" -> {
                    appendLine("  am start -n com.openminis.app/.MainActivity")
                }
                "trigger_compact" -> {
                    appendLine("  # Trigger compact via API")
                    appendLine("  minis-sessions-cli send --text \"/compact\"")
                }
            }
        }
        appendLine("  echo \"--- Run \$i complete ---\"")
        appendLine("done")
        appendLine()
        appendLine("echo \"=== Collection complete ===\"")
        appendLine("echo \"Baseline files: ${config.collectorDir}\"")
    }
}