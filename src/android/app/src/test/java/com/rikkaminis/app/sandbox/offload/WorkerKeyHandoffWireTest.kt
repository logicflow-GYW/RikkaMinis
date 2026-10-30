package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-worker-key-intent-handoff] Source-shape contract for the fix.
 *
 * The defect being closed is a WIRING one: the key must travel from the two
 * dispatch sites into both worker run paths, and a request that carries it
 * must bypass the cross-process freshness abort. None of that is reachable
 * from a JVM test (Context, Binder, EncryptedSharedPreferences), so the shape
 * is pinned here and the decision logic is pinned in [WorkerKeyHandoffTest].
 *
 * Every criterion has a negative control that mutates the REAL source text in
 * memory and asserts the same predicate goes red — a criterion that only ever
 * passes is decoration.
 *
 * Comments are stripped before matching: the production KDoc in this change
 * names `carriedApiKey`, `WorkerKeyHandoff.attach` and `carriesKey`, so a raw
 * `contains` would be satisfied by prose alone.
 */
class WorkerKeyHandoffWireTest {

    private val dispatcherPath =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionDispatcher.kt"
    private val streamPath =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ChatStreamOffloadHandler.kt"
    private val servicePath =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"
    private val handoffPath =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/WorkerKeyHandoff.kt"
    private val gatewayPath =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ProviderExecutionGateway.kt"
    private val offloadDir = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload"

    // ── dispatch side: the key rides the Intent ─────────────────────────────

    @Test
    fun `dispatcher attaches the key inside the request Intent`() {
        assertDispatchAttaches(read(dispatcherPath))
    }

    @Test
    fun `streaming handler attaches the key inside the request Intent`() {
        assertDispatchAttaches(read(streamPath))
    }

    /** Neg control: the same predicate must reject a source without the attach. */
    @Test
    fun `a dispatch site without the attach is rejected`() {
        val mutated = read(dispatcherPath)
            .replace("WorkerKeyHandoff.attachKey(this, apiKey)", "")
        assertFalse(
            "predicate passed on a source with no attach — it is decoration",
            attachesKey(codeOnly(mutated)),
        )
    }

    /**
     * Exactly one resolver: the gateway is the only main-process path holding
     * the instance, and it feeds both dispatch shapes. A third dispatch site
     * added without resolving a key silently falls back to the legacy
     * freshness handoff — which is what this pins against.
     */
    @Test
    fun `the gateway resolves the key for both dispatch shapes`() {
        val code = codeOnly(read(gatewayPath))
        assertEquals(
            "expected one keyFor(context, instance.id) per dispatch shape (send + stream)",
            2,
            countOf(code, "WorkerKeyHandoff.keyFor(context, instance.id)"),
        )
        assertTrue(code.contains("ModelExecutionDispatcher.dispatch("))
        assertTrue(code.contains("ChatStreamOffloadHandler.stream("))
    }

    // ── worker side: read the extra, prefer it, suppress the abort ──────────

    @Test
    fun `worker reads the extra in onStartCommand`() {
        val code = codeOnly(read(servicePath))
        assertTrue(
            "onStartCommand never reads ${WorkerKeyHandoff.EXTRA_API_KEY}",
            code.contains("getStringExtra(WorkerKeyHandoff.EXTRA_API_KEY)"),
        )
    }

    @Test
    fun `both run paths forward the carried key`() {
        val code = codeOnly(read(servicePath))
        assertTrue(code.contains("executeStreamingRun(req, dir, carriedApiKey)"))
        assertTrue(code.contains("executeRun(req, dir, carriedApiKey)"))
    }

    @Test
    fun `both run paths prefer the carried key and both consult the abort gate`() {
        val code = codeOnly(read(servicePath))
        assertEquals(
            "expected exactly two pickKey(carriedApiKey) sites (streaming + non-streaming)",
            2,
            countOf(code, "WorkerKeyHandoff.pickKey(carriedApiKey)"),
        )
        assertEquals(
            "expected exactly two staleKeyAbortNeeded(carriedApiKey) sites",
            2,
            countOf(code, "staleKeyAbortNeeded(carriedApiKey)"),
        )
    }

    @Test
    fun `the abort gate returns early for a carried key, before touching the prefs mtime`() {
        val body = bodyOf(codeOnly(read(servicePath)), "private fun staleKeyAbortNeeded")
        val earlyReturn = body.indexOf("if (WorkerKeyHandoff.carriesKey(carriedApiKey)) return false")
        val baseline = body.indexOf("WorkerKeyFreshness.captureBaseline")
        assertTrue("abort gate has no carried-key early return", earlyReturn >= 0)
        assertTrue("baseline is captured before the carried-key guard", baseline > earlyReturn)
    }

    @Test
    fun `a poisoned worker still serves requests that carry their own key`() {
        val code = codeOnly(read(servicePath))
        assertTrue(
            "admission rejects every request from a poisoned worker, so one keyless " +
                "dispatch can starve its siblings",
            code.contains(
                "val poisoned = keyCachePoisoned && !WorkerKeyHandoff.carriesKey(carriedApiKey)",
            ),
        )
    }

    /** Neg control: neutering the guard in the real source must flip the check. */
    @Test
    fun `a neutered carried-key guard is rejected`() {
        val mutated = read(servicePath).replace(
            "if (WorkerKeyHandoff.carriesKey(carriedApiKey)) return false",
            "if (false) return false",
        )
        val body = bodyOf(codeOnly(mutated), "private fun staleKeyAbortNeeded")
        assertFalse(body.contains("if (WorkerKeyHandoff.carriesKey(carriedApiKey)) return false"))
    }

    // ── invariants this fix must not break ─────────────────────────────────

    /**
     * The plaintext key must never reach the on-disk request bundle — the
     * project's standing rule (KeyRoulette's KDoc: "the plaintext never
     * touches disk"). The Intent extra is the only transport on purpose.
     */
    @Test
    fun `the plaintext key never enters the request bundle`() {
        val offenders = File(repoRoot(), offloadDir).listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { codeOnly(it.readText()).contains("put(\"api_key") }
            .map { it.name }
        assertEquals("request bundle carries a plaintext api_key: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `the handoff never logs the key`() {
        val code = codeOnly(read(handoffPath))
        assertFalse(code.contains("Log."))
        assertFalse(code.contains("println"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun assertDispatchAttaches(src: String) {
        assertTrue("dispatch Intent does not attach the key", attachesKey(codeOnly(src)))
    }

    private fun attachesKey(code: String): Boolean {
        val dirExtra = code.indexOf("putExtra(ModelExecutionService.EXTRA_REQUEST_DIR")
        val attach = code.indexOf("WorkerKeyHandoff.attachKey(this, apiKey)")
        val start = code.indexOf("startService(intent)", dirExtra.coerceAtLeast(0))
        if (dirExtra < 0 || attach < 0 || start < 0) return false
        // Same Intent construction, and before the dispatch actually fires.
        return attach in (dirExtra + 1) until start && (start - dirExtra) < 1_200
    }

    private fun countOf(hay: String, needle: String): Int {
        var n = 0
        var i = hay.indexOf(needle)
        while (i >= 0) {
            n += 1
            i = hay.indexOf(needle, i + needle.length)
        }
        return n
    }

    private fun bodyOf(code: String, marker: String): String {
        val open = code.indexOf(marker)
        if (open < 0) return ""
        val brace = code.indexOf('{', open)
        if (brace < 0) return ""
        var depth = 0
        var i = brace
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return code.substring(brace, i + 1)
                }
            }
            i += 1
        }
        return ""
    }

    private fun read(rel: String): String {
        val f = File(repoRoot(), rel)
        if (!f.exists()) throw FileNotFoundException(f.path)
        return f.readText()
    }

    private fun repoRoot(): File {
        System.getProperty("probe.repo")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, dispatcherPath).exists()) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from ${System.getProperty("user.dir")}")
    }

    /**
     * Comments stripped — linear scan (regex forms blow the stack on the
     * multi-MB offload sources; see [OffloadPayloadJsonShapeTest]).
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
                i += 1
            }
        }
        return out.toString()
    }
}
