package com.rikkaminis.app.share

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for PendingShare wire-format tolerance (fix/audit-0917).
 * fromJson used getJSONObject per element: a malformed element (non-object)
 * aborted the whole share instead of being skipped — the comment now claims
 * "skipped, not abort", and these tests pin that behavior.
 */
class PendingShareTest {

    private fun textItem(v: String) = PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, v)
    private fun attItem(v: String) = PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, v)

    @Test
    fun `json round trip preserves items and timestamp`() {
        val share = PendingShare(listOf(textItem("hello"), attItem("photo.jpg")), 1726500000000L)
        val parsed = PendingShare.fromJson(share.toJson())
        assertEquals(share, parsed)
    }

    @Test
    fun `wire shape matches the iOS mirror`() {
        val json = PendingShare(listOf(textItem("hi")), 42L).toJson()
        assertEquals("hi", json.getJSONArray("items").getJSONObject(0).getString("value"))
        assertEquals("inlineText", json.getJSONArray("items").getJSONObject(0).getString("kind"))
        assertEquals(42L, json.getLong("timestamp"))
    }

    @Test
    fun `malformed element is skipped not fatal`() {
        val arr = JSONArray()
            .put("not an object")
            .put(JSONObject().put("kind", "inlineText").put("value", "survivor"))
            .put(12345)
        val parsed = PendingShare.fromJson(JSONObject().put("items", arr))
        assertEquals(listOf(textItem("survivor")), parsed?.items)
    }

    @Test
    fun `unknown kind and empty value are skipped`() {
        val arr = JSONArray()
            .put(JSONObject().put("kind", "bogus").put("value", "x"))
            .put(JSONObject().put("kind", "inlineText").put("value", ""))
            .put(JSONObject().put("kind", "attachment").put("value", "keep.txt"))
        val parsed = PendingShare.fromJson(JSONObject().put("items", arr))
        assertEquals(listOf(attItem("keep.txt")), parsed?.items)
    }

    @Test
    fun `missing or empty items yield null`() {
        assertNull(PendingShare.fromJson(JSONObject()))
        assertNull(PendingShare.fromJson(JSONObject().put("items", JSONArray())))
    }

    @Test
    fun `attachment and inlineText wires are distinct`() {
        assertTrue(
            PendingShare.Item.Kind.INLINE_TEXT.wire != PendingShare.Item.Kind.ATTACHMENT.wire
        )
    }
}
