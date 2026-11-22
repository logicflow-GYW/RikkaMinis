package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the group context-limit ladder: five power-of-two fixed stops plus one
 * K-unit custom stop. The retired unlimited sentinel is recognised as legacy
 * and never becomes a slider stop. Older persisted values that used to be
 * fixed stops (32K / 200K / 400K / 2M) classify as custom without being
 * rewritten.
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
    fun `custom labels always use K including above one million`() {
        assertEquals("800K", ContextLimitLadder.customLabel(800_000))
        assertEquals("1500K", ContextLimitLadder.customLabel(1_500_000))
        assertEquals("1950K", ContextLimitLadder.customLabel(1_950_000))
        assertEquals("200.5K", ContextLimitLadder.customLabel(200_500))
        assertEquals("2000K", ContextLimitLadder.customLabel(2_000_000))
    }

    @Test
    fun `custom input value is the K amount without suffix`() {
        assertEquals("200.5", ContextLimitLadder.customInputValue(200_500))
        assertEquals("800", ContextLimitLadder.customInputValue(800_000))
        assertEquals("2000", ContextLimitLadder.customInputValue(2_000_000))
    }

    @Test
    fun `slider stop maps presets and legacy values to custom`() {
        val customIndex = ContextLimitLadder.presets.size
        assertEquals(0, ContextLimitLadder.stepIndexFor(64_000, customIndex))
        assertEquals(4, ContextLimitLadder.stepIndexFor(1_000_000, customIndex))
        assertEquals(customIndex, ContextLimitLadder.stepIndexFor(32_000, customIndex))
        assertEquals(customIndex, ContextLimitLadder.stepIndexFor(200_000, customIndex))
        assertEquals(customIndex, ContextLimitLadder.stepIndexFor(2_000_000, customIndex))
        assertEquals(customIndex, ContextLimitLadder.stepIndexFor(null, customIndex))
    }

    @Test
    fun `legacy values render as custom instead of being rewritten`() {
        assertTrue(ContextLimitLadder.isCustomValue(32_000))
        assertTrue(ContextLimitLadder.isCustomValue(200_000))
        assertTrue(ContextLimitLadder.isCustomValue(400_000))
        assertTrue(ContextLimitLadder.isCustomValue(2_000_000))
    }

    @Test
    fun `preset and retired unlimited values are not custom`() {
        assertFalse(ContextLimitLadder.isCustomValue(null))
        assertFalse(ContextLimitLadder.isCustomValue(64_000))
        assertFalse(ContextLimitLadder.isCustomValue(1_000_000))
        assertFalse(ContextLimitLadder.isCustomValue(Int.MAX_VALUE))
    }

    @Test
    fun `retired unlimited sentinel is detected as legacy`() {
        assertTrue(ContextLimitLadder.isLegacyUnlimited(Int.MAX_VALUE))
        assertFalse(ContextLimitLadder.isLegacyUnlimited(Int.MAX_VALUE - 1))
        assertFalse(ContextLimitLadder.isLegacyUnlimited(null))
    }

    @Test
    fun `custom K input accepts the floor and rejects below it`() {
        assertEquals(64_000, ContextLimitLadder.parseCustomK("64"))
        assertNull(ContextLimitLadder.parseCustomK("63.9"))
    }

    @Test
    fun `custom K input converts common provider values`() {
        assertEquals(200_000, ContextLimitLadder.parseCustomK("200"))
        assertEquals(400_000, ContextLimitLadder.parseCustomK("400"))
        assertEquals(2_000_000, ContextLimitLadder.parseCustomK("2000"))
        assertEquals(200_500, ContextLimitLadder.parseCustomK("200.5"))
    }

    @Test
    fun `custom K input rejects blank non numeric and out of range text`() {
        assertNull(ContextLimitLadder.parseCustomK(""))
        assertNull(ContextLimitLadder.parseCustomK("  "))
        assertNull(ContextLimitLadder.parseCustomK("64k"))
        assertNull(ContextLimitLadder.parseCustomK("9999999"))
        assertNull(ContextLimitLadder.parseCustomK("2147483.647"))
    }

    @Test
    fun `custom K input keeps digits and one decimal point`() {
        assertEquals("200.5", ContextLimitLadder.sanitizeCustomKInput("200.5"))
        assertEquals("2000", ContextLimitLadder.sanitizeCustomKInput("2,000"))
        assertEquals("2.05", ContextLimitLadder.sanitizeCustomKInput("2.0.5"))
        assertEquals("", ContextLimitLadder.sanitizeCustomKInput("abc"))
    }
}
