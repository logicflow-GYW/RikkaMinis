package com.rikkaminis.app.data

/**
 * Publisher-declared digest for a release asset (GitHub's `assets[].digest`,
 * e.g. `"sha256:2cf4b9ad…"`) and the verdict a downloaded file earns against
 * it.
 *
 * Why this exists: [UpdateChecker] used to hash the APK it downloaded and
 * compare that hash only against *itself* (via [PendingUpdateStore]). Nothing
 * ever compared the bytes against what the release publisher declared, so a
 * truncated/rewritten/compromised download was indistinguishable from a good
 * one — the local hash proves the file did not change *after* it was written,
 * not that it is the file we asked for.
 *
 * Extracted as pure functions (same reason [compareVersions] lives in
 * `data/VersionCompare.kt`) so the parsing and the accept/refuse rules are
 * unit-testable on the JVM.
 */

/** A digest declared by the release publisher, split into algorithm + lowercase hex. */
data class PublisherDigest(
    /** Lowercase algorithm name, e.g. `"sha256"`. */
    val algorithm: String,
    /** Lowercase hex payload. */
    val hex: String,
) {
    /**
     * True when [judgeDownloadIntegrity] can actually verify against this —
     * i.e. the algorithm this app computes locally. False means the download
     * degrades to a size check ([DownloadIntegrity.UNSUPPORTED_ALGORITHM]).
     */
    val isSupported: Boolean get() = algorithm == SUPPORTED_ALGORITHM
}

/**
 * Digest algorithms whose verification is wired end-to-end. GitHub publishes
 * `sha256:<hex>` today; anything else is deliberately NOT verified (and never
 * silently *accepted* as verified — see [DownloadIntegrity.UNSUPPORTED_ALGORITHM]).
 */
private const val SUPPORTED_ALGORITHM = "sha256"
private const val SHA256_HEX_LEN = 64
private val HEX_ONLY = Regex("[0-9a-fA-F]+")

/**
 * Parses GitHub's asset `digest` field. Returns null when there is nothing to
 * verify (absent / blank / malformed) — callers treat null as "publisher
 * declared no digest", which degrades to the size check rather than refusing
 * the update. Older releases genuinely carry no digest, so a null here is a
 * normal state, not an error.
 *
 * Accepts:
 *  - `"sha256:<hex>"` (GitHub's shape),
 *  - a bare 64-char hex string, assumed sha256: the only digest this app
 *    computes. Assuming wrong is safe — the comparison then fails and the
 *    download is refused rather than accepted.
 *  - surrounding whitespace.
 *
 * Returns null for: blank input, a non-hex payload, or a bare hex string whose
 * length doesn't match sha256 (nothing we could verify it against).
 */
fun parsePublisherDigest(raw: String?): PublisherDigest? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    val colon = s.indexOf(':')
    val algo = (if (colon >= 0) s.substring(0, colon) else "").trim().lowercase()
    val value = (if (colon >= 0) s.substring(colon + 1) else s).trim()
    if (value.isEmpty() || !HEX_ONLY.matches(value)) return null
    val algorithm = when {
        algo.isNotEmpty() -> algo
        value.length == SHA256_HEX_LEN -> SUPPORTED_ALGORITHM
        else -> return null
    }
    return PublisherDigest(algorithm = algorithm, hex = value.lowercase())
}

/**
 * Outcome of checking a freshly downloaded APK against what the release
 * declared. [isFailure] verdicts must delete the file and refuse to install;
 * the others mean "installable, but here is how much we actually verified".
 */
enum class DownloadIntegrity {
    /** A publisher digest was present and matched the downloaded bytes. */
    VERIFIED,

    /** No publisher digest — size matched (or the asset declared no size). */
    SIZE_ONLY,

    /**
     * The publisher declared a digest, but in an algorithm this app cannot
     * compute (e.g. sha512). Verified as far as size goes, and logged loudly
     * so the downgrade is visible instead of silent.
     */
    UNSUPPORTED_ALGORITHM,

    /** Publisher declared sha256, but hashing the local file failed. Refuse. */
    HASH_FAILED,

    /** Publisher declared sha256 and the downloaded bytes do not match it. Refuse. */
    DIGEST_MISMATCH,

    /** No usable digest and the byte count disagrees with the release asset. Refuse. */
    SIZE_MISMATCH,
    ;

    /** True when the download must be discarded rather than handed to the installer. */
    val isFailure: Boolean
        get() = this == HASH_FAILED || this == DIGEST_MISMATCH || this == SIZE_MISMATCH

    /** True only when a publisher-declared hash vouched for the file. */
    val isVerified: Boolean
        get() = this == VERIFIED
}

/**
 * Decides what to do with a downloaded file.
 *
 * Order matters:
 *  1. A usable publisher digest is the strongest signal, so when it is present
 *     it decides alone. A matching digest wins even if the size metadata
 *     disagrees (the asset was re-uploaded between check and download); a
 *     mismatching digest fails even if the size agrees.
 *  2. Without a digest we fall back to the byte count — the same rule
 *     [PendingUpdateStore.verify] already applied at resume time, now applied
 *     at download time too, where it can still be acted on.
 *  3. Neither available → accept, but report [DownloadIntegrity.SIZE_ONLY] so
 *     the caller can say "not verified" instead of implying it was.
 *
 * @param declared publisher digest, or null when the release declared none
 * @param expectedSize asset `size` from the API (0/negative = unknown)
 * @param actualSize bytes on disk
 * @param actualSha256 locally computed sha256, or null when hashing failed
 */
fun judgeDownloadIntegrity(
    declared: PublisherDigest?,
    expectedSize: Long,
    actualSize: Long,
    actualSha256: String?,
): DownloadIntegrity {
    if (declared != null) {
        if (!declared.isSupported) {
            return if (expectedSize > 0 && actualSize != expectedSize) {
                DownloadIntegrity.SIZE_MISMATCH
            } else {
                DownloadIntegrity.UNSUPPORTED_ALGORITHM
            }
        }
        if (actualSha256 == null) return DownloadIntegrity.HASH_FAILED
        return if (actualSha256.equals(declared.hex, ignoreCase = true)) {
            DownloadIntegrity.VERIFIED
        } else {
            DownloadIntegrity.DIGEST_MISMATCH
        }
    }
    if (expectedSize > 0 && actualSize != expectedSize) return DownloadIntegrity.SIZE_MISMATCH
    return DownloadIntegrity.SIZE_ONLY
}


/**
 * Decides whether a download request may be satisfied by an already-verified
 * pending record instead of the network ([fix/update-pending-resume]). The
 * pending store does not record the request URL, so correlation uses the keys
 * the record does have — the same signals [judgeDownloadIntegrity] weighs for
 * fresh downloads, applied to what [PendingUpdateStore.verify] already
 * re-hashed:
 *
 *  1. A supported declared digest decides alone: it must match the recorded
 *     publisher digest (or our own re-hash of the bytes when the publisher
 *     declared none at download time). No match → null, the caller downloads
 *     afresh and the full digest judgement applies there. This is stricter
 *     than re-using a size match: a declared sha256 we cannot confirm must
 *     not be answered with bytes we never checked against it.
 *  2. Without a usable declared digest, the declared asset size must match
 *     the recorded size — the same standard the size-only download path
 *     accepts under.
 *  3. Neither signal correlates → null. Never hand back a different release
 *     as "success".
 *
 * Extracted as a pure function (same reason [judgeDownloadIntegrity] lives
 * here) so the resume/redo decision is unit-testable on the JVM.
 *
 * @param recordedPublisherDigest publisher hex persisted with the pending record, or null
 * @param recordedSha256 our own re-hash persisted with the record, or null
 * @param recordedSize byte count persisted with the record
 * @param declared digest the current request declared, or null when none
 * @param expectedSize asset size the current request declared (0 = unknown)
 * @return the verdict to report when resuming, or null to download afresh
 */
fun judgeResumePending(
    recordedPublisherDigest: String?,
    recordedSha256: String?,
    recordedSize: Long,
    declared: PublisherDigest?,
    expectedSize: Long,
): DownloadIntegrity? {
    val declaredSha = declared?.takeIf { it.isSupported }
    if (declaredSha != null) {
        val matches =
            recordedPublisherDigest?.equals(declaredSha.hex, ignoreCase = true) == true ||
                recordedSha256?.equals(declaredSha.hex, ignoreCase = true) == true
        return if (matches) DownloadIntegrity.VERIFIED else null
    }
    return if (expectedSize > 0 && recordedSize == expectedSize) {
        DownloadIntegrity.SIZE_ONLY
    } else {
        null
    }
}
