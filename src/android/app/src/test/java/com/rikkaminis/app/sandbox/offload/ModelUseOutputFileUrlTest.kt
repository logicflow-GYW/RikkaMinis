package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [meta1028-D2 F-02] `minis-model-use run --output file:///…`.
 *
 * The defect: `cmdRun` passed a `file://` value through UNSTRIPPED ("for
 * downstream stripping" — no such stripping existed anywhere below it). Every
 * consumer of that string has PATH semantics:
 *  - `PRootKernel.isLinuxPathUnderReadOnlyMount` normalizes
 *    `file:///var/minis/mounts/ro/x` to the RELATIVE `file:/var/minis/mounts/ro/x`,
 *    which never matches the `/var/minis/mounts/` prefix ⇒ the read-only guard
 *    passed;
 *  - `sessionAwareHostFile`'s regex matches `/var/minis/(attachments|offloads|
 *    workspace|browser)` on the same relative form ⇒ session scoping lost;
 *  - the rootfs fallback then really wrote `<rootfs>/file:/var/minis/workspace/out.png`
 *    and answered `output_file: file:///var/minis/workspace/out.png`.
 * Net: the user's file was nowhere, the bytes landed in the rootfs shared by
 * every session, and a read-only mount became writable.
 *
 * Percent-decoding is deliberately NOT part of the fix — foreign input, no
 * in-repo constructor, and decoding corrupts files literally named `%XX`
 * (FIX-FILEURL-CONTRACT §7-R2). The arms below pin exactly that boundary.
 */
class ModelUseOutputFileUrlTest {

    // ── the pure strip ───────────────────────────────────────────────────

    @Test
    fun `the three-slash URL form becomes the absolute guest path`() {
        assertEquals(
            "/var/minis/workspace/out.png",
            stripFileUrlScheme("file:///var/minis/workspace/out.png"),
        )
        assertEquals(
            "/var/minis/mounts/ro_vault/out.txt",
            stripFileUrlScheme("file:///var/minis/mounts/ro_vault/out.txt"),
        )
    }

    @Test
    fun `plain paths are returned untouched — the strip is idempotent`() {
        for (path in listOf(
            "/var/minis/workspace/out.png",
            "out.png",
            "./out.png",
            "/var/minis/workspace/file://odd.png",
            "file:/var/minis/workspace/out.png",
        )) {
            assertEquals(path, stripFileUrlScheme(path))
        }
    }

    @Test
    fun `a relative path after the scheme is still resolved against the shell cwd`() {
        // Same single join implementation the handler uses.
        assertEquals(
            "/var/minis/workspace/out.png",
            resolveMaybeRelative(stripFileUrlScheme("file://out.png"), "/var/minis/workspace"),
        )
        assertEquals(
            "/var/minis/workspace/out.png",
            resolveMaybeRelative(stripFileUrlScheme("file:///var/minis/workspace/out.png"), "/tmp"),
        )
    }

    @Test
    fun `percent escapes are NOT decoded — a literal XX name survives`() {
        // FIX-FILEURL-CONTRACT §7-R2: `--input/--output` values are foreign
        // input with no in-repo `file://` constructor. Decoding here would
        // rewrite a file that is really named `50%23.png`.
        assertEquals(
            "/var/minis/workspace/50%23.png",
            stripFileUrlScheme("file:///var/minis/workspace/50%23.png"),
        )
    }

    @Test
    fun `the scheme alone leaves nothing to resolve and fails the absolute check`() {
        assertEquals("", stripFileUrlScheme("file://"))
        assertFalse(stripFileUrlScheme("file://").startsWith("/"))
    }

    // ── wiring (source shape) ────────────────────────────────────────────

    private val handlerRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt"

    @Test
    fun `cmdRun strips the scheme before resolving instead of passing it through`() {
        val body = memberSlice(readRepoFile(handlerRel), "private fun cmdRun(")
        assertTrue(
            "the output path must be stripped before the cwd join",
            body.contains("resolveMaybeRelative(stripFileUrlScheme(raw), request.cwd)"),
        )
        assertFalse(
            "the pass-through that skipped both the join and the downstream guards must be gone",
            body.contains("if (raw.startsWith(\"file://\")) raw"),
        )
        // The path-semantics consumers are downstream of this value; the shape
        // that made them see `file:/…` must not survive anywhere in the file.
        assertFalse(
            "no site may hand a `file://` string to the path-semantics consumers",
            readRepoFile(handlerRel).contains("startsWith(\"file://\")) raw"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

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
}
