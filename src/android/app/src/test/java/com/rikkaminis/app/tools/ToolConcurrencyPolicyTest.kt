package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for ToolConcurrencyPolicy — the whitelist + greedy
 * order-preserving partitioning ported from OmniBot's
 * AgentToolConcurrencyPolicy.
 *
 * Guards the invariant the agent loop depends on: only pure-read tools are
 * PARALLEL_SAFE, everything else stays SERIAL_BARRIER, and partitioning
 * never reorders calls (order preservation is critical for loop-detector
 * sequence semantics and result ordering).
 */
class ToolConcurrencyPolicyTest {

    // ─── isParallelSafe: whitelist ──────────────────────────────────────────
    @Test fun pureReadTools_areParallelSafe() {
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("file_read", """{"path":"/x/y.txt"}"""))
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("read_image", """{"path":"/x/y.png"}"""))
    }

    @Test fun sideEffectTools_areNotParallelSafe() {
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("shell_execute", """{"command":"echo hi"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("file_write", """{"path":"/x/y.txt"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("file_edit", """{"path":"/x/y.txt"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("memory_write", """{"content":"hi"}"""))
    }

    @Test fun memoryGet_isNotParallelSafe_dueToSharedRecordState() {
        // Read-semantics but appends to _memoryToolRecords (check-then-act) → serial.
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("memory_get", """{"keywords":"x"}"""))
    }

    @Test fun unknownTool_isNotParallelSafe() {
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("no_such_tool", """{}"""))
    }

    @Test fun emptyOrInvalidArgs_forBrowser_isNotParallelSafe() {
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", null))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", "not json"))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{}"""))
    }

    // ─── isParallelSafe: browser_use action granularity ─────────────────────
    @Test fun browser_readActions_areParallelSafe() {
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"get_text"}"""))
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"screenshot"}"""))
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"get_page_info"}"""))
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"get_readable"}"""))
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"get_backbone"}"""))
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"list_tabs"}"""))
        assertTrue(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"get_cookies"}"""))
    }

    @Test fun browser_stateChangingActions_areNotParallelSafe() {
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"navigate","url":"https://x"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"click","coordinate_x":1}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"type","text":"hi"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"scroll"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"fetch","url":"https://x"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"new_tab"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"close_tab"}"""))
        assertFalse(ToolConcurrencyPolicy.isParallelSafe("browser_use", """{"action":"set_user_agent"}"""))
    }

    // ─── partitionToolCalls: greedy, order-preserving ───────────────────────
    @Test fun emptyList_yieldsNoBatches() {
        assertEquals(emptyList<List<Triple<String, String, String>>>(), ToolConcurrencyPolicy.partitionToolCalls(emptyList()))
    }

    @Test fun allParallel_runs_mergeIntoSingleBatch_preservingOrder() {
        val calls = listOf(
            Triple("1", "file_read", """{"path":"/a"}"""),
            Triple("2", "file_read", """{"path":"/b"}"""),
            Triple("3", "read_image", """{"path":"/c.png"}"""),
        )
        val batches = ToolConcurrencyPolicy.partitionToolCalls(calls)
        assertEquals(1, batches.size)
        assertEquals(listOf("1", "2", "3"), batches[0].map { it.first })
    }

    @Test fun serialTools_breakParallelRuns() {
        val calls = listOf(
            Triple("1", "file_read", """{"path":"/a"}"""),
            Triple("2", "shell_execute", """{"command":"ls"}"""),
            Triple("3", "file_read", """{"path":"/b"}"""),
        )
        val batches = ToolConcurrencyPolicy.partitionToolCalls(calls)
        // [1] [2] [3] — serial call in the middle splits the two parallel runs apart
        assertEquals(3, batches.size)
        assertEquals(listOf("1"), batches[0].map { it.first })
        assertEquals(listOf("2"), batches[1].map { it.first })
        assertEquals(listOf("3"), batches[2].map { it.first })
    }

    @Test fun twoParallelRuns_separatedBySerial_areDistinctBatches() {
        val calls = listOf(
            Triple("1", "file_read", """{"path":"/a"}"""),
            Triple("2", "read_image", """{"path":"/b.png"}"""),
            Triple("3", "file_write", """{"path":"/c"}"""),
            Triple("4", "file_read", """{"path":"/d"}"""),
        )
        val batches = ToolConcurrencyPolicy.partitionToolCalls(calls)
        assertEquals(3, batches.size)
        assertEquals(listOf("1", "2"), batches[0].map { it.first })
        assertEquals(listOf("3"), batches[1].map { it.first })
        assertEquals(listOf("4"), batches[2].map { it.first })
    }

    @Test fun browserParallelRunAndSerial_BatchCorrectly() {
        val calls = listOf(
            Triple("1", "browser_use", """{"action":"get_text"}"""),
            Triple("2", "browser_use", """{"action":"screenshot"}"""),
            Triple("3", "browser_use", """{"action":"navigate","url":"https://x"}"""),
            Triple("4", "browser_use", """{"action":"get_readable"}"""),
        )
        val batches = ToolConcurrencyPolicy.partitionToolCalls(calls)
        assertEquals(3, batches.size)
        assertEquals(listOf("1", "2"), batches[0].map { it.first })
        assertEquals(listOf("3"), batches[1].map { it.first })
        assertEquals(listOf("4"), batches[2].map { it.first })
    }

    @Test fun orderIsNeverReordered_acrossWholeStream() {
        // Total order of ids must equal the input order — the invariant that
        // keeps loop-detector sequencing and result ordering correct.
        val ids = (0 until 20).map { it.toString() }
        val names = listOf(
            "file_read", "shell_execute", "read_image", "file_write", "browser_use", "file_edit",
        )
        val calls = ids.mapIndexed { i, id ->
            declareToolCall(id, names[i % names.size], i)
        }
        val batches = ToolConcurrencyPolicy.partitionToolCalls(calls)
        val allOrdered = mutableListOf<String>()
        for (b in batches) {
            for (c in b) allOrdered.add(c.first)
        }
        assertEquals(ids, allOrdered)
    }

    private fun declareToolCall(
        id: String,
        name: String,
        salt: Int,
    ): Triple<String, String, String> {
        val args = when {
            name == "browser_use" -> if (salt % 2 == 0) """{"action":"get_text"}""" else """{"action":"navigate","url":"https://x/$salt"}"""
            name == "file_read" -> """{"path":"/f$salt.txt"}"""
            name == "read_image" -> """{"path":"/i$salt.png"}"""
            name == "shell_execute" -> """{"command":"echo $salt"}"""
            name == "file_write" -> """{"path":"/w$salt.txt"}"""
            name == "file_edit" -> """{"path":"/e$salt.txt"}"""
            else -> """{}"""
        }
        return Triple(id, name, args)
    }
}
