package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [da1031-C] ISSUE-04 B2 — `minis-debug ls|read|write` route their positional
 * through the same relative→absolute join every sibling handler uses, instead
 * of handing a relative token to DebugRPCHandler (which joins onto the rootfs
 * ROOT, not the session cwd).
 *
 * Layer split (why this file does not drive the handler end to end):
 *  - `DebugOffloadHandler.handle()` is bound to `android.content.Context`
 *    (token + file reads) and its RPC round trip needs the in-app DebugServer
 *    on 127.0.0.1:5321, so a JVM run would prove nothing about the real CLI.
 *  - So CI pins: (a) the pure join + the subcommand scoping of the post-pass,
 *    (b) the params-rewrite contract (only `path`; omitted stays omitted),
 *    (c) the *source* wiring of the private `handle()` body, and the CLI-arg
 *    shapes that are pinned verbatim by `offload_cli_args_check.py` D7/D8.
 *  - The real bodies are additionally compiled and run in the sandbox probe.
 *
 * Expectations below are literals, never derived from the code under test.
 */
class DebugOffloadHandlerTest {

    // ── part 1: the shared join (resolveMaybeRelative) drives the CLI too ────

    @Test
    fun `a relative positional joins onto the guest cwd`() {
        assertEquals(
            "/var/minis/workspace/notes.txt",
            resolveMaybeRelative("notes.txt", "/var/minis/workspace"),
        )
        assertEquals(
            "/var/minis/workspace/sub/x.json",
            resolveMaybeRelative("sub/x.json", "/var/minis/workspace"),
        )
        assertEquals(
            "/var/minis/workspace/./x.txt",
            resolveMaybeRelative("./x.txt", "/var/minis/workspace"),
        )
    }

    @Test
    fun `an absolute positional and an unusable cwd are passed through`() {
        assertEquals("/tmp/x.txt", resolveMaybeRelative("/tmp/x.txt", "/var/minis/workspace"))
        assertEquals("notes.txt", resolveMaybeRelative("notes.txt", null))
        assertEquals("notes.txt", resolveMaybeRelative("notes.txt", ""))
        assertEquals("notes.txt", resolveMaybeRelative("notes.txt", "relative/cwd"))
    }

    @Test
    fun `a trailing slash on the cwd does not double up`() {
        assertEquals(
            "/var/minis/workspace/notes.txt",
            resolveMaybeRelative("notes.txt", "/var/minis/workspace/"),
        )
    }

    // ── part 2: subcommand scoping of the post-pass ─────────────────────────

    @Test
    fun `only the path-carrying subcommands are normalized`() {
        assertEquals(setOf("ls", "read", "write"), DEBUG_PATH_SUBCOMMANDS)
        for (sub in listOf("ls", "read", "write")) {
            assertEquals(
                sub,
                "/var/minis/workspace/a.txt",
                debugPathFor(sub, "a.txt", "/var/minis/workspace"),
            )
        }
        // Pass-through tails (`exec` / `shizuku` / `model-use`) and the
        // parameter-less subcommands must never have a token rewritten.
        for (sub in listOf(
            "exec", "shizuku", "model-use", "modelUse", "screenshot", "discover", "appInfo", "call",
        )) {
            assertNull(sub, debugPathFor(sub, "a.txt", "/var/minis/workspace"))
        }
    }

    @Test
    fun `an omitted path stays omitted`() {
        assertNull(debugPathFor("ls", "", "/var/minis/workspace"))

        // `minis-debug ls` with no argument relies on the RPC's own default —
        // the post-pass must not materialise an empty `path`.
        val bare = JSONObject()
        normalizeDebugRpcPaths("ls", bare, "/var/minis/workspace")
        assertFalse("`ls` with no argument must keep its RPC default", bare.has("path"))

        val empty = JSONObject().put("path", "")
        normalizeDebugRpcPaths("ls", empty, "/var/minis/workspace")
        assertEquals("", empty.getString("path"))
    }

    @Test
    fun `the post-pass rewrites only the path field of the built params`() {
        val write = JSONObject().put("path", "notes.txt").put("content", "hi").put("encoding", "utf8")
        normalizeDebugRpcPaths("write", write, "/var/minis/workspace")
        assertEquals("/var/minis/workspace/notes.txt", write.getString("path"))
        assertEquals("hi", write.getString("content"))
        assertEquals("utf8", write.getString("encoding"))

        val read = JSONObject().put("path", "/etc/passwd").put("limit", 100)
        normalizeDebugRpcPaths("read", read, "/var/minis/workspace")
        assertEquals("/etc/passwd", read.getString("path"))
        assertEquals(100L, read.getLong("limit"))

        val exec = JSONObject().put("command", "cat notes.txt")
        normalizeDebugRpcPaths("exec", exec, "/var/minis/workspace")
        assertFalse("pass-through commands must stay verbatim", exec.has("path"))
        assertEquals("cat notes.txt", exec.getString("command"))
    }

    // ── part 3: wiring this test cannot execute (private, Context-bound) ────

    @Test
    fun `handle normalizes the built rpc params with the request cwd`() {
        val body = memberSlice(
            readRepoFile(HANDLER_REL),
            "override fun handle(request: NativeOffloadRequest)",
        )
        assertTrue(
            "the post-pass must run on the params of the RpcCall buildRpcCall returned",
            body.contains("normalizeDebugRpcPaths(sub, rpc.params, request.cwd)"),
        )
        assertTrue("the subcommand token must come from splitArgv", body.contains("val sub = argv.subcommand"))
    }

    @Test
    fun `buildRpcCall keeps the raw positional and its pinned signature`() {
        val src = readRepoFile(HANDLER_REL)
        // offload_cli_args_check.py D7/D8 pin these two strings verbatim: the
        // post-pass must not be inlined into buildRpcCall.
        assertTrue(src.contains("buildRpcCall(sub, args, argv.rawRest)"))
        assertTrue(
            src.contains(
                "private fun buildRpcCall(sub: String, args: OffloadArgs, rawRest: List<String>): RpcCall {",
            ),
        )
        // The raw positional is still what buildRpcCall reads — normalization
        // happens once, on the built params, not per subcommand branch.
        for (needle in listOf(
            "rest.firstOrNull()?.let { p.put(\"path\", it) }",
            "val p = JSONObject().put(\"path\", path)",
            "val p = JSONObject().put(\"path\", path).put(\"content\", content)",
        )) {
            assertTrue("missing raw-positional shape: $needle", src.contains(needle))
        }
    }

    @Test
    fun `the pass-through subcommand set is unchanged`() {
        val src = readRepoFile(HANDLER_REL)
        assertTrue(
            src.contains(
                "private val PASSTHROUGH_SUBCOMMANDS = setOf(\"exec\", \"shizuku\", \"model-use\", \"modelUse\")",
            ),
        )
    }

    @Test
    fun `handle forwards the caller session id to the path-scoped subcommands`() {
        val body = memberSlice(
            readRepoFile(HANDLER_REL),
            "override fun handle(request: NativeOffloadRequest)",
        )
        // [dv16-A03] ls/read/write are session-scoped in the PRoot layout:
        // without the caller's session id the RPC handler's global resolver
        // would search every session tree. The passthrough must gate on the
        // session-scoped subcommand set AND the request's session id.
        assertTrue(
            "the passthrough must gate on SESSION_SCOPED_SUBCOMMANDS",
            body.contains("sub in SESSION_SCOPED_SUBCOMMANDS"),
        )
        assertTrue(
            "the passthrough must gate on request.sessionId being present",
            body.contains("request.sessionId.isNullOrBlank()"),
        )
        assertTrue(
            "the session id must reach the rpc params",
            body.contains("rpc.params.put(\"sessionId\", request.sessionId)"),
        )
    }

    @Test
    fun `the rpc file-system handlers resolve session-scoped and never bare-global`() {
        val src = readRepoFile(RPC_REL)
        for (marker in listOf("private fun handleLS(", "private fun handleReadFile(", "private fun handleWriteFile(")) {
            val body = memberSlice(src, marker)
            assertTrue("$marker must resolve through resolvePathForSession", body.contains("PRootKernel.resolvePathForSession("))
            assertFalse(
                "$marker must not fall back to the bare global resolveHostPath (searches every session tree)",
                body.contains("PRootKernel.resolveHostPath("),
            )
        }
        // The session id comes from the rpc params (forwarded by the offload
        // handler or sent by an external JSON-RPC client); a blank one stays
        // null so the historical global behaviour survives for session-less
        // callers.
        val ls = memberSlice(src, "private fun handleLS(")
        assertTrue(ls.contains("params.optString(\"sessionId\", \"\").ifBlank { null }"))
    }

    @Test
    fun `handleWriteFile keeps the pre-boot rootfsDir fallback`() {
        val body = memberSlice(readRepoFile(RPC_REL), "private fun handleWriteFile(")
        assertTrue(
            "the pre-boot staging path must keep the rootfsDir fallback",
            body.contains("File(rootfsDir, path.removePrefix(\"/\"))"),
        )
        assertTrue(
            "the read-only mount refusal must stay in front of the write",
            body.contains("isLinuxPathUnderReadOnlyMount(path)"),
        )
    }

    // ── part 4: the session-vs-global resolution, real temp dirs ────────────
    // [dv16-A03] drives the pure resolvers the production call sites use —
    // with REAL files, so the boot-state differences and the cross-session
    // misread the fix removes are demonstrated, not inferred from reading.

    private fun sessionDirsRoot(): File {
        val root = java.nio.file.Files.createTempDirectory("dv16-a03-probe-").toFile()
        File(root, "minis-sessions/sess-A/workspace").mkdirs()
        File(root, "minis-sessions/sess-B/workspace").mkdirs()
        return root
    }

    @Test
    fun `a session-scoped read only sees its own session tree`() {
        val root = sessionDirsRoot()
        File(root, "minis-sessions/sess-A/workspace/notes.txt").writeText("A's file")
        File(root, "minis-sessions/sess-B/workspace/notes.txt").writeText("B's file")
        var globalCalls = 0
        val resolved = com.rikkaminis.app.sandbox.resolveSessionGuestHostPath(
            sessionId = "sess-A",
            linuxPath = "/var/minis/workspace/notes.txt",
            filesDir = root,
            perSessionSubdirs = setOf("workspace"),
            resolveGlobal = { globalCalls++; null },
        )
        assertEquals(
            File(root, "minis-sessions/sess-A/workspace/notes.txt").canonicalPath,
            resolved!!.canonicalPath,
        )
        assertEquals("a session hit must never consult the global resolver", 0, globalCalls)
    }

    @Test
    fun `a session miss stays inside the session tree and never crosses over`() {
        val root = sessionDirsRoot()
        // Only sess-B has the file. From sess-C (no dir yet) the resolver
        // must hand back sess-C's own path (a valid WRITE target) and must
        // NOT answer with B's file.
        File(root, "minis-sessions/sess-B/workspace/notes.txt").writeText("B's file")
        val resolved = com.rikkaminis.app.sandbox.resolveSessionGuestHostPath(
            sessionId = "sess-C",
            linuxPath = "/var/minis/workspace/notes.txt",
            filesDir = root,
            perSessionSubdirs = setOf("workspace"),
            resolveGlobal = { fail("global resolver must not be consulted for a session-scoped path"); null },
        )
        assertEquals(
            File(root, "minis-sessions/sess-C/workspace/notes.txt").canonicalPath,
            resolved!!.canonicalPath,
        )
        assertFalse("sess-C has no file yet (read: absent; write: lands in its own tree)", resolved.exists())
    }

    @Test
    fun `the bare global resolver misreads across sessions (pre-fix defect)`() {
        val root = sessionDirsRoot()
        // Only sess-B has the file — the pre-fix call path
        // (resolveHostPath, no session id) picks it up for EVERY session.
        File(root, "minis-sessions/sess-B/workspace/notes.txt").writeText("B's file")
        val global = com.rikkaminis.app.sandbox.resolveGuestHostPath(
            linuxPath = "/var/minis/workspace/notes.txt",
            bindMounts = emptyMap(),
            perSessionSubdirs = setOf("workspace"),
            sessionsRoot = File(root, "minis-sessions"),
            rootfsDir = null,
        )
        assertEquals("B's file", global!!.readText())
    }

    @Test
    fun `a path outside the session scopes keeps the global resolver`() {
        val root = sessionDirsRoot()
        var globalCalls = 0
        val resolved = com.rikkaminis.app.sandbox.resolveSessionGuestHostPath(
            sessionId = "sess-A",
            linuxPath = "/tmp/shared.json",
            filesDir = root,
            perSessionSubdirs = setOf("workspace"),
            resolveGlobal = { globalCalls++; File("/global-root", it.removePrefix("/")) },
        )
        assertEquals(File("/global-root/tmp/shared.json").path, resolved?.path)
        assertEquals(1, globalCalls)
    }

    @Test
    fun `pre-boot the global resolver is null and the session path is still a write target`() {
        val root = java.nio.file.Files.createTempDirectory("dv16-a03-preboot-").toFile()
        // minis-sessions does not exist yet; the rootfs is not initialized.
        val global = com.rikkaminis.app.sandbox.resolveGuestHostPath(
            linuxPath = "/var/minis/workspace/stage.json",
            bindMounts = emptyMap(),
            perSessionSubdirs = setOf("workspace"),
            sessionsRoot = File(root, "minis-sessions"),
            rootfsDir = null,
        )
        assertNull("pre-boot global resolution returns null (handleWriteFile falls back to rootfsDir)", global)
        val session = com.rikkaminis.app.sandbox.resolveSessionGuestHostPath(
            sessionId = "sess-A",
            linuxPath = "/var/minis/workspace/stage.json",
            filesDir = root,
            perSessionSubdirs = setOf("workspace"),
            resolveGlobal = { fail("session-scoped boot-less resolution must stay in the session tree"); null },
        )
        assertEquals(
            File(root, "minis-sessions/sess-A/workspace/stage.json").canonicalPath,
            session!!.canonicalPath,
        )
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
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/DebugOffloadHandler.kt"
        const val RPC_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/debug/DebugRPCHandler.kt"
    }
}
