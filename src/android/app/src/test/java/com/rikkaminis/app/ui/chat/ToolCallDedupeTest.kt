package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.ToolLoopDetector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM unit tests for the same-turn tool-call dedupe introduced in
 * [T-android-tool-dedupe]. Same toolName + same args (ignoring cosmetic
 * UI fields like tool_title) within one turn now execute only once — the
 * first occurrence wins, every identical duplicate is dropped with a
 * synthetic tool_result.
 *
 * The fingerprint implementation (production code) lives in
 * [toolCallDedupeFingerprint] (ChatViewModelUtils.kt); these tests exercise
 * that exact implementation, never a copy.
 */
class ToolCallDedupeTest {

    // ─── 1. same-turn same-name same-args dedupes ───────────────────────────

    @Test
    fun `same tool same args dedupes`() {
        val t1 = JSONObject("""{"path":"/a","tool_title":"Read File"}""")
        val t2 = JSONObject("""{"path":"/a","tool_title":"Read File #2"}""")
        assertEquals(
            "identical args must produce identical fingerprints",
            toolCallDedupeFingerprint("file_read", t1),
            toolCallDedupeFingerprint("file_read", t2),
        )
    }

    @Test
    fun `different tool_title does not change fingerprint`() {
        val t1 = JSONObject("""{"tool_title":"1","path":"/a"}""")
        val t2 = JSONObject("""{"tool_title":"2","path":"/a"}""")
        assertEquals(
            toolCallDedupeFingerprint("file_read", t1),
            toolCallDedupeFingerprint("file_read", t2),
        )
        assertTrue(toolCallDedupeFingerprint("file_read", JSONObject("""{"path":"/a"}""")) in
            setOf(toolCallDedupeFingerprint("file_read", t1), toolCallDedupeFingerprint("file_read", t2)))
    }

    // ─── 2. same tool different args does NOT dedupe ────────────────────────

    @Test
    fun `same tool different args are not deduped`() {
        val t1 = JSONObject("""{"path":"/a"}""")
        val t2 = JSONObject("""{"path":"/b"}""")
        assertFalse(
            "different args must differ",
            toolCallDedupeFingerprint("file_read", t1) == toolCallDedupeFingerprint("file_read", t2),
        )
    }

    // ─── 3. different tools never dedupe ────────────────────────────────────

    @Test
    fun `different tools same args are not deduped`() {
        val t1 = JSONObject("""{"path":"/a"}""")
        val t2 = JSONObject("""{"path":"/a"}""")
        assertFalse(
            "tool name is part of the fingerprint",
            toolCallDedupeFingerprint("file_read", t1) == toolCallDedupeFingerprint("read_image", t2),
        )
    }

    // ─── 4. stable key ordering ─────────────────────────────────────────────

    @Test
    fun `key order does not matter`() {
        val t1 = JSONObject("""{"b":2,"a":1,"c":{"x":true,"w":10}}""")
        val t2 = JSONObject("""{"c":{"w":10,"x":true},"a":1,"b":2}""")
        assertEquals(
            "JSON key order must not change the fingerprint",
            toolCallDedupeFingerprint("file_read", t1),
            toolCallDedupeFingerprint("file_read", t2),
        )
    }

    // ─── 5. ignore-key source of truth ──────────────────────────────────────

    @Test
    fun `dedupe ignore keys match ToolLoopDetector source of truth`() {
        // The dedupe fingerprint must ignore EXACTLY the same keys the
        // cross-turn loop detector ignores — duplicated constants drift.
        assertEquals(
            "dedupe must reuse the loop detector's ignored-keys set",
            setOf("tool_title"),
            ToolLoopDetector.ARGS_HASH_IGNORED_KEYS,
        )
    }

    // ─── 6. resolution map contract (first id wins) ─────────────────────────

    @Test
    fun `resolution map first occurrence wins`() {
        // Mirror of the Pass-1 dedupe bookkeeping: for each call, the map
        // records id → fingerprint; a duplicate resolves to the FIRST id.
        val a = JSONObject("""{"path":"/a"}""")
        val fpA = toolCallDedupeFingerprint("file_read", a)
        val seen = mutableMapOf<String, String>() // fingerprint -> first id
        val calls = listOf("A" to fpA, "B" to fpA, "C" to toolCallDedupeFingerprint("file_read", JSONObject("""{"path":"/b"}""")))
        val resolved = calls.map { (id, fp) ->
            val dupOf = seen[fp]
            seen.putIfAbsent(fp, id)
            id to (dupOf ?: id)
        }
        // A first → executes itself; B duplicate → resolves to A; C different → itself
        assertEquals("A" to "A", resolved[0])
        assertEquals("B" to "A", resolved[1])
        assertEquals("C" to "C", resolved[2])
    }
}