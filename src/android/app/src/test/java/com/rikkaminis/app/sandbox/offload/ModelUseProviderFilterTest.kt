package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv16-A04] — `minis-model-use --provider` used to be answered three
 * different ways: `list` matched model.provider/label as a case-sensitive
 * substring, `search` never read the option despite its documented help
 * line, and `run` (resolveEntry) matched label/id exactly. An agent reading
 * `list`'s JSON and replaying its "provider" field into `run` could hit
 * "model not found"; `search --provider x` returned other providers' models.
 *
 * Layer split (why this file does not drive the handler end to end):
 *  - `cmdList`/`cmdSearch`/`resolveEntry` are bound to
 *    [com.rikkaminis.app.data.repository.ProviderRepository] (Context-backed
 *    state), so a JVM run would prove nothing about the real CLI.
 *  - So CI pins: (a) the single matcher [providerMatches] (pure, top-level,
 *    zero Android deps), and (b) the *source* wiring that all three call
 *    sites go through it (same approach DebugOffloadHandlerTest uses for
 *    Context-bound call sites).
 *
 * Expectations below are literals, never derived from the code under test.
 */
class ModelUseProviderFilterTest {

    // ── part 1: the single matcher (exact, case-insensitive, trimmed) ───────

    @Test
    fun `exact match against the instance label is case-insensitive`() {
        assertTrue(providerMatches("OpenAI", "OpenAI (main)", "uuid-1", "OpenAI"))
        assertTrue(providerMatches("openai", "OpenAI (main)", "uuid-1", "OpenAI"))
        assertTrue(providerMatches("  OPENAI  ", "openai (main)", "uuid-1", "OpenAI"))
    }

    @Test
    fun `exact match against the instance id`() {
        assertTrue(providerMatches("uuid-1", "OpenAI (main)", "uuid-1", "OpenAI"))
        assertTrue(providerMatches("UUID-1", "OpenAI (main)", "uuid-1", "OpenAI"))
    }

    @Test
    fun `exact match against the provider type display name`() {
        // "OpenAI" is what `list`/`search` print as their JSON "provider"
        // field for an OpenAI-type instance — the read-then-replay workflow.
        assertTrue(providerMatches("OpenAI", "My Relay", "uuid-1", "OpenAI"))
        assertTrue(providerMatches("Anthropic", "Claude Relay", "uuid-2", "Anthropic"))
        // Google Gemini: the type display name, not the bare "Google".
        assertFalse(providerMatches("Google", "Claude Relay", "uuid-2", "Google Gemini"))
        assertTrue(providerMatches("Google Gemini", "Claude Relay", "uuid-2", "Google Gemini"))
    }

    @Test
    fun `a substring is not enough (the old list coincidence is gone)`() {
        // The pre-fix cmdList matched `contains("open")` here.
        assertFalse(providerMatches("open", "OpenAI (main)", "uuid-1", "OpenAI"))
        assertFalse(providerMatches("rel", "OpenAI (main)", "uuid-1", "OpenAI"))
        assertFalse(providerMatches("uuid", "OpenAI (main)", "uuid-1", "OpenAI"))
    }

    @Test
    fun `a mismatch and an empty query are non-matches`() {
        assertFalse(providerMatches("groq", "OpenAI (main)", "uuid-1", "OpenAI"))
        assertFalse(providerMatches("", "OpenAI (main)", "uuid-1", "OpenAI"))
        assertFalse(providerMatches("   ", "OpenAI (main)", "uuid-1", "OpenAI"))
    }

    // ── part 2: wiring this test cannot execute (repo-bound) ────────────────

    @Test
    fun `all three subcommands go through the single matcher`() {
        val src = readRepoFile(HANDLER_REL)
        for (marker in listOf("private fun cmdList(", "private fun cmdSearch(", "private fun resolveEntry(")) {
            val body = memberSlice(src, marker)
            assertTrue("$marker must call the single providerMatches(", body.contains("providerMatches("))
        }
    }

    @Test
    fun `cmdSearch reads the provider flag it documents`() {
        val body = memberSlice(readRepoFile(HANDLER_REL), "private fun cmdSearch(")
        assertTrue(
            "cmdSearch must read args.get(\"provider\") — it was documented and never read",
            body.contains("args.get(\"provider\")"),
        )
        assertTrue(
            "cmdSearch must gate the filter on the provider flag being present",
            body.contains("if (providerFilter != null)"),
        )
    }

    @Test
    fun `cmdList no longer substring-matches the model provider field`() {
        val body = memberSlice(readRepoFile(HANDLER_REL), "private fun cmdList(")
        assertFalse(
            "the case-sensitive substring match on model.provider is the pre-fix coincidence",
            body.contains("it.model.provider.lowercase().contains("),
        )
        assertTrue(
            "cmdList must resolve the instance and match label/id/typeName",
            body.contains("providerRepository.instance(entry.providerInstanceId)"),
        )
    }

    @Test
    fun `resolveEntry keeps its selection order after the provider filter`() {
        val body = memberSlice(readRepoFile(HANDLER_REL), "private fun resolveEntry(")
        // The pool filter changed, but the candidate order is unchanged:
        // qualified label/model first, then exact id, display name, uuid,
        // then case-insensitive prefix / contains.
        for (needle in listOf(
            "Qualified", // step 0 comment
            "pool.find { it.model.id == idOrName }",
            "pool.find { it.model.displayName == idOrName }",
            "pool.find { it.id == idOrName }",
            "it.model.id.lowercase().startsWith(q)",
            "it.model.displayName.lowercase().contains(q)",
        )) {
            assertTrue("missing unchanged selection step: $needle", body.contains(needle))
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Lines of the member declared at [marker], up to the next sibling member. */
    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val line = lines[i]
            val indent = line.takeWhile { it == ' ' }.length
            indent <= 4 && line.isNotBlank() && !line.trimStart().startsWith("//") &&
                !line.trimStart().startsWith("*") &&
                Regex("^(private|internal|public|protected|fun|val|var|@)").containsMatchIn(line.trimStart())
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        const val HANDLER_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt"
    }
}
