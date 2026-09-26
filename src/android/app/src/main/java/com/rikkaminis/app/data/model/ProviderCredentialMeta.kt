package com.rikkaminis.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * [retained-schema:multi-api-key] Serialized metadata for one credential of an
 * instance — kept after the multi-key feature was removed, because the
 * `credentials_json` column it backs is part of schema version 11 on installed
 * devices (see [ProviderInstance.credentials] for the removal path).
 *
 * ## Why the secret is not a field here
 *
 * The secret lives ONLY in EncryptedSharedPreferences, keyed
 * `apikey_<instanceId>`. This object is the part that is persisted in the
 * config document — the Room row, the JSON mirror, the backup and the sync
 * payload — and therefore the part that is *not* encrypted at rest by default.
 * Keeping the secret out of it is what makes "metadata rides the config
 * document" safe at all.
 *
 * [id] is a stable identity that survives reordering, [label]/[note] are what
 * the user typed. Nothing reads these fields today; they exist so the
 * persisted blob keeps a typed, round-trip-tested shape until the column is
 * dropped properly.
 */
@Serializable
data class ProviderCredentialMeta(
    /** Stable uuid, minted on add and never reused. Not order-dependent. */
    val id: String = UUID.randomUUID().toString(),

    /**
     * User-supplied display name ("主号", "备用-悉尼", "室友的卡"). Blank is
     * allowed and the UI falls back to a positional label (`Key #2`); it is
     * NOT defaulted here so an explicit empty string round-trips as the user
     * left it rather than being rewritten to a generated name on every save.
     */
    var label: String = "",

    /**
     * Free-form remark — which account it belongs to, expiry date, why it was
     * parked. Purely informational; never parsed.
     */
    var note: String = "",

    /**
     * User manually disabled this credential without deleting it (keeping the
     * secret for later).
     */
    var isEnabled: Boolean = true,

    /**
     * When the credential was added. Ordering followed list position (which
     * the user controls), not this.
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Set when the credential was adopted from an instance's pre-existing
     * single `apikey_<instanceId>` slot by the old load-time backfill.
     */
    var migrated: Boolean = false,
) {
    /**
     * Display string for a credential with no user label. Positional so it is
     * stable within a render but obviously not a real name.
     */
    fun displayLabel(index: Int): String =
        label.ifBlank { "Key #${index + 1}" }
}
