package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [ChatViewModel] companion pure functions that are
 * directly accessible as [ChatViewModelUtils] top-level functions.
 *
 * Separated from [ChatViewModelUtilsTest] for organization.
 */
class ChatViewModelCompanionPureTest {

    // ── textDeltaThrottleMs ────────────────────────────────────────────────

    @Test fun `textDeltaThrottle under 500 returns 150ms`() {
        assertEquals(150L, textDeltaThrottleMs(0))
        assertEquals(150L, textDeltaThrottleMs(499))
    }

    @Test fun `textDeltaThrottle 500 to 1999 returns 300ms`() {
        assertEquals(300L, textDeltaThrottleMs(500))
        assertEquals(300L, textDeltaThrottleMs(1_999))
    }

    @Test fun `textDeltaThrottle 2000 to 31999 returns 500ms`() {
        assertEquals(500L, textDeltaThrottleMs(2_000))
        assertEquals(500L, textDeltaThrottleMs(31_999))
    }

    @Test fun `textDeltaThrottle 32000 to 63999 returns 1000ms`() {
        assertEquals(1_000L, textDeltaThrottleMs(32_000))
        assertEquals(1_000L, textDeltaThrottleMs(63_999))
    }

    @Test fun `textDeltaThrottle 64000 to 127999 returns 1500ms`() {
        assertEquals(1_500L, textDeltaThrottleMs(64_000))
        assertEquals(1_500L, textDeltaThrottleMs(127_999))
    }

    @Test fun `textDeltaThrottle 128000 and above returns 2000ms`() {
        assertEquals(2_000L, textDeltaThrottleMs(128_000))
        assertEquals(2_000L, textDeltaThrottleMs(1_000_000))
    }

    @Test fun `textDeltaThrottle boundary values are exact`() {
        assertEquals(150L, textDeltaThrottleMs(499))
        assertEquals(300L, textDeltaThrottleMs(500))
        assertEquals(300L, textDeltaThrottleMs(1_999))
        assertEquals(500L, textDeltaThrottleMs(2_000))
        assertEquals(500L, textDeltaThrottleMs(31_999))
        assertEquals(1_000L, textDeltaThrottleMs(32_000))
        assertEquals(1_000L, textDeltaThrottleMs(63_999))
        assertEquals(1_500L, textDeltaThrottleMs(64_000))
        assertEquals(1_500L, textDeltaThrottleMs(127_999))
        assertEquals(2_000L, textDeltaThrottleMs(128_000))
    }
}