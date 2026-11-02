package com.rikkaminis.app.data.usage

import com.rikkaminis.app.data.model.LLMUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-internal-usage-events] Policy tests for the cost ledger's pure half.
 *
 * What is worth a test here is NOT the field copy (the compiler covers that) but
 * the three decisions that silently change what the usage page reports:
 *
 *  - a channel that reports no usage must produce NO row (a zero row would
 *    inflate the request count while saying nothing about cost);
 *  - null cache counters must fold to 0, not to a skipped row (they are
 *    optional in [LLMUsage] but mandatory in the schema);
 *  - the per-source rollup must be ordered deterministically, because the page
 *    renders it as-is and a reordering between two identical loads reads as a
 *    glitch.
 *
 * Expected values are literals, never derived from the code under test.
 */
class InternalUsageRecordTest {

    private fun usage(
        input: Int = 100,
        output: Int = 20,
        cacheCreation: Int? = null,
        cacheRead: Int? = null,
    ) = LLMUsage(
        inputTokens = input,
        outputTokens = output,
        cacheCreationInputTokens = cacheCreation,
        cacheReadInputTokens = cacheRead,
    )

    private fun record(
        source: String = InternalUsageSources.COMPACT,
        createdAt: Long = 1_000L,
        input: Int = 100,
        output: Int = 20,
        cacheCreation: Int = 0,
        cacheRead: Int = 0,
    ) = InternalUsageRecord(
        createdAt = createdAt,
        source = source,
        modelId = "model-x",
        entryId = null,
        sessionId = null,
        inputTokens = input,
        outputTokens = output,
        cacheCreationTokens = cacheCreation,
        cacheReadTokens = cacheRead,
    )

    // ────────────────────────── from(): what counts ──────────────────────────

    @Test
    fun `from - null usage produces no row`() {
        assertNull(
            "a channel that reports no usage must not produce a zero-cost row",
            InternalUsageRecord.from(
                source = InternalUsageSources.COMPACT,
                modelId = "m",
                entryId = null,
                sessionId = null,
                usage = null,
                nowMs = 42L,
            ),
        )
    }

    @Test
    fun `from - all-zero usage produces no row`() {
        assertNull(
            InternalUsageRecord.from(
                source = InternalUsageSources.TITLE,
                modelId = "m",
                entryId = null,
                sessionId = null,
                usage = LLMUsage(inputTokens = 0, outputTokens = 0),
                nowMs = 42L,
            ),
        )
    }

    @Test
    fun `from - null cache counters fold to zero`() {
        val r = InternalUsageRecord.from(
            source = InternalUsageSources.QUICK_TEST,
            modelId = "m",
            entryId = "entry-1",
            sessionId = "s-1",
            usage = usage(input = 128, output = 12, cacheCreation = null, cacheRead = null),
            nowMs = 7_000L,
        )
        assertEquals(7_000L, r?.createdAt)
        assertEquals("quick_test", r?.source)
        assertEquals("m", r?.modelId)
        assertEquals("entry-1", r?.entryId)
        assertEquals("s-1", r?.sessionId)
        assertEquals(128, r?.inputTokens)
        assertEquals(12, r?.outputTokens)
        assertEquals(0, r?.cacheCreationTokens)
        assertEquals(0, r?.cacheReadTokens)
    }

    @Test
    fun `from - cache-only usage still counts`() {
        // Prompt-cache reads are the cheapest tokens there are, but they are
        // still tokens: a row whose only non-zero counter is the cache read
        // must survive, otherwise cache-heavy callers look free.
        val r = InternalUsageRecord.from(
            source = InternalUsageSources.COMPACT,
            modelId = "m",
            entryId = null,
            sessionId = null,
            usage = usage(input = 0, output = 0, cacheCreation = 0, cacheRead = 900),
            nowMs = 1L,
        )
        assertEquals(900, r?.cacheReadTokens)
        assertEquals(900L, r?.totalTokens)
    }

    @Test
    fun `from - negative counters are clamped, never forwarded`() {
        val r = InternalUsageRecord.from(
            source = InternalUsageSources.TITLE,
            modelId = "m",
            entryId = null,
            sessionId = null,
            usage = usage(input = -5, output = 10, cacheCreation = -1, cacheRead = 0),
            nowMs = 1L,
        )
        assertEquals(0, r?.inputTokens)
        assertEquals(10, r?.outputTokens)
        assertEquals(0, r?.cacheCreationTokens)
    }

    @Test
    fun `from - totalTokens includes cache, matching the page's totalInput`() {
        val r = record(input = 100, output = 20, cacheCreation = 30, cacheRead = 50)!!
        // page semantics: totalInput = input + cacheCreation + cacheRead
        assertEquals(180L, r.inputTokens.toLong() + r.cacheCreationTokens + r.cacheReadTokens)
        assertEquals(200L, r.totalTokens)
    }

    // ─────────────────────────── per-source rollup ───────────────────────────

    @Test
    fun `aggregate - empty ledger yields no section`() {
        assertEquals(emptyList<InternalSourceStats>(), aggregateInternalUsageBySource(emptyList()))
    }

    @Test
    fun `aggregate - groups by source and sums each counter`() {
        val rows = listOf(
            record(source = "compact", input = 100, output = 10, cacheRead = 5),
            record(source = "title", input = 40, output = 8),
            record(source = "compact", input = 60, output = 6, cacheCreation = 4),
        )
        val stats = aggregateInternalUsageBySource(rows)

        assertEquals(2, stats.size)
        val compact = stats.first { it.source == "compact" }
        assertEquals(2, compact.requests)
        assertEquals(160L, compact.inputTokens)
        assertEquals(16L, compact.outputTokens)
        assertEquals(4L, compact.cacheCreationTokens)
        assertEquals(5L, compact.cacheReadTokens)
        assertEquals(169L, compact.totalInput) // 160 + 4 + 5
        assertEquals(185L, compact.totalTokens) // + 16

        val title = stats.first { it.source == "title" }
        assertEquals(1, title.requests)
        assertEquals(48L, title.totalTokens)
    }

    @Test
    fun `aggregate - biggest spender first, then source id`() {
        val rows = listOf(
            record(source = "title", input = 10, output = 0),
            record(source = "quick_test", input = 90, output = 0),
            record(source = "compact", input = 10, output = 0),
        )
        assertEquals(
            listOf("quick_test", "compact", "title"),
            aggregateInternalUsageBySource(rows).map { it.source },
        )
    }

    @Test
    fun `aggregate - ties fall back to source id so the order is stable`() {
        val rows = listOf(
            record(source = "title", input = 5, output = 5),
            record(source = "compact", input = 5, output = 5),
        )
        assertEquals(
            listOf("compact", "title"),
            aggregateInternalUsageBySource(rows).map { it.source },
        )
    }
}
