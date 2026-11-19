package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [R3-B F-04] `browser_use(action="fetch")` read the whole response body into
 * the renderer with `arrayBuffer()`, then built the base64 payload one byte at
 * a time (`binary += String.fromCharCode(bytes[i])`) and handed that string
 * across the JS bridge. A 30–100 MB download therefore materialised the body,
 * a ~2× base64 copy of it, and the per-byte concat (quadratic in the body
 * size) inside the renderer — killing it, or OOM-ing the main process that
 * decoded the result.
 *
 * The fix streams the body with `resp.body.getReader()` under a running byte
 * budget ([BrowserUseJS.MAX_FETCH_BYTES]) and refuses an over-limit response
 * with a structured error — never a truncated half file.
 *
 * This class BEHAVIOURALLY tests that: it renders the REAL [BrowserUseJS.fetch]
 * template, runs it under `node` against a fake streaming body, and asserts on
 * what the script actually did — bytes delivered, whether the reader was
 * cancelled, and the sha256 of the bytes that came back. A shape assertion
 * could only prove the text mentions a limit; these assertions prove the limit
 * is enforced.
 *
 * `node` is not present everywhere, so the class skips (never fails) when it
 * is missing — the same house pattern the mount-guard exec test uses.
 */
class BrowserFetchByteBudgetTest {

    // ── the budget itself ───────────────────────────────────────────────

    @Test
    fun `the fetch budget is finite and matches the blob download budget`() {
        assertEquals(
            "the fetch budget must be a concrete byte count",
            16 * 1024 * 1024,
            BrowserUseJS.MAX_FETCH_BYTES,
        )
        assertTrue(
            "the budget must be a positive, finite number of bytes",
            BrowserUseJS.MAX_FETCH_BYTES in 1..(64 * 1024 * 1024),
        )
    }

    // ── behaviour: inside the limit ─────────────────────────────────────

    @Test
    fun `a body inside the limit comes back byte identical`() {
        val total = 100_000
        val run = runScript(total, chunkSize = 4096, declaredLength = total)
        val json = parse(run)
        assertNull("a body inside the limit must not error: ${json}", json.optString("error").takeIf { it.isNotEmpty() })
        assertEquals("the reported size must be the real byte count", total, json.optInt("size", -1))
        val decoded = java.util.Base64.getDecoder().decode(json.optString("base64"))
        assertEquals("the decoded payload must have the same length", total, decoded.size)
        assertEquals(
            "the decoded payload must be byte identical (sha256 of the bytes that " +
                "came back vs. sha256 of the bytes that were sent)",
            sha256(ByteArray(total) { 0x42 }),
            sha256(decoded),
        )
        assertFalse("the reader must not have been cancelled", run.cancelled)
        assertEquals("the whole body must have been read", total.toLong(), run.delivered)
    }

    @Test
    fun `a body exactly at the limit still succeeds`() {
        val total = BrowserUseJS.MAX_FETCH_BYTES
        val run = runScript(total, chunkSize = 65536, declaredLength = total)
        val json = parse(run)
        assertNull(
            "the boundary case is inside the budget and must succeed: ${json}",
            json.optString("error").takeIf { it.isNotEmpty() },
        )
        assertEquals("the size must be the full body", total, json.optInt("size", -1))
        val decoded = java.util.Base64.getDecoder().decode(json.optString("base64"))
        assertEquals("the decoded payload must have the same length", total, decoded.size)
        assertEquals(
            "the bytes must be identical at the boundary too",
            sha256(ByteArray(total) { 0x42 }),
            sha256(decoded),
        )
    }

    // ── behaviour: over the limit ───────────────────────────────────────

    @Test
    fun `an over-limit body is refused explicitly, not truncated`() {
        val total = BrowserUseJS.MAX_FETCH_BYTES + 1
        val run = runScript(total, chunkSize = 65536, declaredLength = total)
        val json = parse(run)
        assertTrue(
            "an over-limit body must produce an error: $json",
            json.optString("error").isNotEmpty(),
        )
        assertTrue(
            "the error must say the response was too large: ${json.optString("error")}",
            json.optString("error").contains("too large"),
        )
        assertEquals("the refusal must be marked as such", true, json.optBoolean("tooLarge", false))
        assertTrue(
            "a refused body must NOT come back as a payload — a truncated half file " +
                "that looks like a successful fetch is the worse outcome: $json",
            json.optString("base64").isEmpty(),
        )
        assertTrue("the body must have been cancelled", run.cancelled)
        assertEquals(
            "a declared over-limit length must be refused BEFORE the body is read",
            0,
            run.delivered,
        )
    }

    @Test
    fun `a body over the limit without a content length is refused mid-stream`() {
        val total = BrowserUseJS.MAX_FETCH_BYTES * 4
        val chunk = 65536
        val run = runScript(total, chunkSize = chunk, declaredLength = null)
        val json = parse(run)
        assertTrue(
            "an over-limit body must produce an error: $json",
            json.optString("error").isNotEmpty(),
        )
        assertTrue(
            "the error must say the response was too large: ${json.optString("error")}",
            json.optString("error").contains("too large"),
        )
        assertTrue("the body must have been cancelled", run.cancelled)
        assertTrue(
            "the read must stop at the budget — it must not pull the whole body into " +
                "the renderer (delivered ${run.delivered}, budget ${BrowserUseJS.MAX_FETCH_BYTES})",
            run.delivered <= BrowserUseJS.MAX_FETCH_BYTES + chunk,
        )
        assertTrue(
            "the refusal must happen well before the whole body was read (delivered " +
                "${run.delivered} of $total)",
            run.delivered < total,
        )
    }

    @Test
    fun `a lying content length cannot smuggle an over-limit body through`() {
        val total = BrowserUseJS.MAX_FETCH_BYTES * 2
        val chunk = 65536
        val run = runScript(total, chunkSize = chunk, declaredLength = 1024)
        val json = parse(run)
        assertTrue(
            "a content-length that understates the body must not be trusted: $json",
            json.optString("error").isNotEmpty(),
        )
        assertTrue("the body must have been cancelled", run.cancelled)
        assertTrue(
            "the running byte count must catch it (delivered ${run.delivered})",
            run.delivered <= BrowserUseJS.MAX_FETCH_BYTES + chunk,
        )
    }

    // ── the harness itself must be faithful ──────────────────────────────

    @Test
    fun `the rendered template contains exactly the two interpolations`() {
        val template = fetchTemplate()
        assertTrue(
            "the template must interpolate the byte budget",
            template.contains("\$MAX_FETCH_BYTES"),
        )
        assertTrue(
            "the template must interpolate the escaped URL",
            template.contains("\$escaped"),
        )
        val rendered = render(template, "https://example.test/file")
        assertFalse(
            "an unknown interpolation would make this harness render a DIFFERENT " +
                "script than the app runs — the rendered text still contains a '\$'",
            rendered.contains("\$"),
        )
    }

    @Test
    fun `the rendered script still carries the real budget`() {
        val rendered = render(fetchTemplate(), "https://example.test/file")
        assertTrue(
            "the budget must travel INTO the script — a limit the script does not " +
                "know about cannot be enforced while the body is read",
            rendered.contains(BrowserUseJS.MAX_FETCH_BYTES.toString()),
        )
    }

    // ── shape: the structure that makes the behaviour possible ──────────
    //
    // These need no `node`, so the structural guarantee survives on a runner
    // without it (the behavioural arms above skip there).

    @Test
    fun `the body is streamed, never read as one buffer`() {
        val template = fetchTemplate()
        assertTrue(
            "the body must be read through a streaming reader — a single " +
                "arrayBuffer() is the pre-fix shape that materialised the whole " +
                "body in the renderer",
            template.contains("resp.body.getReader()"),
        )
        assertFalse(
            "the pre-fix whole-body read must be gone",
            template.contains("await resp.arrayBuffer()"),
        )
    }

    @Test
    fun `the byte budget is checked while the body is read`() {
        val template = fetchTemplate()
        assertTrue(
            "the running byte count must be compared against the budget inside " +
                "the read loop — a check after the loop cannot prevent the OOM",
            template.contains("if (total > MAX_BYTES) {"),
        )
        assertTrue(
            "the over-limit read must cancel the body — otherwise the download " +
                "keeps running in the renderer",
            template.contains("reader.cancel()"),
        )
    }

    @Test
    fun `the payload is not built one byte at a time`() {
        val template = fetchTemplate()
        assertFalse(
            "the per-byte concat was quadratic in the body size — the payload " +
                "must be built in bounded slices",
            Regex("""binary \+= String\.fromCharCode\((?!apply)""").containsMatchIn(template),
        )
        assertTrue(
            "the payload must be built in bounded slices",
            template.contains("String.fromCharCode.apply("),
        )
    }

    @Test
    fun `an over-limit body is refused rather than truncated`() {
        val template = fetchTemplate()
        assertTrue(
            "the refusal must be a structured error the Android side can read",
            template.contains("tooLarge: true"),
        )
        assertTrue(
            "the refusal must name the size",
            template.contains("'response too large: '"),
        )
        val overLimitBlock = template
            .substringAfter("if (total > MAX_BYTES) {")
            .substringBefore("chunks.push")
        assertFalse(
            "the over-limit branch must not emit a payload — a truncated half " +
                "file that looks like a successful fetch is the worse outcome:\n$overLimitBlock",
            overLimitBlock.contains("base64"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private class ScriptRun(val json: String, val delivered: Long, val cancelled: Boolean)

    /**
     * Render the real [BrowserUseJS.fetch] template, run it under `node`
     * against a fake streaming body, and return what the script produced plus
     * what it actually read.
     */
    private fun runScript(totalBytes: Int, chunkSize: Int, declaredLength: Int?): ScriptRun {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node", "/bin/node")
            .map(::File).firstOrNull { it.canExecute() }
        assumeTrue("node is not available", node != null)
        val dir = File(System.getProperty("java.io.tmpdir"), "minis-fetch-budget-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val script = File(dir, "script.js")
            script.writeText(render(fetchTemplate(), "https://example.test/file"))
            val driver = File(dir, "driver.js")
            driver.writeText(DRIVER)
            val proc = ProcessBuilder(
                node!!.absolutePath, driver.absolutePath, script.absolutePath,
                totalBytes.toString(), chunkSize.toString(),
                declaredLength?.toString() ?: "null",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            assertEquals("the node driver failed:\n$out", 0, rc)
            val parsed = parseDriver(out)
            return ScriptRun(parsed.json, parsed.delivered, parsed.cancelled)
        } finally {
            dir.deleteRecursively()
        }
    }

    private data class DriverResult(val json: String, val delivered: Long, val cancelled: Boolean)

    private fun parseDriver(out: String): DriverResult {
        val line = out.trim().lines().lastOrNull { it.startsWith("{") }
        assertNotNull("the driver printed no result:\n$out", line)
        val obj = org.json.JSONObject(line!!)
        return DriverResult(
            json = obj.optString("result"),
            delivered = obj.optLong("delivered", -1),
            cancelled = obj.optBoolean("cancelled", false),
        )
    }

    private fun parse(run: ScriptRun): org.json.JSONObject = org.json.JSONObject(run.json)

    /** The raw `"""..."""` template of [BrowserUseJS.fetch]. */
    private fun fetchTemplate(): String {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseJS.kt"
        )
        val fn = src.indexOf("fun fetch(url: String): String {")
        assertTrue("the fetch function was not found", fn >= 0)
        val tplStart = src.indexOf("\"\"\"", fn)
        assertTrue("the fetch template start was not found", tplStart >= 0)
        val from = tplStart + 3
        val tplEnd = src.indexOf("\"\"\"", from)
        assertTrue("the fetch template end was not found", tplEnd >= 0)
        return src.substring(from, tplEnd)
    }

    /** What Kotlin's string template produces for this call. */
    private fun render(template: String, url: String): String =
        template
            .replace("\$MAX_FETCH_BYTES", BrowserUseJS.MAX_FETCH_BYTES.toString())
            .replace("\$escaped", jsEscape(url))

    /**
     * The production code interpolates the URL INTO the quotes the template
     * already carries (`fetch("$escaped")`), so the escape must not add a
     * second pair of quotes.
     */
    private fun jsEscape(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        /**
         * The driver: a fake `fetch` that streams [totalBytes] of 0x42 in
         * [chunkSize] chunks, plus a fake Response whose `headers.get` may
         * declare a (possibly lying) content-length. It reports how many bytes
         * the script actually pulled and whether the body was cancelled.
         */
        private val DRIVER = """
            const fs = require('fs');
            const scriptPath = process.argv[2];
            const total = parseInt(process.argv[3], 10);
            const chunkSize = parseInt(process.argv[4], 10);
            const declaredRaw = process.argv[5];
            const declared = declaredRaw === 'null' ? null : parseInt(declaredRaw, 10);
            let delivered = 0;
            let cancelled = false;
            function makeReader() {
                let sent = 0;
                return {
                    read() {
                        if (sent >= total) return Promise.resolve({ done: true, value: undefined });
                        const n = Math.min(chunkSize, total - sent);
                        sent += n;
                        delivered += n;
                        const v = new Uint8Array(n);
                        v.fill(0x42);
                        return Promise.resolve({ done: false, value: v });
                    },
                    cancel() { cancelled = true; return Promise.resolve(); }
                };
            }
            globalThis.fetch = function (url) {
                return Promise.resolve({
                    status: 200,
                    url: url,
                    headers: {
                        get: function (name) {
                            if (String(name).toLowerCase() === 'content-length' && declared !== null) {
                                return String(declared);
                            }
                            return null;
                        }
                    },
                    body: {
                        getReader: makeReader,
                        cancel: function () { cancelled = true; return Promise.resolve(); }
                    }
                });
            };
            const src = fs.readFileSync(scriptPath, 'utf8');
            Promise.resolve(eval(src)).then(function (result) {
                console.log(JSON.stringify({ result: String(result), delivered: delivered, cancelled: cancelled }));
            }).catch(function (e) {
                console.log(JSON.stringify({ result: JSON.stringify({ error: String(e && e.message) }), delivered: delivered, cancelled: cancelled }));
            });
        """.trimIndent()
    }
}
