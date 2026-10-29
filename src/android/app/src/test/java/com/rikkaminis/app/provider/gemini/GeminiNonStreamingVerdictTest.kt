package com.rikkaminis.app.provider.gemini

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [R5-E2] Gemini's non-streaming wire decisions as PURE tables, so they are
 * provable without OkHttp/Android (the caller-visible half lives in
 * `GeminiNonStreamingWireTest`).
 *
 * E-C-02② — `sendMessageClamped` turned `200 {"error":{…}}` and `200` + no
 *   candidates into `LLMResponse("", "end_turn")`: a fabricated successful
 *   empty answer over a rate limit / a blocked prompt / a truncated relay body.
 * E-C-05② — `isOfficialGeminiBase` substring-matched the whole URL, so
 *   `https://relay.internal/generativelanguage.googleapis.com/v1beta` counted
 *   as Google and a relay's auth failure was answered with the static catalog.
 * E-F-06 — a turn whose `contentParts` was non-empty emitted no `imageParts` at
 *   all (both carriers were read only in the mutually exclusive `else` arm).
 *
 * Expected values are hand-written literals (never derived from the code under
 * test, see evidence-discipline): status codes, hostnames and the carrier rows
 * are re-stated from the contract.
 */
class GeminiNonStreamingVerdictTest {

    // -- E-C-02② : verdict table -------------------------------------------

    @Test
    fun `an error object inside a 200 body is an http-class failure`() {
        assertEquals(
            GeminiGenerateContentVerdict.HttpError(429),
            geminiGenerateContentVerdict(
                JSONObject("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}"""),
            ),
        )
        assertEquals(
            GeminiGenerateContentVerdict.HttpError(401),
            geminiGenerateContentVerdict(
                JSONObject("""{"error":{"code":0,"status":"UNAUTHENTICATED","message":"bad key"}}"""),
            ),
        )
        // Neither a plausible status nor a known enum: the stream arm's default.
        assertEquals(
            GeminiGenerateContentVerdict.HttpError(500),
            geminiGenerateContentVerdict(JSONObject("""{"error":{}}""")),
        )
    }

    @Test
    fun `a body without a candidate is not a completed turn`() {
        assertEquals(
            GeminiGenerateContentVerdict.EmptyCandidates(null),
            geminiGenerateContentVerdict(JSONObject("""{"candidates":[]}""")),
        )
        // relay truncation / empty body
        assertEquals(
            GeminiGenerateContentVerdict.EmptyCandidates(null),
            geminiGenerateContentVerdict(JSONObject("""{}""")),
        )
        assertEquals(
            GeminiGenerateContentVerdict.EmptyCandidates(blockReason = "SAFETY"),
            geminiGenerateContentVerdict(
                JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}"""),
            ),
        )
    }

    @Test
    fun `the blocked-prompt message names the block reason`() {
        val verdict = geminiGenerateContentVerdict(
            JSONObject("""{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}"""),
        ) as GeminiGenerateContentVerdict.EmptyCandidates
        assertTrue(verdict.describe().contains("PROHIBITED_CONTENT"))
        assertTrue(verdict.describe().contains("no candidates"))
    }

    @Test
    fun `a candidate with no text is still a completed turn`() {
        // Pure functionCall turn: text "" is legitimate, the tool block carries it.
        assertEquals(
            GeminiGenerateContentVerdict.Ok,
            geminiGenerateContentVerdict(
                JSONObject(
                    """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{}}}]},"finishReason":"STOP"}]}""",
                ),
            ),
        )
        // A candidate that only carries a non-STOP finish reason (blocked output).
        assertEquals(
            GeminiGenerateContentVerdict.Ok,
            geminiGenerateContentVerdict(
                JSONObject("""{"candidates":[{"finishReason":"SAFETY"}]}"""),
            ),
        )
    }

    // -- E-F-06 : the carrier table ----------------------------------------

    @Test
    fun `which images a structured turn still has to send`() {
        val a = LLMMessage.ImagePart(byteArrayOf(1, 2, 3, 4), "image/png")
        val b = LLMMessage.ImagePart(byteArrayOf(9, 9), "image/jpeg")

        // request-level images belong to the LAST user turn only
        assertEquals(
            listOf(a),
            geminiTurnExtraImages(
                isLastUserTurn = true,
                requestImages = listOf(a),
                messageImages = emptyList(),
                inlineImageCount = 0,
            ),
        )
        assertEquals(
            emptyList<LLMMessage.ImagePart>(),
            geminiTurnExtraImages(
                isLastUserTurn = false,
                requestImages = listOf(a),
                messageImages = emptyList(),
                inlineImageCount = 0,
            ),
        )
        // the message's own carrier stays on its own (older) turn
        assertEquals(
            listOf(b),
            geminiTurnExtraImages(
                isLastUserTurn = false,
                requestImages = listOf(a),
                messageImages = listOf(b),
                inlineImageCount = 0,
            ),
        )
        // an inlined ImageData already represents them: appending would double-send
        assertEquals(
            emptyList<LLMMessage.ImagePart>(),
            geminiTurnExtraImages(
                isLastUserTurn = true,
                requestImages = listOf(a),
                messageImages = listOf(a),
                inlineImageCount = 1,
            ),
        )
        // nothing to add
        assertEquals(
            emptyList<LLMMessage.ImagePart>(),
            geminiTurnExtraImages(
                isLastUserTurn = true,
                requestImages = emptyList(),
                messageImages = emptyList(),
                inlineImageCount = 0,
            ),
        )
    }

    // -- E-C-05 : host-based official-base test ----------------------------

    @Test
    fun `google hosts are official`() {
        assertTrue(isOfficialGeminiBase("https://generativelanguage.googleapis.com/v1beta"))
        assertTrue(isOfficialGeminiBase("https://cloudcode-pa.googleapis.com/v1beta"))
        // no scheme: a shape users paste into the custom-base field
        assertTrue(isOfficialGeminiBase("generativelanguage.googleapis.com/v1beta"))
        // trailing DNS dot and an explicit port
        assertTrue(isOfficialGeminiBase("https://generativelanguage.googleapis.com./v1beta"))
        assertTrue(isOfficialGeminiBase("https://generativelanguage.googleapis.com:443/v1beta"))
        assertTrue(isOfficialGeminiBase("HTTPS://GenerativeLanguage.GoogleAPIs.com/v1beta"))
    }

    @Test
    fun `a relay whose path contains a google host is still a relay`() {
        // The regression: substring matching on the whole URL said "official",
        // so a relay's auth/network failure was answered with Google's catalog.
        assertFalse(
            isOfficialGeminiBase("https://relay.internal/generativelanguage.googleapis.com/v1beta"),
        )
        assertFalse(isOfficialGeminiBase("https://relay.internal/cloudcode-pa.googleapis.com"))
        assertFalse(isOfficialGeminiBase("https://generativelanguage.googleapis.com.evil.tld/v1beta"))
        assertFalse(isOfficialGeminiBase("https://my-relay.example.com/v1beta"))
    }

    @Test
    fun `blank means the public endpoint and garbage is not official`() {
        assertTrue(isOfficialGeminiBase(null))
        assertTrue(isOfficialGeminiBase(""))
        assertTrue(isOfficialGeminiBase("   "))
        // unparseable → NOT official (fail towards preserving the user's rows)
        assertFalse(isOfficialGeminiBase("http://["))
        assertFalse(isOfficialGeminiBase("not a url at all"))
    }

    @Test
    fun `the host parser tolerates the shapes users really paste`() {
        assertEquals(
            "generativelanguage.googleapis.com",
            geminiBaseHost("generativelanguage.googleapis.com/v1beta"),
        )
        assertEquals(
            "generativelanguage.googleapis.com",
            geminiBaseHost("HTTPS://GenerativeLanguage.GoogleAPIs.com/v1beta"),
        )
        assertEquals(
            "relay.internal",
            geminiBaseHost("https://relay.internal/generativelanguage.googleapis.com/v1beta"),
        )
        assertEquals(null, geminiBaseHost("   "))
    }

    // -- wiring: the helpers above are what the production paths call ------

    private fun repoFile(relative: String): String {
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

    private val geminiProviderSrc by lazy {
        repoFile("src/android/app/src/main/java/com/rikkaminis/app/provider/gemini/GeminiProvider.kt")
    }
    private val geminiModelsSrc by lazy {
        repoFile("src/android/app/src/main/java/com/rikkaminis/app/provider/gemini/GeminiModelsApi.kt")
    }

    @Test
    fun `the non-streaming arm consults the verdict before parsing`() {
        val verdictAt = geminiProviderSrc.indexOf("geminiGenerateContentVerdict(json)")
        val parseAt = geminiProviderSrc.indexOf("val text = extractText(json)")
        assertTrue("sendMessageClamped does not consult the verdict", verdictAt > 0)
        assertTrue("extractText call site not found", parseAt > 0)
        assertTrue(
            "the verdict must gate extraction — otherwise a 200 error body still parses as an empty answer",
            verdictAt < parseAt,
        )
    }

    @Test
    fun `both carriers assemble images through one helper`() {
        // definition + the contentParts call site + the legacy call site
        assertEquals(
            "both branches must emit images through appendImageParts (two assembly shapes is the defect)",
            2,
            Regex("appendImageParts\\(parts, ").findAll(geminiProviderSrc).count(),
        )
        assertEquals(
            "the contentParts branch must consult the carrier table",
            2,
            Regex("geminiTurnExtraImages\\(").findAll(geminiProviderSrc).count(),
        )
    }

    @Test
    fun `the oauth auth fallback is gated on the official-base test`() {
        assertTrue(
            "a relay's 401/403 must not answer with the static Google catalog",
            "if (oauthCredential && isOfficialGeminiBase(baseUrl))" in geminiModelsSrc,
        )
        assertTrue(
            "the failure-path fallback still needs its own verdict",
            "isOfficialGeminiBase(baseUrl)" in geminiModelsSrc.substringAfter("val fallback"),
        )
    }
}
