package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit-cs0913] Pure-JVM tests for the native-offload back-pressure policy.
 *
 * The policy only decides *whether* a measurement is worth reporting — it must
 * stay free of Android dependencies so the thresholds and the boundaries can be
 * pinned here. Boundary semantics matter: the constants are inclusive
 * (`>= WARN`) so that a request waiting exactly QUEUE_WARN_MS is reported.
 */
class OffloadQueuePolicyTest {

    @Test
    fun queueBelowThresholdIsNotReported() {
        assertFalse(OffloadQueuePolicy.shouldReportQueue(0))
        assertFalse(OffloadQueuePolicy.shouldReportQueue(OffloadQueuePolicy.QUEUE_WARN_MS - 1))
    }

    @Test
    fun queueAtOrAboveThresholdIsReported() {
        assertTrue(OffloadQueuePolicy.shouldReportQueue(OffloadQueuePolicy.QUEUE_WARN_MS))
        assertTrue(OffloadQueuePolicy.shouldReportQueue(OffloadQueuePolicy.QUEUE_WARN_MS + 1))
        assertTrue(OffloadQueuePolicy.shouldReportQueue(30_000))
    }

    @Test
    fun execAtOrAboveThresholdIsReported() {
        assertFalse(OffloadQueuePolicy.shouldReportExec(OffloadQueuePolicy.EXEC_WARN_MS - 1))
        assertTrue(OffloadQueuePolicy.shouldReportExec(OffloadQueuePolicy.EXEC_WARN_MS))
    }

    @Test
    fun eitherDimensionTriggersReport() {
        // Fast handler but starved by the pool.
        assertTrue(OffloadQueuePolicy.shouldReport(OffloadQueuePolicy.QUEUE_WARN_MS, 5))
        // Instant slot but long-running handler.
        assertTrue(OffloadQueuePolicy.shouldReport(0, OffloadQueuePolicy.EXEC_WARN_MS))
        // Neither.
        assertFalse(OffloadQueuePolicy.shouldReport(1, 1))
    }

    @Test
    fun describeCarriesHandlerAndBothDurations() {
        val s = OffloadQueuePolicy.describe("model-use", 3_500, 61_000)
        assertEquals("handler=model-use queue=3500ms exec=61000ms", s)
    }

    @Test
    fun thresholdsStayOrdered() {
        // A queue warning must be possible without an exec warning and vice versa —
        // guards against someone collapsing the two constants into one.
        assertTrue(OffloadQueuePolicy.QUEUE_WARN_MS < OffloadQueuePolicy.EXEC_WARN_MS)
    }
}
