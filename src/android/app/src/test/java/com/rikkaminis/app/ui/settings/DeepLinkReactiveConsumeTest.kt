package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.deeplink.DeepLinkCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1028-c] — deep-audit-1028 ISSUE-06.
 *
 * Both deep-link entry points were read ONCE, as a `.value` snapshot taken
 * inside a `LaunchedEffect(Unit)` (env vars) / `LaunchedEffect(sessionId)`
 * (chat quick action). Deep links are delivered while the app is already
 * resumed — the Activity is not recreated, the screen is already composed —
 * so the effect had already run with a null request and never re-ran. The
 * request sat in the coordinator forever and the user saw nothing happen
 * (`minis://action/camera_chat` never opened the camera; the
 * `?create_key=` prefill never opened the sheet).
 *
 * Fix shape: subscribe reactively (`collectAsState()`) and key the effect on
 * the observed value. This file pins both halves:
 *  - BEHAVIOUR: `DeepLinkCoordinator` is a pure Kotlin object, so its contract
 *    is run for real — a subscription already in place sees a request that
 *    arrives afterwards, and `consume` clears the flow so the keyed effect
 *    settles instead of re-firing forever.
 *  - WIRING: the two screens are Compose-only, so their half is pinned by
 *    source-shape assertions (the approach `SharedCacheExportPruneTest` uses).
 *
 * The state is global to the JVM (the coordinator is an `object`), so every
 * behavioural test restores it in a `finally`.
 */
class DeepLinkReactiveConsumeTest {

    // -- behaviour: the coordinator contract the fix relies on -------------

    @Test
    fun `a subscription already in place observes a request that arrives later`() = runBlocking {
        val seen = mutableListOf<DeepLinkCoordinator.ChatAction?>()
        val job = launch(Dispatchers.Unconfined) {
            DeepLinkCoordinator.pendingChatAction.collect { seen += it }
        }
        try {
            assertEquals(
                "a StateFlow replays its current value (null) to a new collector",
                1,
                seen.size,
            )
            DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
            assertEquals(
                "this is the whole defect: the request arrives AFTER the consumer is " +
                    "composed, so only a live subscription can see it",
                2,
                seen.size,
            )
            assertEquals(DeepLinkCoordinator.ChatAction.OPEN_CAMERA, seen[1])
        } finally {
            job.cancel()
            DeepLinkCoordinator.consumePendingChatAction()
        }
    }

    @Test
    fun `consuming publishes null so a keyed effect re-runs exactly once`() {
        try {
            DeepLinkCoordinator.consumePendingChatAction()
            DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
            assertEquals(
                DeepLinkCoordinator.ChatAction.OPEN_CAMERA,
                DeepLinkCoordinator.consumePendingChatAction(),
            )
            assertNull(
                "consume must clear the flow: with `LaunchedEffect(pendingChatActionState)` a " +
                    "value left behind would re-trigger the camera on every recomposition",
                DeepLinkCoordinator.pendingChatAction.value,
            )
            assertNull("and it is consumed exactly once", DeepLinkCoordinator.consumePendingChatAction())
        } finally {
            DeepLinkCoordinator.consumePendingChatAction()
        }
    }

    @Test
    fun `the env-var create request carries the prefill and clears on consume`() {
        try {
            DeepLinkCoordinator.consumePendingEnvVarCreate()
            assertNull(DeepLinkCoordinator.pendingEnvVarCreate.value)
            DeepLinkCoordinator.setPendingEnvVarCreate("DA1028_TEST_KEY", "test-value", "note")
            assertEquals(
                DeepLinkCoordinator.EnvVarCreate("DA1028_TEST_KEY", "test-value", "note"),
                DeepLinkCoordinator.pendingEnvVarCreate.value,
            )
            assertEquals(
                DeepLinkCoordinator.EnvVarCreate("DA1028_TEST_KEY", "test-value", "note"),
                DeepLinkCoordinator.consumePendingEnvVarCreate(),
            )
            assertNull(DeepLinkCoordinator.pendingEnvVarCreate.value)
        } finally {
            DeepLinkCoordinator.consumePendingEnvVarCreate()
        }
    }

    // -- wiring: the screens must observe instead of snapshotting ----------

    private val envScreen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/EnvironmentVariablesScreen.kt")
    }
    private val chatScreen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt")
    }

    @Test
    fun `the form sheet keys every field on what it was seeded from`() {
        val src = codeOnly(envScreen)
        for (field in listOf("keyText", "valueText", "noteText", "groupText")) {
            val m = Regex("""var\s+$field\s+by\s+remember\s*\(([^)]*)\)""").find(src)
            assertTrue(
                "`var $field by remember(...)` not found — keyless or renamed: a keyless " +
                    "shadow keeps the FIRST entry's text after editEntry switches identity, " +
                    "and Save then writes that stale text back over an external write",
                m != null,
            )
            assertTrue(
                "`var $field by remember()` carries no keys",
                m!!.groupValues[1].trim().isNotEmpty(),
            )
        }
        assertTrue(
            "valueText must key on the LIVE secret rather than only the entry, otherwise a " +
                "value-only external write is invisible and the box shows the old secret",
            src.contains("remember(editEntry?.id, currentSecretValue, prefillValue)"),
        )
    }

    @Test
    fun `the env-var screen subscribes to the pending create request`() {
        val src = codeOnly(envScreen)
        assertTrue(
            "pendingEnvVarCreate must be read through collectAsState()",
            Regex("""pendingEnvVarCreate\s*\.\s*collectAsState\s*\(""").containsMatchIn(src),
        )
        assertTrue(
            "the effect must key on the observed value, otherwise it still only runs once",
            Regex("""LaunchedEffect\s*\(\s*pendingCreate\s*\)""").containsMatchIn(src),
        )
        falseAlarmFor("a one-shot consume is unreachable", src, "LaunchedEffect(Unit)")
    }

    // -- [da1034-B4] the edit sheet must close when its target disappears ----

    @Test
    fun `the edit sheet closes when its target disappears`() {
        val src = codeOnly(envScreen)
        assertTrue(
            "the sheet is opened for an id and its target is re-resolved from the live " +
                "list, so an entry deleted while the sheet is up (minis-config remove / " +
                "backup restore) leaves editEntryId non-null over a null lookup — the form " +
                "silently flips to new-variable semantics and Save creates a phantom entry. " +
                "The close effect must be keyed on (editEntryId, entries): keying on the id " +
                "alone would not re-run when the list loses the entry",
            Regex("""LaunchedEffect\s*\(\s*editEntryId\s*,\s*entries\s*\)""")
                .containsMatchIn(src),
        )
        assertTrue(
            "the close must be decided by the live lookup (editTargetVanished), not taken " +
                "unconditionally — an unconditional close would dismiss the sheet on every " +
                "recomposition",
            Regex("""editTargetVanished\s*\(\s*entries\s*,\s*editEntryId\s*\)""")
                .containsMatchIn(src),
        )
        assertTrue(
            "the deep-link prefill effect must stay keyless-independent of the close effect",
            Regex("""LaunchedEffect\s*\(\s*pendingCreate\s*\)""").containsMatchIn(src),
        )
    }

    @Test
    fun `the chat screen subscribes to the pending quick action and keeps the session guard`() {
        val src = codeOnly(chatScreen)
        assertTrue(
            "pendingChatAction must be read through collectAsState()",
            Regex("""pendingChatAction\s*\.\s*collectAsState\s*\(""").containsMatchIn(src),
        )
        assertFalse(
            "a `.value` snapshot read is the defect this batch fixes",
            Regex("""pendingChatAction\s*\.\s*value\b""").containsMatchIn(src),
        )
        assertTrue(
            "the effect must keep `sessionId` in its keys — that is what keeps the action " +
                "scoped to the session the user actually opened",
            Regex("""LaunchedEffect\s*\(\s*sessionId\s*,\s*pendingChatActionState\s*\)""")
                .containsMatchIn(src),
        )
        assertTrue(
            "pendingHtmlPreview (the neighbouring consumer, already reactive) must not be " +
                "'simplified' back to a snapshot",
            Regex("""pendingHtmlPreview\w*\s*\.\s*collectAsState\s*\(""").containsMatchIn(src),
        )
    }

    // -- helpers --

    private fun falseAlarmFor(why: String, src: String, needle: String) {
        val idx = src.indexOf(needle)
        if (idx < 0) return
        // The only `LaunchedEffect(Unit)` this screen may keep is one that does
        // NOT consume the deep-link request; anything else is the old shape.
        val block = src.substring(idx, minOf(src.length, idx + 400))
        assertFalse(why, block.contains("consumePendingEnvVarCreate("))
    }

    private fun codeOnly(src: String): String = src
        .split("\n")
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
