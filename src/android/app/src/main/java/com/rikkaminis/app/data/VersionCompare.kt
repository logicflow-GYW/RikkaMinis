package com.rikkaminis.app.data

/**
 * Semver-style ordering for the version strings this app compares.
 *
 * Extracted from [UpdateChecker] so the comparison is unit-testable (it was a
 * private member with no in-repo test; the only coverage was a hand-copied
 * shadow in the sandbox verification scripts).
 *
 * Shape: `MAJOR[.MINOR[.PATCH...]][-PRERELEASE][+BUILD]`.
 *
 * Release components:
 *  - compared left to right; a numeric component compares numerically, a
 *    non-numeric one lexically, and a missing component compares as "" (so
 *    "1.2" still sorts *below* "1.2.0" — see the note at the bottom).
 *
 * Prerelease (semver §11), reached only when the release parts are equal:
 *  - a version WITHOUT a prerelease outranks the same release WITH one:
 *    "1.0.0" > "1.0.0-beta"  ← this is the rule the old implementation got
 *    backwards ("beta" compared as a 4th component against "" and won, so a
 *    GitHub prerelease tag outranked the stable release);
 *  - prerelease identifiers compare dot-separated, left to right: numeric
 *    identifiers compare numerically and rank BELOW alphanumeric ones, and a
 *    shorter identifier list is lower when the shared prefix is equal
 *    ("1.0.0-alpha" < "1.0.0-alpha.1").
 *
 * Build metadata (`+...`) never participates in precedence (semver §10).
 *
 * NOTE for [UpdateChecker] callers: every call site feeds *normalized*
 * versions ([normalizeTag] strips the prerelease suffix), so on the
 * update-check path both sides are bare release versions and the prerelease
 * rules are unreachable today. They exist so this function is correct for any
 * future caller instead of quietly inverting semver.
 */
fun compareVersions(a: String, b: String): Int {
    val (aRelease, aPre) = splitPrerelease(a)
    val (bRelease, bPre) = splitPrerelease(b)

    val releaseCmp = compareReleaseParts(aRelease, bRelease)
    if (releaseCmp != 0) return releaseCmp

    return when {
        aPre == null && bPre == null -> 0
        aPre == null -> 1 // stable outranks its own prerelease
        bPre == null -> -1
        else -> comparePrereleaseParts(aPre, bPre)
    }
}

/** Splits `X.Y.Z-pre+build` into ("X.Y.Z", "pre"). Build metadata is dropped. */
private fun splitPrerelease(version: String): Pair<String, String?> {
    val noBuild = version.substringBefore('+')
    val dash = noBuild.indexOf('-')
    return if (dash < 0) noBuild to null
    else noBuild.substring(0, dash) to noBuild.substring(dash + 1)
}

/**
 * Normalizes a GitHub tag (or a local `BuildConfig.VERSION_NAME`) into the
 * string [compareVersions] consumes.
 *
 * Extracted from [UpdateChecker] verbatim so the parsing is unit-testable:
 *  - a leading `v`/`V` is dropped ("v1.0.0" -> "1.0.0");
 *  - everything from the first `-` on is dropped, so prerelease tags collapse
 *    onto their release line ("0.1-preview" -> "0.1", "1.2.3-rc1" -> "1.2.3").
 *    That collapse is intentional product behaviour: the same numeric release
 *    is one version regardless of its prerelease suffix. It also means the
 *    semver prerelease rules in [compareVersions] are unreachable from the
 *    update-check path (see the note there).
 *  - build metadata (`+<run>`) is kept here and ignored by [compareVersions],
 *    so CI builds stay distinguishable in the About page without turning
 *    "1.0.0" into a prerelease.
 *
 * Returns the input unchanged when it carries no `-` (e.g. "1.0.0+42").
 */
fun normalizeTag(tag: String): String {
    val trimmed = tag.trim().removePrefix("v").removePrefix("V")
    // "0.1-preview" → "0.1"; "0.1.0" → "0.1.0"; "1.2.3-rc1" → "1.2.3"
    val dashIdx = trimmed.indexOf('-')
    return if (dashIdx > 0) trimmed.substring(0, dashIdx) else trimmed
}

private fun compareReleaseParts(a: String, b: String): Int {
    val ap = a.split('.')
    val bp = b.split('.')
    val n = maxOf(ap.size, bp.size)
    for (i in 0 until n) {
        val x = ap.getOrNull(i) ?: ""
        val y = bp.getOrNull(i) ?: ""
        val xi = x.toIntOrNull()
        val yi = y.toIntOrNull()
        val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
        if (c != 0) return c
    }
    return 0
}

private fun comparePrereleaseParts(a: String, b: String): Int {
    val ap = a.split('.')
    val bp = b.split('.')
    val n = maxOf(ap.size, bp.size)
    for (i in 0 until n) {
        val x = ap.getOrNull(i)
        val y = bp.getOrNull(i)
        // Shorter list loses once the shared prefix is equal (semver §11.4.4).
        if (x == null) return -1
        if (y == null) return 1
        val xi = x.toIntOrNull()
        val yi = y.toIntOrNull()
        val c = when {
            xi != null && yi != null -> xi.compareTo(yi)
            xi != null -> -1 // numeric identifiers rank below alphanumeric
            yi != null -> 1
            else -> x.compareTo(y)
        }
        if (c != 0) return c
    }
    return 0
}
