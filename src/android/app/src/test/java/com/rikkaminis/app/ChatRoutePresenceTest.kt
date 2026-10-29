package com.rikkaminis.app

import com.rikkaminis.app.ui.navigation.LaunchSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-U F-01/F-02/F-09] Table-driven contract of the chat-route decisions.
 *
 * Three defects are pinned here:
 *
 *  - **F-01 draft keys reached the crash-recovery file.** `__new__<uuid>` is a
 *    composer draft key with no row behind it; a native-crash restore then
 *    synthesized `OpenSession("__new__…")` and opened a synthetic draft while
 *    the real conversation was elsewhere.
 *  - **F-02 the AUTO window was skipped on cold start.** The restore deep link
 *    short-circuits `AppNavigation`'s launch dispatcher (`hasDeepLink`), so
 *    AUTO's fresh-within-15-minutes rule never ran: a three-day-old record
 *    reopened the old chat instead of a new conversation.
 *  - **F-09 a dialog counted as leaving the chat.** `Routes.FILE_PREVIEW` is a
 *    `dialog(...)` destination pushed on top of the chat, but the back-stack
 *    listener treated the emission as "chat gone" and dropped keep-alive
 *    presence while the user was still sitting in the chat.
 */
class ChatRoutePresenceTest {

    @Test
    fun draftKeyIsNeverPersisted() {
        assertFalse(ChatRoutePresence.shouldPersistCrashRecovery(null))
        assertFalse(ChatRoutePresence.shouldPersistCrashRecovery(""))
        assertFalse(ChatRoutePresence.shouldPersistCrashRecovery("   "))
        // The real shape minted by ComposerDraftStore.nextDraftId().
        assertFalse(ChatRoutePresence.shouldPersistCrashRecovery(DRAFT))
        assertTrue(ChatRoutePresence.shouldPersistCrashRecovery(REAL))
    }

    @Test
    fun draftIdTable() {
        assertTrue(ChatRoutePresence.isDraftSessionId("__new__abc"))
        assertFalse(ChatRoutePresence.isDraftSessionId("__ne__abc"))
        assertFalse(ChatRoutePresence.isDraftSessionId("abc__new__"))
        assertFalse(ChatRoutePresence.isDraftSessionId(null))
    }

    @Test
    fun dialogNavigatorTable() {
        assertTrue(ChatRoutePresence.isChatKeptAliveOverlay("dialog"))
        // Fragment / activity destinations are real navigations away.
        assertFalse(ChatRoutePresence.isChatKeptAliveOverlay("androidx.navigation.fragment.NavHostFragment"))
        assertFalse(ChatRoutePresence.isChatKeptAliveOverlay("circular"))
        assertFalse(ChatRoutePresence.isChatKeptAliveOverlay(null))
    }

    private data class RestoreRow(
        val label: String,
        val launchMode: Int,
        val storedId: String?,
        val storedAtMs: Long,
        val nowMs: Long,
        val expectRestore: String?,
    )

    @Test
    fun coldStartRestoreTable() {
        val now = 1_000_000_000L
        val fresh = now - 60_000L                 // one minute ago
        val atWindowEdge = now - ChatRoutePresence.AUTO_RESUME_WINDOW_MS
        val justStale = atWindowEdge - 1L
        val threeDaysAgo = now - 3L * 24L * 60L * 60L * 1000L

        val rows = listOf(
            // NEW_CHAT / SAFE_START: the preference already refused the restore.
            RestoreRow("new chat never restores", LaunchSessionMode.NEW_CHAT, REAL, fresh, now, null),
            RestoreRow("safe start never restores", LaunchSessionMode.SAFE_START, REAL, fresh, now, null),
            // LAST_SESSION is unbounded: "always reopen the last chat".
            RestoreRow("last session restores a stale record", LaunchSessionMode.LAST_SESSION, REAL, threeDaysAgo, now, REAL),
            RestoreRow("last session restores without timestamp", LaunchSessionMode.LAST_SESSION, REAL, 0L, now, REAL),
            // AUTO honours the 15-minute window (the half that was missing).
            RestoreRow("auto restores a fresh record", LaunchSessionMode.AUTO, REAL, fresh, now, REAL),
            RestoreRow("auto keeps the record at the window edge", LaunchSessionMode.AUTO, REAL, atWindowEdge, now, REAL),
            RestoreRow("auto rejects one ms past the window", LaunchSessionMode.AUTO, REAL, justStale, now, null),
            RestoreRow("auto rejects a three-day-old record", LaunchSessionMode.AUTO, REAL, threeDaysAgo, now, null),
            // A record written before this change carries no timestamp at all.
            RestoreRow("auto rejects a record without timestamp", LaunchSessionMode.AUTO, REAL, 0L, now, null),
            // Draft keys are refused for every mode, including LAST_SESSION.
            RestoreRow("draft key never restores", LaunchSessionMode.LAST_SESSION, DRAFT, fresh, now, null),
            RestoreRow("blank record never restores", LaunchSessionMode.AUTO, "  ", fresh, now, null),
            RestoreRow("missing record never restores", LaunchSessionMode.AUTO, null, fresh, now, null),
            // Clock jumped backwards (manual change / reboot): keep the restore
            // rather than silently dropping it.
            RestoreRow("clock jumped back keeps the record", LaunchSessionMode.AUTO, REAL, now + 60_000L, now, REAL),
            // An unknown (future) mode value falls back to the dispatcher.
            RestoreRow("unknown mode defers", 99, REAL, fresh, now, null),
        )

        rows.forEach { r ->
            val actual = ChatRoutePresence.decideColdStartRestore(
                launchMode = r.launchMode,
                storedSessionId = r.storedId,
                storedAtMs = r.storedAtMs,
                nowMs = r.nowMs,
            )
            assertEquals(r.label, r.expectRestore, actual)
        }
    }

    @Test
    fun autoWindowLiteral() {
        // Pinned as a literal, not derived from the constant under test:
        // AppNavigation's dispatcher uses 15 minutes (a local `autoThresholdMs`
        // literal there). If that changes, this mirror must change with it and
        // this assertion is the reminder.
        assertEquals(900_000L, ChatRoutePresence.AUTO_RESUME_WINDOW_MS)
    }

    @Test
    fun draftPrefixIsTheComposerShape() {
        // ComposerDraftStore.nextDraftId() mints "__new__" + uuid; a silent
        // rename there would make restoring a draft-shaped id legal again.
        assertEquals("__new__", ChatRoutePresence.DRAFT_ID_PREFIX)
        assertNull(
            ChatRoutePresence.decideColdStartRestore(
                launchMode = LaunchSessionMode.AUTO,
                storedSessionId = ChatRoutePresence.DRAFT_ID_PREFIX + "x",
                storedAtMs = 1L,
                nowMs = 2L,
            ),
        )
    }

    private companion object {
        const val REAL = "0b0c1d2e-3f40-4516-8788-99aabbccddee"
        const val DRAFT = "__new__0b0c1d2e-3f40-4516-8788-99aabbccddee"
    }
}
