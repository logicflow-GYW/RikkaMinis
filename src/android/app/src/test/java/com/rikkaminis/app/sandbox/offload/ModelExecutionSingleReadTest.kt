package com.rikkaminis.app.sandbox.offload

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * [ISSUE-12a] deep-audit-1018 (P2) — request.json must be read from disk and
 * parsed exactly ONCE per run.
 *
 * Baseline behavior (pre-fix): the worker read `request.json` three times —
 * the queue-frame decision, the formal read, and the ack-barrier decision —
 * and parsed it at each site (2 redundant reads + 2 redundant parses of a
 * multi-MB body). [CapturedRequestBody] now captures once; this file pins
 * (a) the read-once property, (b) that each consumer site still behaves
 * exactly like the old code (per-site failure semantics), and (c) that the
 * wiring in ModelExecutionService.kt really consumes the capture.
 *
 * Device shape: the disk read is an injected `() -> String` counter, so no
 * file system and no Android Service instantiation is needed — "read once" is
 * asserted directly. The three PRE-FIX sites are transcribed 1:1 in
 * [BaselineSites] and compared case by case (differential), which is what
 * makes "observationally identical apart from the removed work" checkable.
 *
 * Expected values are literals; nothing is derived from the code under test.
 */
class ModelExecutionSingleReadTest {

    // ---- the three PRE-FIX sites, transcribed from the baseline source ----

    private class BaselineSites(private val read: () -> String) {
        /** Site 1 (queue-frame decision) / site 3 (ack-barrier decision). */
        fun flagOrFalse(): Boolean = runCatching {
            JSONObject(read()).optBoolean("streaming", false)
        }.getOrDefault(false)

        /** Site 2 (formal read): read/parse failure escapes as an exception. */
        fun formal(): Boolean {
            val requestText = read()
            return JSONObject(requestText).optBoolean("streaming", false)
        }
    }

    private class CountingRead(private val body: () -> String) {
        var calls = 0
            private set

        fun read(): String {
            calls++
            return body()
        }
    }

    private fun bodyWithStreaming(value: Any?): String =
        if (value == null) """{"model":"m","messages":[]}"""
        else """{"model":"m","messages":[],"streaming":$value}"""

    // ---- 1) read-once ----

    @Test
    fun capture_readsTheRequestBodyExactlyOnceForAllThreeSites() {
        val counting = CountingRead { bodyWithStreaming("true") }
        val captured = CapturedRequestBody.capture(counting::read)

        // pull every projection: site 1 flag, site 2 json, site 3 flag
        val site1 = captured.streamingFlagOrFalse()
        val site2 = captured.jsonOrThrow().optBoolean("streaming", false)
        val site3 = captured.streamingFlagOrFalse()

        assertEquals("one disk read serves all three sites", 1, counting.calls)
        assertEquals(true, site1)
        assertEquals(true, site2)
        assertEquals(true, site3)
    }

    @Test
    fun capture_isIdempotent_noSecondReadOnRepeatedUse() {
        val counting = CountingRead { bodyWithStreaming("false") }
        val captured = CapturedRequestBody.capture(counting::read)

        repeat(5) { captured.streamingFlagOrFalse() }
        repeat(3) { captured.jsonOrThrow() }

        assertEquals(1, counting.calls)
        assertEquals(false, captured.streamingFlagOrFalse())
    }

    // ---- 2) per-site semantics vs the pre-fix baseline ----

    @Test
    fun allThreeSitesMatchThePreFixBaselineForEveryBodyShape() {
        val cases: List<Pair<String, () -> String>> = listOf(
            "streaming=true" to { bodyWithStreaming("true") },
            "streaming=false" to { bodyWithStreaming("false") },
            "streaming key absent" to { bodyWithStreaming(null) },
            "streaming non-boolean" to { bodyWithStreaming("\"yes\"") },
            "malformed json" to { """{"model":"m","}""" },
        )
        for ((label, make) in cases) {
            val baseline = BaselineSites(make)
            val captured = CapturedRequestBody.capture(make)

            // site 1 (queue-frame decision)
            assertEquals("$label: site1 flag", baseline.flagOrFalse(), captured.streamingFlagOrFalse())

            // site 2 (formal read): same value or same exception type + message
            val expected = runCatching { baseline.formal() }
            val actual = runCatching { captured.jsonOrThrow().optBoolean("streaming", false) }
            assertEquals("$label: site2 success/failure", expected.isSuccess, actual.isSuccess)
            if (expected.isSuccess) {
                assertEquals("$label: site2 value", expected.getOrThrow(), actual.getOrThrow())
            } else {
                val e = expected.exceptionOrNull()!!
                val a = actual.exceptionOrNull()!!
                assertEquals("$label: site2 exception class", e::class, a::class)
                assertEquals("$label: site2 exception message", e.message, a.message)
            }

            // site 3 (ack-barrier decision)
            assertEquals("$label: site3 flag", baseline.flagOrFalse(), captured.streamingFlagOrFalse())
        }
    }

    @Test
    fun readFailure_degradesAtFlagSitesAndEscapesAtTheFormalSite() {
        val boom: () -> String = { throw IOException("request.json gone") }
        val baseline = BaselineSites(boom)
        val captured = CapturedRequestBody.capture(boom)

        // sites 1/3 fold any failure into false (pre-fix runCatching default)
        assertEquals(false, baseline.flagOrFalse())
        assertEquals(false, captured.streamingFlagOrFalse())

        // site 2 let the IOException escape into the run's catch →
        // `model_use_failed`; the capture must reproduce that path
        val actual = runCatching { captured.jsonOrThrow() }
        assertTrue("formal site must fail", actual.isFailure)
        assertSame(
            "formal site must raise the same IOException the old readText() raised",
            IOException::class.java,
            actual.exceptionOrNull()!!::class.java,
        )
        assertEquals("request.json gone", actual.exceptionOrNull()!!.message)

        // ...and site 3 still degrades instead of throwing
        assertEquals(false, captured.streamingFlagOrFalse())
    }

    @Test
    fun malformedBody_throwsJsonExceptionAtTheFormalSiteRatherThanAFallback() {
        val captured = CapturedRequestBody.capture { """{"streaming":tru""" }

        assertEquals(false, captured.streamingFlagOrFalse())
        try {
            captured.jsonOrThrow()
            fail("malformed body must escape as JSONException (→ model_use_failed)")
        } catch (expected: JSONException) {
            // expected: same class the old site-2 JSONObject(...) raised
        }
    }

    // ---- 3) wiring: one read on disk, parsed body handed to both executors ----

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

    private fun serviceSource(): String = readRepoFile(SERVICE_REL)

    @Test
    fun serviceSource_readsTheRequestFileExactlyOnce() {
        val src = serviceSource()

        assertEquals(
            "request.json must be read from disk once per run",
            1,
            Regex(Regex.escape("requestFile.readText()")).findAll(src).count(),
        )
        assertTrue(src.contains("CapturedRequestBody.capture { requestFile.readText() }"))
    }

    @Test
    fun serviceSource_hasNoLeftoverPerSiteReadsOrParses() {
        val src = serviceSource()

        assertEquals(
            "the old per-site local must be gone",
            0,
            Regex("\\brequestText\\b").findAll(src).count(),
        )
        assertEquals(
            "the old per-site parse of the request file must be gone",
            0,
            Regex("JSONObject\\(requestFile\\.readText\\(\\)\\)").findAll(src).count(),
        )
        assertEquals(
            "all three sites must go through the capture",
            3,
            Regex(Regex.escape("requestBody.")).findAll(src).count(),
        )
    }

    @Test
    fun serviceSource_handsTheAlreadyParsedBodyToBothExecutors() {
        val src = serviceSource()

        assertTrue(src.contains("val req = requestBody.jsonOrThrow()"))
        // [T-worker-key-intent-handoff] The executors take the carried API key as
        // a third argument. The criterion is unchanged — the FIRST argument is
        // still the already-parsed body — so the pin is a prefix match rather
        // than a full signature (a run function that took the raw String would
        // still fail here and in modelsvc_single_read_check).
        assertTrue(Regex("executeStreamingRun\\(req, dir[,)]").containsMatchIn(src))
        assertTrue(Regex("executeRun\\(req, dir[,)]").containsMatchIn(src))
        assertTrue(
            Regex("private fun executeRun\\(req: JSONObject, dir: File[,)]").containsMatchIn(src),
        )
        assertTrue(
            Regex("private fun executeStreamingRun\\(req: JSONObject, dir: File[,)]")
                .containsMatchIn(src),
        )
        assertEquals(
            "neither executor may re-parse the body",
            0,
            Regex("JSONObject\\(req\\b").findAll(src).count(),
        )
    }

    private companion object {
        const val SERVICE_REL =
            "app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"
    }
}
