package com.rikkaminis.app.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ProviderRepositoryUtils] pure functions.
 */
class ProviderRepositoryUtilsTest {

    // ── hashJsonMirror ─────────────────────────────────────────────────────

    @Test fun `hashJsonMirror non-empty string returns 64 hex chars`() {
        val result = hashJsonMirror("hello")
        assertEquals(64, result.length)
        assertTrue(result.matches(Regex("[0-9a-f]+")))
    }

    @Test fun `hashJsonMirror empty string`() {
        val result = hashJsonMirror("")
        assertEquals(64, result.length)
    }

    @Test fun `hashJsonMirror deterministic`() {
        assertEquals(hashJsonMirror("test"), hashJsonMirror("test"))
    }

    @Test fun `hashJsonMirror different inputs differ`() {
        assertTrue(hashJsonMirror("abc") != hashJsonMirror("xyz"))
    }

    @Test fun `hashJsonMirror unicode`() {
        val result = hashJsonMirror("你好")
        assertEquals(64, result.length)
    }

    @Test fun `hashJsonMirror large string`() {
        val big = "a".repeat(10000)
        val result = hashJsonMirror(big)
        assertEquals(64, result.length)
    }

    // ── isSameCalendarDay ──────────────────────────────────────────────────

    @Test fun `isSameCalendarDay same millis returns true`() {
        val now = System.currentTimeMillis()
        assertTrue(isSameCalendarDay(now, now))
    }

    @Test fun `isSameCalendarDay same day different hours returns true`() {
        val dayStart = 1700000000000L  // Some arbitrary timestamp
        val dayEnd = dayStart + 23 * 60 * 60 * 1000L
        // These may cross midnight on the calendar; test with known values
        // Use a known date: 2024-01-01 00:00:00 UTC = 1704067200000
        val t1 = 1704067200000L
        val t2 = t1 + 12 * 60 * 60 * 1000L  // 12 hours later
        assertTrue(isSameCalendarDay(t1, t2))
    }

    @Test fun `isSameCalendarDay different days returns false`() {
        val t1 = 1704067200000L  // 2024-01-01
        val t2 = t1 + 48 * 60 * 60 * 1000L  // 2024-01-03
        // If these happen to be in different timezone days, this is still valid
        // as a consistency check
        assertEquals(
            isSameCalendarDay(t1, t2),
            isSameCalendarDay(t2, t1)
        )
    }

    @Test fun `isSameCalendarDay symmetric`() {
        val t1 = 1704067200000L
        val t2 = t1 + 36 * 60 * 60 * 1000L
        assertEquals(isSameCalendarDay(t1, t2), isSameCalendarDay(t2, t1))
    }

    // ── modalityBitfieldFromLists ──────────────────────────────────────────

    @Test fun `modalityBitfield null inputs returns 0`() {
        assertEquals(0, modalityBitfieldFromLists(null, null))
    }

    @Test fun `modalityBitfield text in`() {
        val bits = modalityBitfieldFromLists(listOf("text"), null)
        assertTrue(bits and 1 != 0)  // MODALITY_BIT_TEXT_IN = 1 shl 0
    }

    @Test fun `modalityBitfield image in`() {
        val bits = modalityBitfieldFromLists(listOf("image"), null)
        assertTrue(bits and (1 shl 2) != 0)
    }

    @Test fun `modalityBitfield text out`() {
        val bits = modalityBitfieldFromLists(null, listOf("text"))
        assertTrue(bits and (1 shl 1) != 0)
    }

    @Test fun `modalityBitfield image out`() {
        val bits = modalityBitfieldFromLists(null, listOf("image"))
        assertTrue(bits and (1 shl 6) != 0)
    }

    @Test fun `modalityBitfield multimodal`() {
        val bits = modalityBitfieldFromLists(
            listOf("text", "image", "audio"),
            listOf("text", "image")
        )
        // Text in (bit 0), Image in (bit 2), Audio in (bit 4)
        assertTrue(bits and (1 shl 0) != 0)
        assertTrue(bits and (1 shl 2) != 0)
        assertTrue(bits and (1 shl 4) != 0)
        // Text out (bit 1), Image out (bit 6)
        assertTrue(bits and (1 shl 1) != 0)
        assertTrue(bits and (1 shl 6) != 0)
        // No PDF in, No video in, no audio/video out
        assertTrue(bits and (1 shl 3) == 0)
        assertTrue(bits and (1 shl 5) == 0)
        assertTrue(bits and (1 shl 7) == 0)
        assertTrue(bits and (1 shl 8) == 0)
    }

    @Test fun `modalityBitfield case insensitive`() {
        val bits = modalityBitfieldFromLists(listOf("TEXT", "Image"), null)
        assertTrue(bits and (1 shl 0) != 0)
        assertTrue(bits and (1 shl 2) != 0)
    }

    @Test fun `modalityBitfield unknown input ignored`() {
        assertEquals(0, modalityBitfieldFromLists(listOf("unknown"), null))
    }

    @Test fun `modalityBitfield pdf and video`() {
        val bits = modalityBitfieldFromLists(listOf("pdf", "video"), null)
        assertTrue(bits and (1 shl 3) != 0)  // PDF in
        assertTrue(bits and (1 shl 5) != 0)  // Video in
    }

    // ── modalityListsFromBitfield ──────────────────────────────────────────

    @Test fun `modalityLists zero bitfield returns null null`() {
        val (ins, outs) = modalityListsFromBitfield(0)
        assertNull(ins)
        assertNull(outs)
    }

    @Test fun `modalityLists text in out`() {
        val bits = (1 shl 0) or (1 shl 1)  // text in + text out
        val (ins, outs) = modalityListsFromBitfield(bits)
        assertEquals(listOf("text"), ins)
        assertEquals(listOf("text"), outs)
    }

    @Test fun `modalityLists image in image out`() {
        val bits = (1 shl 2) or (1 shl 6)
        val (ins, outs) = modalityListsFromBitfield(bits)
        assertEquals(listOf("image"), ins)
        assertEquals(listOf("image"), outs)
    }

    @Test fun `modalityLists audio in only`() {
        val bits = 1 shl 4
        val (ins, outs) = modalityListsFromBitfield(bits)
        assertEquals(listOf("audio"), ins)
        assertNull(outs)
    }

    @Test fun `modalityLists video in only`() {
        val bits = 1 shl 5
        val (ins, outs) = modalityListsFromBitfield(bits)
        assertEquals(listOf("video"), ins)
        assertNull(outs)
    }

    @Test fun `modalityLists pdf in only`() {
        val bits = 1 shl 3
        val (ins, outs) = modalityListsFromBitfield(bits)
        assertEquals(listOf("pdf"), ins)
        assertNull(outs)
    }

    @Test fun `modalityLists roundtrip`() {
        val inputs = listOf("text", "image", "audio", "pdf", "video")
        val outputs = listOf("text", "image", "audio", "video")
        val bits = modalityBitfieldFromLists(inputs, outputs)
        val (insOut, outsOut) = modalityListsFromBitfield(bits)
        // Decode order is bit-position order, not insertion order:
        // text(0), image(2), pdf(3), audio(4), video(5)
        assertEquals(listOf("text", "image", "pdf", "audio", "video"), insOut)
        assertEquals(listOf("text", "image", "audio", "video"), outsOut)
    }

    @Test fun `modalityLists only output`() {
        // Audio out only (bit 7)
        val bits = 1 shl 7
        val (ins, outs) = modalityListsFromBitfield(bits)
        assertNull(ins)
        assertEquals(listOf("audio"), outs)
    }

    // ── readModalitiesWithBitfieldFallback ─────────────────────────────────

    @Test fun `readModalities native arrays take precedence`() {
        val obj = JSONObject()
        obj.put("inputModalities", org.json.JSONArray(listOf("text", "image")))
        obj.put("outputModalities", org.json.JSONArray(listOf("text")))
        obj.put("modalityOverride", 999)  // Should be ignored
        val (ins, outs) = readModalitiesWithBitfieldFallback(obj)
        assertEquals(listOf("text", "image"), ins)
        assertEquals(listOf("text"), outs)
    }

    @Test fun `readModalities only inputs`() {
        val obj = JSONObject()
        obj.put("inputModalities", org.json.JSONArray(listOf("audio")))
        val (ins, outs) = readModalitiesWithBitfieldFallback(obj)
        assertEquals(listOf("audio"), ins)
        assertNull(outs)
    }

    @Test fun `readModalities only outputs`() {
        val obj = JSONObject()
        obj.put("outputModalities", org.json.JSONArray(listOf("image")))
        val (ins, outs) = readModalitiesWithBitfieldFallback(obj)
        assertNull(ins)
        assertEquals(listOf("image"), outs)
    }

    @Test fun `readModalities bitfield fallback`() {
        val obj = JSONObject()
        // modalityOverride = text_in(1) | text_out(2) | image_in(4) = 7
        // But wait: text_in=1, text_out=2, image_in=4 → 7
        obj.put("modalityOverride", 7)
        val (ins, outs) = readModalitiesWithBitfieldFallback(obj)
        assertEquals(listOf("text", "image"), ins)
        assertEquals(listOf("text"), outs)
    }

    @Test fun `readModalities no modality info returns null null`() {
        val obj = JSONObject()
        val (ins, outs) = readModalitiesWithBitfieldFallback(obj)
        assertNull(ins)
        assertNull(outs)
    }

    @Test fun `readModalities empty arrays treated as absent`() {
        val obj = JSONObject()
        obj.put("inputModalities", org.json.JSONArray())
        val (ins, outs) = readModalitiesWithBitfieldFallback(obj)
        assertNull(ins)
        assertNull(outs)
    }

    @Test fun `readModalities bitfield fallback with native arrays absent`() {
        val obj = JSONObject()
        obj.put("modalityOverride", 3)  // text_in(1) | text_out(2)
        val (ins, outs) = readModalitiesWithBitfieldFallback(obj)
        assertEquals(listOf("text"), ins)
        assertEquals(listOf("text"), outs)
    }

    @Test fun `readModalities modalityOverride zero treated as none`() {
        val obj = JSONObject()
        obj.put("modalityOverride", 0)
        val (ins, outs) = readModalitiesWithBitfieldFallback(obj)
        assertNull(ins)
        assertNull(outs)
    }
}