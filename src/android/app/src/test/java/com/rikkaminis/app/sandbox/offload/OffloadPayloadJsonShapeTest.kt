package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1034 FIX-A4 ISSUE-08] Payload shapes of the six handlers the
 * N1/N2 gate rules grew to cover.
 *
 * Defect shape being pinned: exit-0 data payloads that were plain English
 * sentences passed through `OffloadOutput.formatBody` untouched, so a
 * `jq` / `json.loads` reader broke on success — while sibling subcommands in
 * the same file already answered with JSON. `formatBody` is a pass-through, so
 * "did the handler hand it JSON?" is only observable in the source: these
 * assertions read the exact declarations that changed.
 *
 * Comments are stripped before every "the old text is gone" assertion on
 * purpose — the new code explains IN a comment which sentence it replaced, and
 * a rule that judges prose by spelling would go red on its own explanation.
 * (Same lesson the calendar gate learned in this batch.)
 *
 * Repo root comes from -Dprobe.repo when set, else it walks up from user.dir.
 */
class OffloadPayloadJsonShapeTest {

    private val offload = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload"

    // ── Location: one shape for both geocode outcomes ─────────────────────

    @Test
    fun `reverse geocode answers the same shape whether or not it found lines`() {
        val src = codeOnly(read("LocationOffloadHandler.kt"))
        val geocode = memberSlice(src, "private fun geocode(")
        assertTrue(
            "the empty and the populated outcome must both be built by the shared payload helper",
            geocode.contains("reverseGeocodeJson("),
        )
        assertFalse(
            "the 'No address found for (...)' sentence cannot come back",
            geocode.contains("No address found for ("),
        )
        val helper = memberSlice(src, "private fun reverseGeocodeJson(")
        for (key in listOf("\"latitude\"", "\"longitude\"", "\"address\"", "\"address_lines\"", "\"count\"")) {
            assertTrue("the payload must carry $key", helper.contains(key))
        }
    }

    @Test
    fun `the uncaught branch reports a JSON error`() {
        val src = codeOnly(read("LocationOffloadHandler.kt"))
        assertTrue("the failure payload must be structured", src.contains("\"error\", \"uncaught\""))
        assertFalse(
            "the bare 'android-location: <msg>' sentence cannot come back",
            Regex("""formatBody\(\s*"android-location:""").containsMatchIn(src),
        )
    }

    // ── the short acknowledgements ────────────────────────────────────────

    @Test
    fun `notification clear reports JSON and the sentence is gone`() {
        val src = codeOnly(read("NotificationOffloadHandler.kt"))
        assertTrue("clear must answer with an envelope", src.contains("JSONObject().put(\"cleared\", \"all\")"))
        assertFalse("'All notifications cleared.' cannot come back", src.contains("All notifications cleared."))
    }

    @Test
    fun `clipboard set and clear report JSON and the sentences are gone`() {
        val src = codeOnly(read("ClipboardOffloadHandler.kt"))
        assertTrue("set must report what it copied", src.contains(".put(\"copied\", true)"))
        assertTrue("clear must answer with an envelope", src.contains(".put(\"cleared\", true)"))
        assertFalse("'Copied N characters' cannot come back", Regex("""Copied \$\{""").containsMatchIn(src))
        assertFalse("'Clipboard cleared.' cannot come back", src.contains("Clipboard cleared."))
    }

    @Test
    fun `open reports JSON and the Opened sentence is gone`() {
        val src = codeOnly(read("OpenOffloadHandler.kt"))
        assertTrue("success must report the url it opened", src.contains(".put(\"opened\", true).put(\"url\", url)"))
        assertFalse("'Opened: <url>' cannot come back", Regex("""Opened: \$""").containsMatchIn(src))
    }

    // ── fastio usage: JSON envelope, HELP still reachable ─────────────────

    @Test
    fun `fastio usage hands formatBody JSON and keeps HELP reachable`() {
        val src = codeOnly(read("FastioOffloadHandler.kt"))
        val usage = memberSlice(src, "private fun usage(")
        assertTrue("reason must be carried as a field", usage.contains(".put(\"message\", reason)"))
        assertTrue("HELP must stay reachable through the envelope", usage.contains(".put(\"help\", HELP)"))
        assertFalse(
            "the 'minis-fastio: <reason>\\n<HELP>' prose payload cannot come back",
            usage.contains("formatBody(\"minis-fastio:"),
        )
        assertTrue("HELP itself is untouched", read("FastioOffloadHandler.kt").contains("private val HELP = \"\"\""))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Comments out, so an assertion never judges the explanation.
     *
     * Hand-written linear scan on purpose: the regex form (a line-comment
     * alternative plus a lazy block-comment match) recurses once per character
     * of a block comment, so on the 64 KB FastioOffloadHandler it blew the JVM
     * stack (StackOverflowError in Pattern$LazyLoop) — measured on CI, where
     * the test JVM's stack is smaller than the one this fix was written on.
     */
    private fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i = if (i + 2 > src.length) src.length else i + 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun read(name: String): String {
        val f = File(repoRoot(), "$offload/$name")
        if (!f.exists()) throw FileNotFoundException(f.path)
        return f.readText()
    }

    private fun repoRoot(): File {
        System.getProperty("probe.repo")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, "$offload/FastioOffloadHandler.kt").exists()) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Source lines of the class member at [marker], up to the next member. */
    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { idx ->
            val t = lines[idx]
            val indent = t.length - t.trimStart(' ').length
            indent in 1..4 && t.trim().isNotEmpty() && !t.trim().startsWith("//") &&
                !t.trim().startsWith("*") &&
                Regex("""^(private|internal|public|protected|fun|val|var|@)""").containsMatchIn(t.trim())
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }
}
