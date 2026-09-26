package com.rikkaminis.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * [T-multi-api-key] User-facing metadata for ONE credential of an instance.
 *
 * ## Why the secret is not a field here
 *
 * The secret lives ONLY in EncryptedSharedPreferences, keyed
 * `apikey_<instanceId>` (index 0, the historical slot) or
 * `apikey_<instanceId>_<index>`. This object is the part that is persisted in
 * the config document — the Room row, the JSON mirror, the backup and the
 * multi-device sync payload — and therefore the part that is *not* encrypted
 * at rest by default. Keeping the secret out of it is what makes "multiple
 * keys" and "keys ride the config document" compatible at all; see
 * [com.rikkaminis.app.data.repository.ProviderRepository.saveApiKeys].
 *
 * Two fields (`label`, `note`) are metadata the user types; `id` is the stable
 * identity that survives reordering, so a credential's health record and its
 * rotation position stay attached to the right secret even after the user
 * drags rows around.
 *
 * ## Why not reuse the raw index as identity
 *
 * Rotation state and circuit-breaker state are keyed by position
 * (`"<entryId>#<index>"`, see [com.rikkaminis.app.data.routing.GroupRouter.routeId]).
 * If identity were the *index alone*, deleting key #0 would silently shift
 * every later key one slot and hand key #1's cold-state to key #2 — a spent
 * credential would appear healthy again. [id] decouples the two: the index is
 * where the secret physically lives (it must be, to address the prefs slot),
 * while [id] is what the user and the diagnostics see.
 */
@Serializable
data class ProviderCredentialMeta(
    /**
     * Stable uuid, minted on add and never reused. Not order-dependent — see
     * the class doc for why index-based identity is unsafe under deletion.
     */
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
     * secret for later). A disabled credential is skipped by rotation exactly
     * like a spent one — but for an explicit reason the user controls, and it
     * must NOT be reported as "exhausted" in the health view.
     */
    var isEnabled: Boolean = true,

    /**
     * When the credential was added. Ordering in the UI follows list position
     * (which the user controls), not this; the timestamp exists so the
     * diagnostics view can say how long a key has been cooling.
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Adopted from an instance's pre-existing single `apikey_<instanceId>`
     * slot by [T-multi-api-key]'s load-time backfill. Marked so the UI can
     * explain where it came from ("imported from the previous single-key
     * field") and so a failed backfill is diagnosable rather than looking like
     * a key the user never created.
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
