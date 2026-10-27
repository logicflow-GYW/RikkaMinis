package com.rikkaminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [da1032-A] ISSUE-02 — regression net for the `SessionEditSheet` wiring.
 *
 * Two halves of one defect:
 *  1. `ChatScreen` is the only call site of `SessionEditSheet`, and
 *     `onRegenerate` / `liveSession` are *defaulted* parameters — omitting them
 *     compiles clean, leaves the Regenerate button on its `{}` default (dead
 *     button) and freezes the edit fields on the snapshot taken when the sheet
 *     opened. Both arguments must be passed explicitly.
 *  2. `SessionEditSheet`'s seed state was unkeyed, so re-pointing the sheet at
 *     another row kept the previous session's text, and the live-row effect
 *     could not re-seed across rows.
 *
 * Layer: source-shape assertions + one "must not change" anchor (no Compose
 * runtime in the sandbox/CI unit-test JVM). Expectations are hard-coded
 * literals, never derived from the file under test.
 */
class SessionEditSheetBindingTest {

    private val chatScreen: String = readRepoFile(CHAT_SCREEN_REL)
    private val sessionsShared: String = readRepoFile(SESSIONS_SHARED_REL)

    @Test
    fun `the edit sheet call site passes both defaulted arguments`() {
        val block = requireNotNull(callBlock(chatScreen, "SessionEditSheet")) {
            "no SessionEditSheet( call block found in ChatScreen.kt"
        }
        assertTrue(
            "onRegenerate must be passed explicitly — the default is a no-op",
            block.contains("onRegenerate ="),
        )
        assertTrue(
            "liveSession must be passed explicitly — the default is the stale snapshot",
            block.contains("liveSession ="),
        )
    }

    @Test
    fun `the live row comes from the repository flow, scoped to the sheet`() {
        val host = requireNotNull(braceBlockAfter(chatScreen, "editingSession?.let")) {
            "no `editingSession?.let` host block found in ChatScreen.kt"
        }
        assertTrue(
            "the live row must be read from a real repository flow",
            host.contains("chatRepository.observeSessions()"),
        )
        assertTrue(
            "the flow must be resolved down to this sheet's row, with the snapshot as fallback",
            host.contains("it.id == session.id"),
        )
        assertTrue(
            "the flow must be remembered (stable identity across this screen's many recompositions)",
            host.contains("remember { chatRepository.observeSessions() }"),
        )
        assertTrue(
            "the observation must live in the let block, not at ChatScreen scope",
            host.contains("collectAsState"),
        )
    }

    @Test
    fun `regenerate entry point still has exactly two call sites`() {
        // "must not change": the drawer entry and the sheet entry. A third one
        // would mean a second, non-single-source way to trigger regeneration.
        assertEquals(
            2,
            countOccurrences(chatScreen, "viewModel.regenerateSessionTitle()"),
        )
    }

    @Test
    fun `edit sheet seeds are keyed on the row id`() {
        // Scope: from the SessionEditSheet signature to the end of the file
        // (it is the last declaration). Other composables in this file are out
        // of scope for the *gate*; this test asserts the file-level absence of
        // the keyless forms, which is the stricter direction.
        val body = sessionsShared.substringAfter("internal fun SessionEditSheet(")
        assertTrue(
            "title must re-seed when the sheet is re-pointed",
            body.contains("var title by remember(session.id)"),
        )
        assertTrue(
            "selectedCategory must re-seed when the sheet is re-pointed",
            body.contains("var selectedCategory by remember(session.id)"),
        )
        assertFalse(
            "keyless title seed must not come back",
            body.contains("var title by remember {"),
        )
        assertFalse(
            "keyless category seed must not come back",
            body.contains("var selectedCategory by remember {"),
        )
        assertTrue(
            "the live-row seeding effect must re-run when the row changes",
            body.contains("LaunchedEffect(liveSession.id,"),
        )
    }

    @Test
    fun `the sheet still offers the regenerate control`() {
        // "must not change": the button that motivates the wiring is still
        // rendered and still disabled while a run is in flight.
        val body = sessionsShared.substringAfter("internal fun SessionEditSheet(")
        assertTrue(body.contains("onClick = onRegenerate"))
        assertTrue(body.contains("enabled = !isRegenerating"))
    }

    // --- helpers -----------------------------------------------------------

    /** Text of the `name(...)` call whose parens balance, or null. */
    private fun callBlock(text: String, name: String): String? {
        val start = text.indexOf("$name(")
        if (start < 0) return null
        val open = start + name.length
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /** Text of the `{ … }` block that follows [marker], or null. */
    private fun braceBlockAfter(text: String, marker: String): String? {
        val at = text.indexOf(marker)
        if (at < 0) return null
        val open = text.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        return null
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run
     * can start anywhere under the repo — no fixed depth works.
     */
    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val CHAT_SCREEN_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"
        const val SESSIONS_SHARED_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sessions/SessionsShared.kt"
    }
}
