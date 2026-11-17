package com.rikkaminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [W1118-D5 F-08] `PendingUpdateStore.getPending`'s KDoc promised to reject a
 * record whose target version is no longer newer than the running build, but
 * only [UpdateChecker.resumablePending] performed that comparison — every
 * future direct caller would have been handed a download for the build it is
 * already running.
 *
 * The gate is pure ([pendingTargetIsStale]) so it can be pinned without
 * BuildConfig / SharedPreferences; `getPending` calls it with
 * `BuildConfig.VERSION_NAME` by default.
 */
class PendingTargetStaleGateTest {

    @Test
    fun `the version already installed is stale`() {
        assertTrue(pendingTargetIsStale("1.0.0", "1.0.0"))
    }

    @Test
    fun `an older target is stale`() {
        assertTrue(pendingTargetIsStale("0.9.9", "1.0.0"))
    }

    @Test
    fun `a newer target is resumable`() {
        assertFalse(pendingTargetIsStale("1.0.1", "1.0.0"))
    }

    @Test
    fun `the running build suffix and a v prefix do not decide the comparison`() {
        // BuildConfig.VERSION_NAME carries the CI run number ("1.0.0+3209");
        // comparing it raw would make every build look older than itself.
        assertTrue(pendingTargetIsStale("1.0.0", "1.0.0+3209"))
        assertFalse(pendingTargetIsStale("1.0.1", "v1.0.0+3209"))
    }

    @Test
    fun `a prerelease of the installed version is stale`() {
        // normalizeTag strips the prerelease on both sides; the pending record
        // for 1.0.0-rc1 cannot be resumed onto 1.0.0.
        assertTrue(pendingTargetIsStale("1.0.0-rc1", "1.0.0"))
    }
}
