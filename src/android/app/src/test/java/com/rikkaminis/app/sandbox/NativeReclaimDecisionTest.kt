package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [shouldContinueNativeReclaim] — the pure decision function
 * used by the bounded iterative GC loop in [ExecutionCoordinator.postRecycleMemoryRecovery].
 *
 * The function decides whether another GC round is worth trying, given:
 * - how much native heap this round actually freed (0 means no effect)
 * - how many rounds have been used so far
 * - the max rounds budget
 * - the current native heap vs the locked floor
 *
 * The goal is to avoid the "single GC + 50ms give up" failure mode that
 * permanently locks the session (2026-08-12, 5.5GB app native heap).
 */
class NativeReclaimDecisionTest {

    // ── shouldContinueNativeReclaim ───────────────────────────────────
    // Decision rule (JVM-testable): keep going only while budget remains AND
    // native heap is still at/above the locked floor. freedThisRoundMb is NOT
    // a decision input (a 0-free round doesn't predict later rounds), but is
    // kept in the signature for Log/reporting symmetry.

    @Test
    fun `continue while native still above floor and budget remains`() {
        assertTrue(shouldContinueNativeReclaim(
            freedThisRoundMb = 50L,  // freed 50MB (not a decision input)
            roundsUsed = 1,
            maxRounds = 3,
            nativeNowMb = 200L,       // still above floor
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `stop when native drops below floor even if this round freed nothing`() {
        assertFalse(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,   // no effect
            roundsUsed = 1,
            maxRounds = 3,
            nativeNowMb = 100L,       // below floor
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `stop when at max rounds even if memory still high`() {
        assertFalse(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,
            roundsUsed = 3,
            maxRounds = 3,
            nativeNowMb = 500L,       // still high
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `continue when freed nothing but native still above floor`() {
        // 0MB freed is NOT a stop signal by itself — native is still above the
        // floor, so keep trying (objects may become free as concurrent GC advances).
        assertTrue(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,   // no effect this round
            roundsUsed = 1,
            maxRounds = 3,
            nativeNowMb = 300L,       // still above floor
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `stop immediately when native below floor`() {
        assertFalse(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,
            roundsUsed = 1,
            maxRounds = 3,
            nativeNowMb = 80L,        // well below floor
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `last round at budget but still above floor stops`() {
        assertFalse(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,
            roundsUsed = 3,
            maxRounds = 3,
            nativeNowMb = 150L,       // still above floor, but budget exhausted
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `at exact floor continues`() {
        assertTrue(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,
            roundsUsed = 1,
            maxRounds = 3,
            nativeNowMb = 120L,       // exactly at floor (>=), continue
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `one below floor stops regardless of freed`() {
        assertFalse(shouldContinueNativeReclaim(
            freedThisRoundMb = 60L,   // freed 60MB, but already below floor
            roundsUsed = 1,
            maxRounds = 3,
            nativeNowMb = 100L,       // below floor
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `way above floor keeps going from start`() {
        assertTrue(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,
            roundsUsed = 1,
            maxRounds = 3,
            nativeNowMb = 1024L,       // 1GB, way above floor
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `second round continues while above floor`() {
        assertTrue(shouldContinueNativeReclaim(
            freedThisRoundMb = 10L,
            roundsUsed = 2,
            maxRounds = 3,
            nativeNowMb = 400L,
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `one below floor on first round stops`() {
        assertFalse(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,
            roundsUsed = 1,
            maxRounds = 3,
            nativeNowMb = 118L,
            lockedFloorMb = 120L,
        ))
    }

    @Test
    fun `maxRounds budget zero never continues`() {
        assertFalse(shouldContinueNativeReclaim(
            freedThisRoundMb = 0L,
            roundsUsed = 0,
            maxRounds = 0,
            nativeNowMb = 500L,
            lockedFloorMb = 120L,
        ))
    }
}
