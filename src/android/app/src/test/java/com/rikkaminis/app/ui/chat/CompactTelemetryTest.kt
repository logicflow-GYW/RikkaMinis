package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.conversation.compactSummaryTelemetrySuffix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the [fix/compact-telemetry-superseded-1005] telemetry half:
 * the adopted summary's size and model-reported output tokens ride the
 * existing SUCCESS log line (low-frequency event → INFO is free; no new line,
 * no debug switch — task-brief-1005-D §2.A). This turns the summary horizon
 * (compact-exp-1004 D-hold: six consecutive 4096-cap hits; E: low-density
 * saturation ~2.1k tok) into a log reading instead of a surprise.
 *
 * Expected values are literal — never derived from the tested logic, so a
 * semantic flip fails these instead of following the mutation
 * (JVM 装置自指陷阱, 2026-09-26). Wiring is pinned on source fragments —
 * order of named fragments only, per the source-text-assertion lesson
 * (2026-10-01).
 */
class CompactTelemetryTest {

    // ── segment shape (pure function, literal expectations) ────────────

    @Test
    fun `suffix carries chars and outTok segments`() {
        val suffix = compactSummaryTelemetrySuffix(
            LLMResponse(
                text = "x".repeat(4221),
                stopReason = "stop",
                usage = LLMUsage(inputTokens = 9000, outputTokens = 2180),
            ),
        )
        // Task-brief format example: `... in 7256ms chars=4221 outTok=2180`.
        assertEquals(" chars=4221 outTok=2180", suffix)
    }

    @Test
    fun `null usage omits the outTok segment`() {
        // Channels that do not report usage: absent reads as "unknown";
        // a logged 0 would read as "empty" — omission is the honest shape.
        val suffix = compactSummaryTelemetrySuffix(
            LLMResponse(text = "abc", stopReason = null, usage = null),
        )
        assertEquals(" chars=3", suffix)
        assertTrue("no dangling outTok", !suffix.contains("outTok"))
    }

    @Test
    fun `empty summary still reports chars zero`() {
        val suffix = compactSummaryTelemetrySuffix(
            LLMResponse(text = "", stopReason = "stop", usage = null),
        )
        assertEquals(" chars=0", suffix)
    }

    @Test
    fun `zero output tokens are reported not omitted`() {
        // A 0 outTok with usage PRESENT is real data (truncated-at-zero
        // relay behaviour), distinct from usage absence — keep it.
        val suffix = compactSummaryTelemetrySuffix(
            LLMResponse(
                text = "abc",
                stopReason = "stop",
                usage = LLMUsage(inputTokens = 10, outputTokens = 0),
            ),
        )
        assertEquals(" chars=3 outTok=0", suffix)
    }

    @Test
    fun `segment starts with a single space`() {
        // The call site concatenates directly after `in ${ms}ms" +` — the
        // leading space keeps the joined line single-spaced, matching the
        // task-brief example (`7256ms chars=4221`).
        val suffix = compactSummaryTelemetrySuffix(
            LLMResponse(text = "a", stopReason = null, usage = null),
        )
        assertTrue(suffix.startsWith(" "))
        assertTrue("no double space", !suffix.contains("  "))
    }

    // ── wiring: the suffix rides the SUCCESS line, after the guard ─────
    //
    // generateCompactSummary lives on ChatViewModel (uninstantiable in JVM),
    // so the integration is pinned on source fragments — order of named
    // fragments only.

    @Test
    fun `success line carries the telemetry suffix between guard and adoption`() {
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt",
        )
        val successRegion = lifecycle
            .substringAfter("is ProviderExecutionGateway.SendResult.Success -> {")
            .substringBefore("is ProviderExecutionGateway.SendResult.RemoteFailure ->")
        val atGuard = successRegion.indexOf("compactSummaryIsTruncated(r.response.stopReason)")
        val atSuccessLog = successRegion.indexOf("SUCCESS")
        val atInMs = successRegion.indexOf("in ${'$'}{ms}ms\"")
        val atSuffix = successRegion.indexOf("compactSummaryTelemetrySuffix(r.response)")
        val atReturn = successRegion.indexOf("return r.response.text")
        for ((name, idx) in listOf(
            "truncation guard" to atGuard, "SUCCESS log" to atSuccessLog,
            "ms segment" to atInMs, "telemetry suffix" to atSuffix,
            "text adoption" to atReturn,
        )) {
            assertTrue("success branch missing $name", idx >= 0)
        }
        // Sequence: guard first (a truncated candidate must never reach the
        // success log, let alone its telemetry), then the SUCCESS line, the
        // suffix appended onto that same line (after the `in ${ms}ms` string
        // fragment — not a separate log call), all before the text adoption.
        assertTrue(atGuard < atSuccessLog)
        assertTrue(atSuccessLog < atInMs)
        assertTrue(atInMs < atSuffix)
        assertTrue(atSuffix < atReturn)
        // It rides the EXISTING line: exactly one SUCCESS log line in the
        // branch, and no new AppLogger call may appear beyond the pre-existing
        // TRUNCATED + SUCCESS pair.
        val atTruncatedLog = successRegion.indexOf("summary TRUNCATED")
        assertTrue("TRUNCATED log missing", atTruncatedLog >= 0)
        assertEquals(
            "no new log line allowed — telemetry must ride the SUCCESS line",
            2,
            Regex("AppLogger\\.info").findAll(successRegion).count(),
        )
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir (`.../src/android/app`),
     * so a single-level parent lookup is not enough — CI caught this shape.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
