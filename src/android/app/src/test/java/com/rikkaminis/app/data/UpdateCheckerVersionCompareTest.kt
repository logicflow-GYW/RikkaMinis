package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/updatechecker-semver-prerelease] Pins the ordering contract of
 * [compareVersions]. Before this fix the function compared "1.0.0-beta" as
 * ["1","0","0","beta"] against ["1","0","0"] and returned a positive value,
 * i.e. a GitHub prerelease tag outranked the stable release.
 *
 * The first group pins the behaviour that must NOT change (it is what
 * UpdateChecker's normalized inputs actually exercise); the second group pins
 * the new semver prerelease rules.
 */
class UpdateCheckerVersionCompareTest {

    // ── release ordering (unchanged contract) ────────────────────────────

    @Test
    fun `numeric components compare numerically not lexically`() {
        assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(compareVersions("1.9.0", "1.10.0") < 0)
        assertTrue(compareVersions("2.0.0", "1.99.99") > 0)
    }

    @Test
    fun `equal releases compare zero`() {
        assertEquals(0, compareVersions("1.2.3", "1.2.3"))
        assertEquals(0, compareVersions("0.11", "0.11"))
    }

    @Test
    fun `missing segment keeps the pre-existing ordering`() {
        // Unchanged by this fix: "" sorts below "0", so 1.2 < 1.2.0. Kept
        // as-is deliberately (see VersionCompare KDoc); the update path feeds
        // consistently-shaped normalized versions.
        assertTrue(compareVersions("1.2.0", "1.2") > 0)
    }

    @Test
    fun `normalized UpdateChecker inputs behave as before`() {
        // normalizeTag strips the prerelease suffix, so this is the shape the
        // update check actually compares.
        assertTrue(compareVersions("0.12", "0.11") > 0)
        assertTrue(compareVersions("0.11", "0.12") < 0)
        assertEquals(0, compareVersions("0.11", "0.11"))
    }

    // ── prerelease precedence (the fix) ──────────────────────────────────

    @Test
    fun `stable release outranks its own prerelease`() {
        assertTrue(
            "1.0.0 must outrank 1.0.0-beta",
            compareVersions("1.0.0", "1.0.0-beta") > 0,
        )
        assertTrue(
            "1.0.0-beta must sort below 1.0.0",
            compareVersions("1.0.0-beta", "1.0.0") < 0,
        )
    }

    @Test
    fun `prerelease still outranks an older release`() {
        // The fix must not make prereleases invisible: a newer release line
        // with a prerelease suffix is still an upgrade.
        assertTrue(compareVersions("1.0.1-beta", "1.0.0") > 0)
        assertTrue(compareVersions("1.1.0-rc1", "1.0.9") > 0)
    }

    @Test
    fun `prerelease identifiers compare numerically then lexically`() {
        assertTrue(compareVersions("1.0.0-rc2", "1.0.0-rc1") > 0)
        // Dot-separated numeric identifiers compare numerically...
        assertTrue(compareVersions("1.0.0-rc.10", "1.0.0-rc.9") > 0)
        // ...but "rc10" is a SINGLE alphanumeric identifier, so it compares
        // lexically and "rc10" < "rc9" (semver §11.4.2 — the well-known
        // gotcha; a tag must be dotted `rc.10` to sort numerically).
        assertTrue(compareVersions("1.0.0-rc10", "1.0.0-rc9") < 0)
        assertTrue(compareVersions("1.0.0-beta", "1.0.0-alpha") > 0)
    }

    @Test
    fun `numeric prerelease identifier ranks below alphanumeric`() {
        // semver §11.4.3
        assertTrue(compareVersions("1.0.0-1", "1.0.0-alpha") < 0)
        assertTrue(compareVersions("1.0.0-alpha", "1.0.0-1") > 0)
    }

    @Test
    fun `normalizeTag strips the tag prefix and the prerelease suffix`() {
        assertEquals("1.0.0", normalizeTag("v1.0.0"))
        assertEquals("1.0.0", normalizeTag("V1.0.0"))
        assertEquals("1.0.0", normalizeTag("  1.0.0  "))
        assertEquals("0.1", normalizeTag("0.1-preview"))
        assertEquals("1.2.3", normalizeTag("1.2.3-rc1"))
        // No dash -> returned unchanged, build metadata included.
        assertEquals("1.0.0+42", normalizeTag("1.0.0+42"))
        // The rolling download tag normalizes to a NON-version string; the
        // caller must skip it (see UpdateChecker's candidate filter).
        assertEquals("android", normalizeTag("android-latest"))
    }

    @Test
    fun `CI build metadata does not make a build look newer or older`() {
        // The CI suffix changed from "-beta.<run>" to "+<run>"; both the old
        // and the new shape must compare EQUAL to the bare release, so an
        // installed build never sees itself as older than its own release.
        assertEquals(0, compareVersions(normalizeTag("1.0.0+42"), normalizeTag("1.0.0")))
        assertEquals(0, compareVersions(normalizeTag("1.0.0+42"), normalizeTag("1.0.0-beta.7")))
        assertTrue(compareVersions(normalizeTag("1.0.1+1"), normalizeTag("1.0.0+99")) > 0)
    }

    @Test
    fun `shorter prerelease list loses on equal prefix`() {
        // semver §11.4.4
        assertTrue(compareVersions("1.0.0-alpha", "1.0.0-alpha.1") < 0)
        assertTrue(compareVersions("1.0.0-alpha.1", "1.0.0-alpha") > 0)
    }

    @Test
    fun `build metadata is ignored`() {
        // semver §10: +build never participates in precedence.
        assertEquals(0, compareVersions("1.0.0+build5", "1.0.0+build1"))
        assertEquals(0, compareVersions("1.0.0-rc1+b1", "1.0.0-rc1+b2"))
    }

    @Test
    fun `equal prereleases compare zero`() {
        assertEquals(0, compareVersions("1.0.0-beta", "1.0.0-beta"))
    }

    @Test
    fun `comparison is antisymmetric across the fixed matrix`() {
        val versions = listOf(
            "0.9", "1.0.0-1", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-beta",
            "1.0.0-rc1", "1.0.0", "1.0.1", "1.10.0", "2.0.0",
        )
        for (a in versions) {
            assertEquals("self compare must be 0 for $a", 0, compareVersions(a, a))
            for (b in versions) {
                val ab = compareVersions(a, b)
                val ba = compareVersions(b, a)
                assertEquals(
                    "antisymmetry broken for $a vs $b (ab=$ab ba=$ba)",
                    -ab.coerceIn(-1, 1),
                    ba.coerceIn(-1, 1),
                )
            }
        }
    }
}
