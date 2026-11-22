package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the group context-limit ladder: five power-of-two fixed stops, one
 * custom stop, and the unlimited sentinel. Legacy persisted values that used
 * to be fixed stops (32K / 200K / 400K / 2M) must classify as custom without
 * being rewritten.
 */
class ContextLimitLadderTest {

    @Test
    fun `fixed stops are the five power-of-two values`() {
        assertEquals(
            listOf(64_000, 128_000, 256_000, 512_000, 1_000_000),
            ContextLimitLadder.presets,
        )
    }

    @Test
    fun `preset labels stay compact`() {
        assertEquals("64K", ContextLimitLadder.label(64_000))
        assertEquals("128K", ContextLimitLadder.label(128_000))
        assertEquals("256K", ContextLimitLadder.label(256_000))
        assertEquals("512K", ContextLimitLadder.label(512_000))
        assertEquals("1M", ContextLimitLadder.label(1_000_000))
    }

    @Test
    fun `custom labels are exact token counts not K M approximations`() {
        assertEquals("1,500,000", ContextLimitLadder.customLabel(1_500_000))
        assertEquals("1,950,000", ContextLimitLadder.customLabel(1_950_000))
        assertEquals("200,500", ContextLimitLadder.customLabel(200_500))
        assertEquals("2,000,000", ContextLimitLadder.customLabel(2_000_000))
    }

    @Test
    fun `slider stop maps presets legacy values and unlimited`() {
        val customIndex = ContextLimitLadder.presets.size
        val unlimitedIndex = customIndex + 1
        assertEquals(0, ContextLimitLadder.stepIndexFor(64_000, customIndex, unlimitedIndex))
        assertEquals(4, ContextLimitLadder.stepIndexFor(1_000_000, customIndex, unlimitedIndex))
        assertEquals(customIndex, ContextLimitLadder.stepIndexFor(32_000, customIndex, unlimitedIndex))
        assertEquals(customIndex, ContextLimitLadder.stepIndexFor(200_000, customIndex, unlimitedIndex))
        assertEquals(customIndex, ContextLimitLadder.stepIndexFor(2_000_000, customIndex, unlimitedIndex))
        assertEquals(unlimitedIndex, ContextLimitLadder.stepIndexFor(null, customIndex, unlimitedIndex))
        assertEquals(unlimitedIndex, ContextLimitLadder.stepIndexFor(Int.MAX_VALUE, customIndex, unlimitedIndex))
    }

    @Test
    fun `legacy values render as custom instead of being rewritten`() {
        assertTrue(ContextLimitLadder.isCustomValue(32_000))
        assertTrue(ContextLimitLadder.isCustomValue(200_000))
        assertTrue(ContextLimitLadder.isCustomValue(400_000))
        assertTrue(ContextLimitLadder.isCustomValue(2_000_000))
    }

    @Test
    fun `preset and unlimited values are not custom`() {
        assertFalse(ContextLimitLadder.isCustomValue(null))
        assertFalse(ContextLimitLadder.isCustomValue(64_000))
        assertFalse(ContextLimitLadder.isCustomValue(1_000_000))
        assertFalse(ContextLimitLadder.isCustomValue(Int.MAX_VALUE))
    }

    @Test
    fun `custom input accepts the floor and rejects below it`() {
        assertEquals(64_000, ContextLimitLadder.parseCustom("64000"))
        assertNull(ContextLimitLadder.parseCustom("63999"))
    }

    @Test
    fun `custom input accepts provider-specific values`() {
        assertEquals(200_000, ContextLimitLadder.parseCustom("200000"))
        assertEquals(400_000, ContextLimitLadder.parseCustom("400000"))
        assertEquals(2_000_000, ContextLimitLadder.parseCustom("2000000"))
    }

    @Test
    fun `custom input rejects blank non numeric and out of range text`() {
        assertNull(ContextLimitLadder.parseCustom(""))
        assertNull(ContextLimitLadder.parseCustom("  "))
        assertNull(ContextLimitLadder.parseCustom("64k"))
        assertNull(ContextLimitLadder.parseCustom("9999999999"))
        assertNull(ContextLimitLadder.parseCustom(Int.MAX_VALUE.toString()))
    }
}
