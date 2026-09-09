package com.rikkaminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P4-rootfs-disk-guard] Pure-JVM tests for [RootfsManager.hasEnoughSpaceForRootfs].
 *
 * Pins down the disk-space gate contract: installation is refused when the
 * usable space is below `compressedAssetBytes × 4 + 64 MiB`, and allowed when
 * it is exactly at the threshold or above.
 */
class RootfsDiskGuardTest {

    // 8.4 MiB compressed asset (matches bundled alpine-minirootfs.tar).
    private val ASSET = 8_468_480L
    // Estimated extracted size: 8_468_480 × 4 = 33_873_920; + 64 MiB margin.
    private val MARGIN = 64L * 1024L * 1024L
    private val REQUIRED = ASSET * 4L + MARGIN

    @Test
    fun `exact threshold fits`() {
        assertTrue(RootfsManager.hasEnoughSpaceForRootfs(REQUIRED, ASSET))
    }

    @Test
    fun `one byte short fails`() {
        assertFalse(RootfsManager.hasEnoughSpaceForRootfs(REQUIRED - 1, ASSET))
    }

    @Test
    fun `zero-length asset only needs the margin`() {
        // A 0-byte asset has nothing to extract, but installation still does
        // real work (integrity manifest, apk world snapshot, first-boot
        // package ops), so the 64 MiB margin applies by itself.
        assertTrue(RootfsManager.hasEnoughSpaceForRootfs(MARGIN, 0))
        assertFalse(RootfsManager.hasEnoughSpaceForRootfs(MARGIN - 1, 0))
        assertFalse(RootfsManager.hasEnoughSpaceForRootfs(0, 0))
    }

    @Test
    fun `plenty of space passes`() {
        assertTrue(RootfsManager.hasEnoughSpaceForRootfs(REQUIRED + 1_000_000_000L, ASSET))
    }

    @Test
    fun `negative usable space fails`() {
        assertFalse(RootfsManager.hasEnoughSpaceForRootfs(-1, ASSET))
    }

    @Test
    fun `negative asset size fails`() {
        assertFalse(RootfsManager.hasEnoughSpaceForRootfs(REQUIRED, -1))
    }

    @Test
    fun `zero usable space fails when asset present`() {
        assertFalse(RootfsManager.hasEnoughSpaceForRootfs(0, ASSET))
    }
}