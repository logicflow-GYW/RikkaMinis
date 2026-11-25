package com.rikkaminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorativeAnimationTest {
    @Test fun `phase traverses wraps and respects stagger`() {
        assertEquals(0f, decorativePhase(0L, 1000), 0f)
        assertEquals(0.25f, decorativePhase(250_000_000L, 1000), 0.000001f)
        assertEquals(0f, decorativePhase(1_000_000_000L, 1000), 0f)
        assertEquals(0f, decorativePhase(120_000_000L, 700, 120), 0f)
        assertEquals(580f / 700f, decorativePhase(0L, 700, 120), 0.000001f)
    }

    @Test fun `phase is bounded at float wrap negative time and large offsets`() {
        for (time in listOf(0L, -1L, 999_999_999L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            for (offset in listOf(0, 120, Int.MIN_VALUE, Int.MAX_VALUE)) {
                val phase = decorativePhase(time, 1000, offset)
                assertTrue("time=$time offset=$offset phase=$phase", phase >= 0f && phase < 1f)
            }
        }
        assertEquals(0f, decorativePhase(123L, 0), 0f)
        assertEquals(0f, decorativePhase(123L, -1), 0f)
    }

    @Test fun `ping pong follows reverse repeat for wrapped and unwrapped phase`() {
        assertEquals(0f, decorativePingPong(0f), 0f)
        assertEquals(0.5f, decorativePingPong(0.25f), 0f)
        assertEquals(1f, decorativePingPong(0.5f), 0f)
        assertEquals(0.5f, decorativePingPong(0.75f), 0f)
        assertEquals(0.5f, decorativePingPong(1.25f), 0f)
        assertEquals(0.5f, decorativePingPong(-0.25f), 0f)
    }

    @Test fun `frame publication is throttled to thirty fps not the display rate`() {
        assertEquals(33_333_333L, DecorativeClock.INTERVAL_NS)
        assertTrue(decorativeFrameDue(0L, 1L))
        assertFalse(decorativeFrameDue(1L, 33_333_333L))
        assertTrue(decorativeFrameDue(1L, 33_333_334L))
    }

    @Test fun `lease releases on stop and disposal without double release`() {
        var acquires = 0
        var releases = 0
        val lease = DecorativeClockLease({ acquires++ }, { releases++ })
        lease.setStarted(false)
        lease.setStarted(true)
        lease.setStarted(true)
        assertEquals(1, acquires)
        lease.setStarted(false)
        lease.setStarted(false)
        lease.dispose()
        assertEquals(1, releases)
        lease.setStarted(true)
        lease.dispose()
        lease.dispose()
        assertEquals(2, acquires)
        assertEquals(2, releases)
    }

    @Test fun `independent leases retain the shared subscription until the last reader leaves`() {
        var readers = 0
        val first = DecorativeClockLease({ readers++ }, { readers-- })
        val second = DecorativeClockLease({ readers++ }, { readers-- })
        first.setStarted(true)
        second.setStarted(true)
        assertEquals(2, readers)
        first.dispose()
        assertEquals(1, readers)
        second.setStarted(false)
        assertEquals(0, readers)
    }
}
