package com.rikkaminis.app.provider.thinking

import com.rikkaminis.app.data.model.ThinkingLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [T-android-effort-self-learn] Pins the parser that turns a gateway's 400 body into a
 * tier set, the on-disk round trip, and the precedence that makes the learned set win
 * over both the static table and the catalog declaration.
 *
 * The parser tests are the load-bearing ones: a wrong parse silently corrupts every
 * request for that host+model until the entry expires, and it can only be reached by
 * an actual 400, so there is no other place where a typo in this regex-shaped logic
 * would surface itself.
 *
 * Fixture for `record()` is the exact shape probed live on `token.sensenova.cn`
 * 2026-09-23 — the body that motivated this class.
 */
class EffortTierLearnerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        // UUID rather than nanoTime: the suite runs in well under a second, and a
        // colliding directory name would leak one test's learned entries into the next.
        dir = File(System.getProperty("java.io.tmpdir"), "etl-test-${java.util.UUID.randomUUID()}")
        EffortTierLearner.initFile(File(dir, "gateway_efforts.json"))
    }

    @After
    fun tearDown() {
        EffortTierLearner.reset()
        dir.deleteRecursively()
    }

    // ── enumFromText ─────────────────────────────────────────────────────────────

    @Test
    fun `parses the shape probed live on sensenova`() {
        val body = """{"error":{"message":"field ReasoningEffort invalid, should be one of: low, medium, high, xhigh, none"}}"""
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "none"),
            EffortTierLearner.enumFromText(body),
        )
    }

    @Test
    fun `survives prose around the list`() {
        val body = "reasoning_effort must be one of the following values: low, medium, high"
        assertEquals(listOf("low", "medium", "high"), EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `rejects a one-of clause about some other field`() {
        // Without this guard, a `max_tokens must be one of: ...` style body would plant a
        // bogus tier set under the model and clamp every later request.
        val body = """{"error":"max_tokens must be one of: 1024, 2048, 4096"}"""
        assertNull(EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `rejects a body with no one-of clause`() {
        val body = """{"error":"reasoning_effort is not supported by this model"}"""
        assertNull(EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `rejects empty and vocabulary-free input`() {
        assertNull(EffortTierLearner.enumFromText(""))
        assertNull(EffortTierLearner.enumFromText("the request failed"))
        assertNull(EffortTierLearner.enumFromText("effort should be one of: unknown, vocabulary"))
    }

    @Test
    fun `deduplicates keeping first occurrence order`() {
        val body = "reasoning effort should be one of: low, high, low, medium, low"
        assertEquals(listOf("low", "high", "medium"), EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `is case insensitive on both the trigger and the tiers`() {
        val body = "REASONING_EFFORT invalid, should be ONE OF: Low, MEDIUM"
        assertEquals(listOf("low", "medium"), EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `ignores words outside the known vocabulary`() {
        // "the following" and "or" must not be mistaken for tiers.
        val body = "effort must be one of the following: low, or none"
        assertEquals(listOf("low", "none"), EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `rejects prose that merely contains tier words near a one-of clause`() {
        // `low`/`high`/`medium`/`none`/`max` are ordinary English words. Without the
        // list-shape requirement this sentence was learned as [high, medium], which
        // pins the host+model to a two-tier ceiling for the whole TTL — a silent
        // downgrade with no 400 left to explain it.
        val body = """{"error":"reasoning_effort is required for this model. Upgrade is one of """ +
            """the recommended steps; a high traffic month can also produce this, medium load too."}"""
        assertNull(EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `stops the list at the first word that is not a tier or a connector`() {
        // A second clause about the same field must not be folded into the first —
        // otherwise the learned ceiling silently becomes the union of two enums.
        val body = "effort must be one of: low, medium; temperature must be one of: high"
        assertEquals(listOf("low", "medium"), EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `learns from the enum clause when an unrelated one-of clause comes first`() {
        // Anchoring on the FIRST "one of" alone discards the answer whenever the body
        // leads with some other field's enum; each anchor is tried in turn.
        val body = """{"error":"stream must be one of true,false. reasoning_effort must be """ +
            """one of: low, medium, high"}"""
        assertEquals(listOf("low", "medium", "high"), EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `accepts a quoted and bracketed list`() {
        val body = """reasoning_effort must be one of: ["low", "medium", "high"]"""
        assertEquals(listOf("low", "medium", "high"), EffortTierLearner.enumFromText(body))
    }

    @Test
    fun `accepts a list whose line breaks arrive as JSON escapes`() {
        // The SSE hook records `event.toString()`, which turns a newline inside the
        // gateway's message into a literal backslash-n. Read as text, that escape
        // letter would otherwise look like a word and end the list before it starts.
        val body = """{"error":{"message":"reasoning_effort must be one of:\n low,\n high"}}"""
        assertEquals(listOf("low", "high"), EffortTierLearner.enumFromText(body))
    }

    // ── ceilingFromText (thinking-level-unsupported) ─────────────────────────────

    /**
     * The exact body the local gateway returned on 2026-11-18 for
     * `antigravity/gemini-4-hydrogen-sonar` — see
     * `logs/minis-2026-11-18.log` [T321] ← HTTP 400.
     */
    private val thinkingLevel400 =
        """{"error":{"message":"Thinking level is unsupported: THINKING_LEVEL_XHIGH","type":"api_error"}}"""

    @Test
    fun `reads the rejected tier out of the thinking-level-unsupported shape`() {
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high"),
            EffortTierLearner.ceilingFromText(thinkingLevel400),
        )
    }

    @Test
    fun `the learned ceiling is what makes the retry clamp instead of 400 again`() {
        val host = "127.0.0.1"
        val model = "gemini-4-hydrogen-sonar"
        val learned = EffortTierLearner.record(host, """{"model":"$model"}""", thinkingLevel400)

        assertEquals(listOf("none", "minimal", "low", "medium", "high"), learned)
        assertEquals(listOf("none", "minimal", "low", "medium", "high"),
            EffortTierLearner.learned(host, model))
        // The pinned outcome: the tier that 400'd comes back as the strongest legal one
        // below it. Without the ceiling parse this assertion fails with "xhigh" — i.e.
        // exactly the request the retry used to re-send verbatim.
        assertEquals("high", ThinkingEffortLadder.clampEffort("xhigh", learned))
        assertEquals(
            "high",
            ThinkingEffortLadder.clampEffort(ThinkingEffortLadder.wireEffort(ThinkingLevel.XHIGH), learned),
        )
    }

    @Test
    fun `a rejected middle tier keeps every weaker tier legal`() {
        val body = """{"error":"Thinking level is unsupported: THINKING_LEVEL_MEDIUM"}"""
        assertEquals(listOf("none", "minimal", "low"), EffortTierLearner.ceilingFromText(body))
    }

    @Test
    fun `accepts the camelCase and spaced spellings`() {
        assertEquals(listOf("none", "minimal", "low"),
            EffortTierLearner.ceilingFromText("""{"error":"thinkingLevel: medium is unsupported"}"""))
        assertEquals(listOf("none", "minimal", "low"),
            EffortTierLearner.ceilingFromText("thinking level: medium is not supported"))
        assertEquals(listOf("none", "minimal", "low"),
            EffortTierLearner.ceilingFromText("invalid thinking_level value: MEDIUM"))
        // The tier can also sit behind a connector ("level of HIGH"): the scan skips
        // filler words, so the anchor still lands on the tier instead of on "of".
        assertEquals(listOf("none", "minimal", "low", "medium"),
            EffortTierLearner.ceilingFromText("Thinking level of HIGH is not supported"))
    }

    @Test
    fun `a thinking-level complaint that names no tier teaches nothing`() {
        // Both are real shapes: Gemini answers this when the model has no reasoning at
        // all. There is no answer in the sentence, so nothing may be learned — a guess
        // here would pin the model to a ceiling nobody measured.
        assertNull(EffortTierLearner.ceilingFromText("""{"error":"Thinking level is not supported for this model"}"""))
        assertNull(EffortTierLearner.ceilingFromText("""{"error":"thinking_level is unsupported"}"""))
    }

    @Test
    fun `ignores an unsupported complaint about another field`() {
        // "high" / "max" are ordinary English words again: the domain word gate is what
        // keeps a temperature complaint from being learned as an effort ceiling.
        assertNull(EffortTierLearner.ceilingFromText("""{"error":"temperature high is unsupported for this model"}"""))
        assertNull(EffortTierLearner.ceilingFromText("""{"error":"max_tokens 64000 is unsupported on level cheap"}"""))
        // This one has every ingredient except the domain word: a marker, a `level`
        // anchor and a real tier right behind it. Only the thinking/effort/reasoning
        // gate keeps it from becoming a ceiled entry for the model.
        assertNull(EffortTierLearner.ceilingFromText("""{"error":"compression level high is unsupported"}"""))
    }

    @Test
    fun `ignores a body with no rejection marker`() {
        // Mentions the tier and the word "level" but never says the tier was refused.
        assertNull(EffortTierLearner.ceilingFromText("""{"error":"Thinking level XHIGH requested"}"""))
    }

    @Test
    fun `an unknown tier name teaches nothing`() {
        assertNull(EffortTierLearner.ceilingFromText("""{"error":"Thinking level is unsupported: THINKING_LEVEL_DEFAULT"}"""))
        // "none" is the weakest tier: nothing is strictly below it, so no ceiling exists.
        assertNull(EffortTierLearner.ceilingFromText("""{"error":"Thinking level is unsupported: THINKING_LEVEL_NONE"}"""))
    }

    @Test
    fun `a later enum replaces the learned ceiling`() {
        EffortTierLearner.record("127.0.0.1", """{"model":"$model"}""", thinkingLevel400)
        EffortTierLearner.record("127.0.0.1", """{"model":"$model"}""",
            """{"error":"field ReasoningEffort invalid, should be one of: low, high"}""")
        // The fuller truth wins — the ceiling was only a guess at the legal set.
        assertEquals(listOf("low", "high"), EffortTierLearner.learned("127.0.0.1", model))
    }

    @Test
    fun `a write from the other process is visible immediately`() {
        EffortTierLearner.record(host, """{"model":"$model"}""",
            """{"error":"effort should be one of: low"}""")
        assertEquals(listOf("low"), EffortTierLearner.learned(host, model))

        // [fix/thinking-level-ceiling-1118] The chat provider runs in :modelservice, so
        // the 400 that teaches a ceiling is learned THERE and read HERE. A blind
        // time-based cache made this process keep serving the old set — which is how
        // the composer's picker could update while the navbar badge still said XHigh.
        // The payload is longer on purpose: the fingerprint is (mtime, size), so this
        // holds even when both writes land in the same millisecond.
        File(dir, "gateway_efforts.json").writeText(
            """{"entries":{"$host/$model":{"tiers":["low","medium","high"],"at":${System.currentTimeMillis()}}}}""",
        )
        assertEquals(listOf("low", "medium", "high"), EffortTierLearner.learned(host, model))
    }

    // ── modelIdFrom ──────────────────────────────────────────────────────────────

    @Test
    fun `reads the model field from a chat request body`() {
        val body = """{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"hi"}]}"""
        assertEquals("deepseek-v4-pro", EffortTierLearner.modelIdFrom(body))
    }

    @Test
    fun `rejects a missing model field`() {
        assertNull(EffortTierLearner.modelIdFrom("""{"messages":[]}"""))
        assertNull(EffortTierLearner.modelIdFrom("""{"model":""}"""))
    }

    @Test
    fun `rejects a malformed body instead of throwing`() {
        assertNull(EffortTierLearner.modelIdFrom("not json at all"))
        assertNull(EffortTierLearner.modelIdFrom(""))
    }

    @Test
    fun `rejects an unreasonably long model id`() {
        val body = """{"model":"${"x".repeat(300)}"}"""
        assertNull(EffortTierLearner.modelIdFrom(body))
    }

    // ── record / learned round trip ──────────────────────────────────────────────

    private val host = "token.sensenova.cn"
    private val model = "sensenova-6.8-flash-lite"

    @Test
    fun `record then learned round trips through disk`() {
        val learned = EffortTierLearner.record(host, """{"model":"$model"}""",
            """{"error":{"message":"effort should be one of: low, medium, high"}}""")
        assertEquals(listOf("low", "medium", "high"), learned)
        assertEquals(listOf("low", "medium", "high"), EffortTierLearner.learned(host, model))
    }

    @Test
    fun `record returns null when the body holds no enum`() {
        assertNull(EffortTierLearner.record(host, """{"model":"$model"}""", """{"error":"boom"}"""))
        assertNull(EffortTierLearner.record(host, """{"messages":[]}""",
            """{"error":"effort should be one of: low, medium"}"""))
        assertNull(EffortTierLearner.record("", """{"model":"$model"}""",
            """{"error":"effort should be one of: low, medium"}"""))
    }

    @Test
    fun `a learned set is scoped to host AND model`() {
        EffortTierLearner.record(host, """{"model":"$model"}""",
            """{"error":"effort should be one of: low, medium"}""")
        assertEquals(listOf("low", "medium"), EffortTierLearner.learned(host, model))
        assertNull(EffortTierLearner.learned("api.sensenova.cn", model))
        assertNull(EffortTierLearner.learned(host, "glm-5.2"))
        // A trailing slash must not create a second key for the same host.
        assertEquals(listOf("low", "medium"), EffortTierLearner.learned("$host/", model))
    }

    @Test
    fun `a later learning replaces the earlier entry`() {
        EffortTierLearner.record(host, """{"model":"$model"}""",
            """{"error":"effort should be one of: low, medium, high, xhigh"}""")
        EffortTierLearner.record(host, """{"model":"$model"}""",
            """{"error":"effort should be one of: low, medium"}""")
        assertEquals(listOf("low", "medium"), EffortTierLearner.learned(host, model))
    }

    @Test
    fun `survives a process restart and an in-process cache reset`() {
        EffortTierLearner.record(host, """{"model":"$model"}""",
            """{"error":"effort should be one of: low, xhigh"}""")
        // Simulates the other process having no idea what was just written.
        EffortTierLearner.reset()
        EffortTierLearner.initFile(File(dir, "gateway_efforts.json"))
        assertEquals(listOf("low", "xhigh"), EffortTierLearner.learned(host, model))
    }

    @Test
    fun `learned before init returns null rather than throwing`() {
        EffortTierLearner.reset()
        assertNull(EffortTierLearner.learned(host, model))
        assertEquals(emptyList<String>(), EffortTierLearner.learnedKeys())
    }

    @Test
    fun `a corrupt file yields no tiers instead of a crash`() {
        val f = File(dir, "gateway_efforts.json")
        f.parentFile?.mkdirs()
        f.writeText("{not json")
        EffortTierLearner.reset()
        EffortTierLearner.initFile(f)
        assertNull(EffortTierLearner.learned(host, model))
    }

    // ── precedence inside resolveTier ────────────────────────────────────────────

    private val hostModel = "sensenova-6.8-flash-lite"

    @Test
    fun `learned outranks the static table`() {
        // Without anything learned, MAX clamps onto the static table's "xhigh".
        EffortTierLearner.reset()
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(host, hostModel, ThinkingLevel.MAX, null))

        EffortTierLearner.initFile(File(dir, "gateway_efforts.json"))
        EffortTierLearner.record(host, """{"model":"$hostModel"}""",
            """{"error":"effort should be one of: none, low, medium"}""")

        // The gateway's own complaint wins, even though the catalog declares high+max.
        assertEquals(
            "medium",
            GatewayEffortTruth.resolveTier(host, hostModel, ThinkingLevel.MAX, listOf("high", "max")),
        )
    }

    @Test
    fun `with nothing learned behaviour is unchanged`() {
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(host, hostModel, ThinkingLevel.MAX, null))
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(host, hostModel, ThinkingLevel.MAX, listOf("high", "max")))
        // An unmeasured model on a measured host still falls back — and the fallback's
        // ceiling is xhigh, so MAX degrades there rather than being sent verbatim.
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(host, "brand-new-model", ThinkingLevel.MAX, null))
    }
}
