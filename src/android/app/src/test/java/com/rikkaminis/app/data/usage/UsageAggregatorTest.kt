package com.rikkaminis.app.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class UsageAggregatorTest {

    private val utcDayFormat: (Long) -> String = { ms ->
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.format(Date(ms))
    }

    private fun row(
        modelId: String = "model-a",
        json: String,
        createdAtMs: Long,
        sessionId: String = "sess-1",
    ) = UsageRow(modelId, json, createdAtMs, sessionId)

    @Test
    fun `multi-model multi-row aggregation is correct`() {
        val rows = listOf(
            row(json = """{"inputTokens":100,"outputTokens":10,"cacheCreationTokens":5,"cacheReadTokens":20}""", createdAtMs = 1_000L, sessionId = "s1"),
            row(json = """{"inputTokens":200,"outputTokens":30,"cacheCreationTokens":15,"cacheReadTokens":40}""", createdAtMs = 2_000L, sessionId = "s2"),
            row(modelId = "model-b", json = """{"inputTokens":7,"outputTokens":3}""", createdAtMs = 3_000L),
        )

        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)

        assertEquals(2, result.size)
        val a = result["model-a"]!!
        assertEquals(300L, a.inputTokens)
        assertEquals(40L, a.outputTokens)
        assertEquals(20L, a.cacheCreationTokens)
        assertEquals(60L, a.cacheReadTokens)
        // totalInput = input + cacheRead + cacheCreation
        assertEquals(300L + 60L + 20L, a.totalInput)
        assertEquals(setOf("s1", "s2"), a.distinctSessions)

        val b = result["model-b"]!!
        assertEquals(7L, b.inputTokens)
        assertEquals(3L, b.outputTokens)
        assertEquals(0L, b.cacheReadTokens)
    }

    @Test
    fun `malformed JSON rows are skipped`() {
        val rows = listOf(
            row(json = "{not valid json", createdAtMs = 1_000L),
            row(json = """{"inputTokens":50,"outputTokens":5}""", createdAtMs = 2_000L),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        assertEquals(1, result.size)
        assertEquals(50L, result["model-a"]!!.inputTokens)
    }

    @Test
    fun `legacy JSON key fallback applies`() {
        val rows = listOf(
            row(json = """{"inputTokens":10,"outputTokens":2,"cacheCreationInputTokens":11,"cacheReadInputTokens":22}""", createdAtMs = 1_000L),
            // modern keys win over legacy when both present
            row(json = """{"inputTokens":10,"outputTokens":2,"cacheCreationTokens":99,"cacheCreationInputTokens":11,"cacheReadTokens":88,"cacheReadInputTokens":22}""", createdAtMs = 2_000L),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        val stats = result["model-a"]!!
        assertEquals(11L + 99L, stats.cacheCreationTokens)
        assertEquals(22L + 88L, stats.cacheReadTokens)
    }

    @Test
    fun `time filter is half-open since inclusive until exclusive`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(json = """{"inputTokens":1,"outputTokens":1}""", createdAtMs = t0 - 1),          // before window
            row(json = """{"inputTokens":10,"outputTokens":1}""", createdAtMs = t0),             // == since → in
            row(json = """{"inputTokens":100,"outputTokens":1}""", createdAtMs = t0 + 5_000),    // inside
            row(json = """{"inputTokens":1000,"outputTokens":1}""", createdAtMs = t0 + 10_000),  // == until → out
        )
        val filter = UsageFilter(sinceMs = t0, untilMs = t0 + 10_000)
        val result = UsageAggregator.aggregate(rows, filter = filter, dayFormat = utcDayFormat)
        val stats = result["model-a"]!!
        assertEquals(110L, stats.inputTokens)
        assertTrue(stats.inputTokens != 1111L && stats.inputTokens != 11L)
    }

    @Test
    fun `distinctDays uses injected formatter`() {
        val rows = listOf(
            row(json = """{"inputTokens":1,"outputTokens":1}""", createdAtMs = 0L),                    // UTC 1970-01-01
            row(json = """{"inputTokens":1,"outputTokens":1}""", createdAtMs = 86_400_000L),           // UTC 1970-01-02
            row(json = """{"inputTokens":1,"outputTokens":1}""", createdAtMs = 86_400_001L),           // still 1970-01-02
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        assertEquals(setOf("1970-01-01", "1970-01-02"), result["model-a"]!!.distinctDays)

        // identity formatter proves the injection point is actually used
        val identity = UsageAggregator.aggregate(rows, dayFormat = { it.toString() })
        assertEquals(setOf("0", "86400000", "86400001"), identity["model-a"]!!.distinctDays)
    }

    @Test
    fun `empty list returns empty map`() {
        assertTrue(UsageAggregator.aggregate(emptyList(), dayFormat = utcDayFormat).isEmpty())
    }
}