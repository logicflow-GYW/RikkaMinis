package com.rikkaminis.app.ui.settings

/**
 * [meta-audit-1028 D4] The write gates shared by the settings screens that (a) read a
 * secret asynchronously and (b) rewrite it on save, or (c) build a tens-of-megabytes
 * payload before they can apply it.
 *
 * All three screens are Compose-only (a real `Context`, a Keystore, an Activity for the
 * OAuth browser flow, a WebDAV client), so their defect class — "the write ran while the
 * read that was supposed to feed it had not landed yet" — cannot be reproduced by driving
 * the UI in a unit test. Keeping the DECISION here as a pure function is what makes the
 * truth table testable with literal expectations instead of source-shape assertions, and
 * the screen keeps only the assignment (see `SettingsWriteGatesTest`).
 */

/**
 * Load state of a form field whose content comes from an asynchronous read (Keystore →
 * `Dispatchers.IO`).
 *
 * The distinction that matters is not "did the read finish" but "can the field's content
 * be attributed to the user": [PENDING] means the box is still showing its initial value
 * (which is NOT the stored one), and [UNREADABLE] means the read failed, so the box
 * shows nothing while a secret exists on disk. Writing either of those back is how a
 * note-only edit used to erase the stored secret.
 */
internal enum class SecretLoadState {
    /** The read is in flight; the field's content is not the stored value. */
    PENDING,

    /** The field shows what is on disk (or the add-mode prefill, where nothing is). */
    LOADED,

    /** The read threw. Nothing can be written safely — fail closed, never "empty". */
    UNREADABLE,
}

/**
 * Save gate for a form whose save rewrites the stored secret.
 *
 * [otherFieldsValid] is the form's own validation (name/format/duplicates). Both other
 * states refuse on purpose: an unreadable secret is the case where "the box is empty"
 * and "there is no secret" look identical, and guessing wrong destroys a credential the
 * user never saw.
 */
internal fun canSaveWithSecretField(
    state: SecretLoadState,
    otherFieldsValid: Boolean,
): Boolean = state == SecretLoadState.LOADED && otherFieldsValid

/** What a save should do to the stored secret — including "nothing at all". */
internal enum class SecretWrite {
    /**
     * Do not touch the stored value. Reached when the secret was never loaded (so the
     * field's content cannot be attributed to the user) or when the user never edited
     * the field (so whatever is stored is still what they meant).
     */
    LEAVE_UNTOUCHED,

    /** Store the field's text (trimmed), replacing what was there. */
    SET,

    /** The user emptied a field they had edited: remove the stored secret. */
    CLEAR,
}

/**
 * The three-state write decision for the MCP OAuth client secret.
 *
 * `ifBlank { null }` at the call site was the defect: it collapsed "the box is empty
 * because you cleared it", "the box is empty because the read has not landed", and "the
 * box is empty because you never touched it" into one `null` — and `null` means REMOVE
 * in [com.rikkaminis.app.mcp.oauth.MCPOAuthStore.setClientSecret]. [oauthConfigured] is
 * the separate, older rule kept intact: a server whose saved config is not a configured
 * URL-transport OAuth server must have no credentials at all, so that branch clears
 * regardless of what the form holds (`[fix/audit0917-b8]`).
 */
internal fun decideClientSecretWrite(
    oauthConfigured: Boolean,
    state: SecretLoadState,
    editedByUser: Boolean,
    text: String,
): SecretWrite = when {
    !oauthConfigured -> SecretWrite.CLEAR
    state != SecretLoadState.LOADED -> SecretWrite.LEAVE_UNTOUCHED
    !editedByUser -> SecretWrite.LEAVE_UNTOUCHED
    text.isBlank() -> SecretWrite.CLEAR
    else -> SecretWrite.SET
}

/**
 * May a restore start right now?
 *
 * A restore is not one operation: WebDAV restores download a full payload and the local
 * snapshot/auto-backup rows `readText()` one, and only THEN call the shared
 * `restoreWithSnapshot` (which takes a fresh snapshot, i.e. builds a SECOND payload).
 * Every one of those windows allocates tens of megabytes on a 512 MB heap, so the
 * exclusion has to be claimed BEFORE the download/read — the old code only checked it
 * after, which is why two of them could be resident at once and why a download that
 * finished second was thrown away with `backup_err_busy`.
 *
 * [minisbakConverting] is included because the encrypted-package import path holds a
 * decrypted payload of the same size for the duration of the conversion.
 */
internal fun canStartRestorePayload(
    operationBusy: Boolean,
    minisbakConverting: Boolean,
): Boolean = !operationBusy && !minisbakConverting
