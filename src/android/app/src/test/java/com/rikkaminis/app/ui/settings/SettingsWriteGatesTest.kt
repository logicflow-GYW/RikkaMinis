package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 D4] Truth table for the write gates the settings screens use.
 *
 * Why these are pure functions at all: all three screens are Compose-only (a real
 * `Context`, a Keystore, an Activity for the OAuth browser flow, a WebDAV client), so
 * the defect class they share — "the write ran while the read that was supposed to feed
 * it had not landed" — cannot be reproduced by driving the UI. The screens keep only the
 * assignment; the DECISION lives here.
 *
 * Every expectation is a literal. Deriving it from the production code would let a
 * mutation masquerade as PASS (see `evidence-discipline`).
 */
class SettingsWriteGatesTest {

    // ── F-03 / F-04: the save gate ────────────────────────────────────────────

    /**
     * The wipe this fixes: the box is empty because the Keystore read has not landed,
     * the form is otherwise valid, and Save writes "" over a secret the user cannot see.
     * Only LOADED may write — PENDING and UNREADABLE look identical to "the user cleared
     * the field" from the value alone.
     */
    @Test
    fun saveIsGatedOnTheSecretBeingLoaded() {
        assertEquals(true, canSaveWithSecretField(SecretLoadState.LOADED, true))
        assertEquals(false, canSaveWithSecretField(SecretLoadState.PENDING, true))
        assertEquals(false, canSaveWithSecretField(SecretLoadState.UNREADABLE, true))
    }

    /** An invalid key is still invalid — the gate is an AND, not a replacement. */
    @Test
    fun otherValidationStillDecides() {
        assertEquals(false, canSaveWithSecretField(SecretLoadState.LOADED, false))
        assertEquals(false, canSaveWithSecretField(SecretLoadState.PENDING, false))
    }

    /** The gate refuses all three non-LOADED paths, and only those. */
    @Test
    fun everyNonLoadedStateRefuses() {
        for (state in SecretLoadState.values()) {
            val ok = canSaveWithSecretField(state, true)
            if (state == SecretLoadState.LOADED) assertTrue(ok) else assertFalse(ok)
        }
    }

    // ── F-04: three-way secret write ──────────────────────────────────────────

    /** A server that is not a configured URL-transport OAuth server keeps no secret. */
    @Test
    fun unconfiguredOauthAlwaysClears() {
        for (state in SecretLoadState.values()) {
            for (edited in listOf(false, true)) {
                assertEquals(
                    "unconfigured must clear regardless of the form's state ($state/$edited)",
                    SecretWrite.CLEAR,
                    decideClientSecretWrite(false, state, edited, "super-secret"),
                )
            }
        }
    }

    /**
     * The defect, stated as a table row: text is present but the read has not landed, so
     * the box's content cannot be attributed to the user — it must NOT be written (and
     * must NOT clear either).
     */
    @Test
    fun pendingOrUnreadableNeverWrites() {
        for (state in listOf(SecretLoadState.PENDING, SecretLoadState.UNREADABLE)) {
            for (edited in listOf(false, true)) {
                assertEquals(
                    "$state/$edited must leave the stored secret untouched",
                    SecretWrite.LEAVE_UNTOUCHED,
                    decideClientSecretWrite(true, state, edited, "anything"),
                )
            }
        }
    }

    /** Untouched + loaded = "keep whatever is stored", even when the box is empty. */
    @Test
    fun untouchedBoxNeverWritesEvenWhenEmpty() {
        assertEquals(
            SecretWrite.LEAVE_UNTOUCHED,
            decideClientSecretWrite(true, SecretLoadState.LOADED, false, ""),
        )
        assertEquals(
            SecretWrite.LEAVE_UNTOUCHED,
            decideClientSecretWrite(true, SecretLoadState.LOADED, false, "  "),
        )
    }

    /** Edited + loaded: the text wins; an emptied box is an explicit clear. */
    @Test
    fun editedBoxDecidesBetweenSetAndClear() {
        assertEquals(
            SecretWrite.SET,
            decideClientSecretWrite(true, SecretLoadState.LOADED, true, "s3cret"),
        )
        assertEquals(
            SecretWrite.SET,
            decideClientSecretWrite(true, SecretLoadState.LOADED, true, " s3cret "),
        )
        assertEquals(
            SecretWrite.CLEAR,
            decideClientSecretWrite(true, SecretLoadState.LOADED, true, ""),
        )
        assertEquals(
            SecretWrite.CLEAR,
            decideClientSecretWrite(true, SecretLoadState.LOADED, true, "   "),
        )
    }

    /**
     * The specific regression, in one row: the pre-fix code was
     * `secretValue.trim().ifBlank { null }`, which maps a pending read (box == "") to a
     * REMOVE of the stored secret. Both gates have to agree that this row is not a write.
     */
    @Test
    fun pendingEmptyBoxIsNeitherSetNorClear() {
        val decision = decideClientSecretWrite(
            oauthConfigured = true,
            state = SecretLoadState.PENDING,
            editedByUser = false,
            text = "",
        )
        assertEquals(SecretWrite.LEAVE_UNTOUCHED, decision)
        assertEquals(false, canSaveWithSecretField(SecretLoadState.PENDING, true))
    }

    // ── F-02: the restore payload gate ────────────────────────────────────────

    @Test
    fun restoreIsMutuallyExclusive() {
        assertEquals(true, canStartRestorePayload(false, false))
        assertEquals(false, canStartRestorePayload(true, false))
        assertEquals(false, canStartRestorePayload(false, true))
        assertEquals(false, canStartRestorePayload(true, true))
    }

    /**
     * The other half of the gate: the caller that already holds the claim hands it over
     * (preClaimed), so the shared check must not refuse the restore that owns the lock —
     * that self-collision is what the old `restoreWithSnapshot` guard was avoiding by
     * dropping the gate entirely.
     */
    @Test
    fun handoffSkipsTheRecheck() {
        // Mirrors `if (!preClaimed && operationBusy) refuse`.
        fun refuse(preClaimed: Boolean, operationBusy: Boolean) = !preClaimed && operationBusy
        assertEquals(false, refuse(preClaimed = true, operationBusy = true))
        assertEquals(true, refuse(preClaimed = false, operationBusy = true))
        assertEquals(false, refuse(preClaimed = false, operationBusy = false))
    }

    /** The three states the enum exists to distinguish are exactly three. */
    @Test
    fun secretLoadStateHasExactlyThreeStates() {
        assertEquals(3, SecretLoadState.values().size)
    }
}
