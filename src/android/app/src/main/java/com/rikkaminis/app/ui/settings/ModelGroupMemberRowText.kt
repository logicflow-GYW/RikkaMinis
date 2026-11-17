package com.rikkaminis.app.ui.settings

/**
 * The secondary line under one member row of Settings → Model Group detail.
 *
 * Why it exists: the member list is a DRAG-REORDER surface — the order of the
 * models inside a group is decided right there — but each row named only the
 * provider, so two members with similar display names (or two ids under the
 * same provider) were indistinguishable while you were reordering them. The
 * chat model picker already shows `provider · model id` under every member;
 * this brings the same fact to the screen where the order is set.
 *
 * Deliberately Android-free (String only): the interesting part is what the
 * line says when a piece is missing, and that must be pinnable without a device.
 * Callers pass the already-localized provider label in
 * ([SystemVoiceMemberPresentation.memberSubtitle] is the single source for
 * "a System member's provider is the device"), so no language key is added
 * here.
 */
internal object ModelGroupMemberRowText {

    /** Same separator the pickers use, so both surfaces read alike. */
    const val SEPARATOR: String = " · "

    /**
     * `provider · model id`, degrading to whichever half exists.
     *
     * - [providerLabel] blank → just the id. This is the stale-persisted shape:
     *   the provider instance was deleted, so the row has no provider name —
     *   showing a dangling " · id" would look like a rendering bug.
     * - [modelId] blank → just the provider (nothing better to say).
     * - both blank → empty string; the caller renders no line.
     *
     * No de-duplication: `model.id` is the exact identifier the user is
     * comparing, and hiding it because it happens to equal the display name
     * would remove the fact this line exists to expose.
     */
    fun secondaryLine(providerLabel: String?, modelId: String): String {
        val provider = providerLabel?.trim().orEmpty()
        val id = modelId.trim()
        return when {
            id.isEmpty() -> provider
            provider.isEmpty() -> id
            else -> provider + SEPARATOR + id
        }
    }
}
