package com.rikkaminis.app.provider.thinking

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [fix/effort-key-1119] The two halves of the learned-effort scope.
 *
 * **F-01 — the key is an endpoint, not a host.** `EffortTierLearner` used to key
 * its entries by `HttpUrl.host`, which carries neither the port nor the path prefix.
 * Two instances of one gateway therefore shared one entry: the tier set instance A
 * learned from a 400 was then applied to instance B's requests. [EffortTierLearner.scopeOf]
 * normalizes a base URL into `scheme://host[:port]/path-prefix`, and the learner's
 * [EffortTierLearner.record] / [EffortTierLearner.learned] both go through it, so
 * the write side and the read side cannot disagree.
 *
 * **F-02 — a learned ceiling is a ceiling, not a whitelist.** A `THINKING_LEVEL_XHIGH`
 * rejection yields the whole ladder PREFIX below xhigh, and that prefix was previously
 * handed to [ThinkingEffortLadder.clampEffort] in place of the model's declared set —
 * putting a tier back on the wire that the model never declared (a guaranteed 400, the
 * very 400 the entry was learned from). [ThinkingEffortLadder.learnedWithinDeclared]
 * narrows the learned set by the declaration instead.
 *
 * Every expectation below is a literal written by hand: the F-01 pair is asserted as
 * two DIFFERENT scopes (a single-scope assertion would pass even if normalization were
 * a no-op), and the F-02 pair is asserted through [ThinkingEffortLadder.clampEffort],
 * i.e. through the value that actually goes on the wire.
 */
class EffortScopeDeclaredSetTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        // UUID rather than nanoTime: the suite runs in well under a second, and a
        // colliding name would leak one test's learned entries into the next.
        dir = File(System.getProperty("java.io.tmpdir"), "etl-scope-${java.util.UUID.randomUUID()}")
        file = File(dir, "gateway_efforts.json")
        EffortTierLearner.initFile(file)
    }

    @After
    fun tearDown() {
        EffortTierLearner.reset()
        dir.deleteRecursively()
    }

    // ── F-01: scopeOf — the key prefix ───────────────────────────────────────────

    @Test
    fun `the scope keeps the scheme host port and path prefix`() {
        // A scope that dropped the port or the prefix is the pre-fix bug in one line.
        assertEquals("http://127.0.0.1:8790/v1", EffortTierLearner.scopeOf("http://127.0.0.1:8790/v1"))
        assertEquals(
            "https://gateway.example.com/openai/v1",
            EffortTierLearner.scopeOf("https://gateway.example.com/openai/v1"),
        )
        // The port and the path are what a host-only key lost.
        assertEquals(
            "http://127.0.0.1:8790/v1",
            EffortTierLearner.scopeOf("http://127.0.0.1:8790/v1"),
        )
    }

    @Test
    fun `the scope is canonicalized - lowercase host, no trailing slash, no query`() {
        assertEquals("https://api.openai.com/v1", EffortTierLearner.scopeOf("HTTPS://Api.OpenAI.com/v1/"))
        assertEquals("https://api.openai.com/v1", EffortTierLearner.scopeOf("https://api.openai.com/v1?api-version=2024"))
        assertEquals("https://api.openai.com/v1", EffortTierLearner.scopeOf("https://api.openai.com/v1#frag"))
        // A trailing slash is not part of the endpoint, and no endpoint is just a slash.
        assertEquals("https://api.openai.com/v1", EffortTierLearner.scopeOf("https://api.openai.com/v1///"))
    }

    @Test
    fun `a bare host keeps its legacy scope`() {
        // Legacy callers (and entries already on disk) key by the host alone; the
        // normalizer must not invent a scope for them, or every old entry goes blind.
        assertEquals("api.sensenova.cn", EffortTierLearner.scopeOf("api.sensenova.cn"))
        assertEquals("api.sensenova.cn", EffortTierLearner.scopeOf("api.sensenova.cn/"))
    }

    @Test
    fun `a string that is not an endpoint has no scope`() {
        assertNull(EffortTierLearner.scopeOf(""))
        assertNull(EffortTierLearner.scopeOf("   "))
        assertNull(EffortTierLearner.scopeOf("https://"))
        assertNull(EffortTierLearner.scopeOf("https:///v1"))
    }

    @Test
    fun `the stored key is the normalized endpoint, not the raw argument`() {
        // The two "two ports" / "two prefixes" cases above only prove that different
        // scopes do not collide — they pass even when key() does NO normalization,
        // because the write and the read then agree on the same raw string. This pins
        // the key SHAPE itself, which is what actually keeps `HttpUrl.host` from
        // creeping back in.
        val model = "gemini-4-hydrogen-sonar"
        EffortTierLearner.record("HTTP://127.0.0.1:8790/v1/?x=1", """{"model":"$model"}""", thinkingLevel400)
        assertEquals(
            listOf("http://127.0.0.1:8790/v1/$model"),
            EffortTierLearner.learnedKeys(),
        )
    }

    @Test
    fun `two ports of one host are two scopes`() {
        assertNotEquals(
            EffortTierLearner.scopeOf("http://127.0.0.1:8790/v1"),
            EffortTierLearner.scopeOf("http://127.0.0.1:8791/v1"),
        )
    }

    @Test
    fun `two path prefixes of one host are two scopes`() {
        assertNotEquals(
            EffortTierLearner.scopeOf("https://gateway.example.com/v1"),
            EffortTierLearner.scopeOf("https://gateway.example.com/openai/v1"),
        )
    }

    // ── F-01: the round trip — write side and read side agree ───────────────────

    private val thinkingLevel400 =
        """{"error":{"message":"Thinking level is unsupported: THINKING_LEVEL_XHIGH","type":"api_error"}}"""

    private val ceiling = listOf("none", "minimal", "low", "medium", "high")

    @Test
    fun `an entry learned on one port is not read from another port`() {
        val model = "gemini-4-hydrogen-sonar"
        assertEquals(ceiling, EffortTierLearner.record("http://127.0.0.1:8790/v1", """{"model":"$model"}""", thinkingLevel400))

        // The pin: instance B reads its own (empty) entry, not A's ceiling.
        assertNull(EffortTierLearner.learned("http://127.0.0.1:8791/v1", model))
        assertEquals(ceiling, EffortTierLearner.learned("http://127.0.0.1:8790/v1", model))
    }

    @Test
    fun `an entry learned on one path prefix is not read from another prefix`() {
        val model = "gemini-4-hydrogen-sonar"
        EffortTierLearner.record("https://gateway.example.com/v1", """{"model":"$model"}""", thinkingLevel400)

        assertNull(EffortTierLearner.learned("https://gateway.example.com/openai/v1", model))
        assertEquals(ceiling, EffortTierLearner.learned("https://gateway.example.com/v1", model))
    }

    @Test
    fun `recording a raw base URL and reading the normalized scope agree`() {
        // The caller hands the learner the raw base URL; the key is normalized on both
        // sides. A write that stored one spelling and a read that asked for another
        // would silently throw away every learning.
        val model = "gemini-4-hydrogen-sonar"
        EffortTierLearner.record("HTTPS://Api.OpenAI.com/v1/", """{"model":"$model"}""", thinkingLevel400)
        assertEquals(ceiling, EffortTierLearner.learned("https://api.openai.com/v1", model))
    }

    @Test
    fun `a legacy host-keyed entry is still readable through a host, not through a scope`() {
        // Migration note, pinned: entries written before this fix are keyed `host/model`.
        // They stay readable for host-keyed callers (GatewayEffortTruth's default),
        // and a scope-keyed read deliberately misses them — the ceiling is re-learned
        // on the next 400 rather than mis-applied to a different endpoint.
        val model = "legacy-model"
        file.parentFile?.mkdirs()
        file.writeText(
            """{"entries":{"api.sensenova.cn/$model":{"tiers":["low","medium"],"at":${System.currentTimeMillis()}}}}""",
        )
        EffortTierLearner.reset()
        EffortTierLearner.initFile(file)

        assertEquals(listOf("low", "medium"), EffortTierLearner.learned("api.sensenova.cn", model))
        assertNull(EffortTierLearner.learned("https://api.sensenova.cn/v1", model))
    }

    // ── F-02: learnedWithinDeclared — a ceiling narrowed by the declaration ────

    @Test
    fun `a declared set narrows the learned ladder prefix`() {
        // The exact shape of F-02: a `THINKING_LEVEL_XHIGH` complaint says "everything
        // weaker is legal", and this model declares only two of those tiers.
        assertEquals(
            listOf("low", "high"),
            ThinkingEffortLadder.learnedWithinDeclared(ceiling, listOf("low", "high")),
        )
    }

    @Test
    fun `a tier the model never declared stays off the wire`() {
        // The behaviour the F-02 finding is about: `medium` is in the learned prefix
        // and was never in the declaration, so it must not be sent.
        val narrowed = ThinkingEffortLadder.learnedWithinDeclared(ceiling, listOf("low", "high"))
        assertEquals("low", ThinkingEffortLadder.clampEffort("medium", narrowed))
    }

    @Test
    fun `the wire value for an undeclared request degrades downward, not upward`() {
        // End-to-end through the real storage path: the ceiling is learned from an
        // actual 400 body, narrowed by the declaration, then clamped. `medium` is in
        // the learned prefix but NOT declared, so it must land on `low`; with the raw
        // prefix it would go out verbatim (that is F-02).
        val scope = "https://gw.example/v1"
        EffortTierLearner.record(scope, """{"model":"m5"}""", thinkingLevel400)
        val narrowed = EffortTierLearner.learnedWithin(scope, "m5", listOf("low", "high"))
        assertEquals(listOf("low", "high"), narrowed)
        assertEquals("low", ThinkingEffortLadder.clampEffort("medium", narrowed))
        assertEquals("high", ThinkingEffortLadder.clampEffort("xhigh", narrowed))
        assertEquals("high", ThinkingEffortLadder.clampEffort("high", narrowed))
    }

    @Test
    fun `with nothing declared the learned ceiling is passed through`() {
        // A model the catalog never heard of still gets the full weight of the
        // gateway's own answer — that is the pre-fix behaviour and it must survive.
        assertEquals(ceiling, ThinkingEffortLadder.learnedWithinDeclared(ceiling, null))
        assertEquals(ceiling, ThinkingEffortLadder.learnedWithinDeclared(ceiling, emptyList()))
    }

    @Test
    fun `with nothing learned the declaration is passed through`() {
        assertEquals(listOf("low", "high"), ThinkingEffortLadder.learnedWithinDeclared(null, listOf("low", "high")))
        assertEquals(listOf("low", "high"), ThinkingEffortLadder.learnedWithinDeclared(emptyList(), listOf("low", "high")))
    }

    @Test
    fun `a contradiction degrades to the weakest declared tier`() {
        // learned says "low only", declared says "high and max": disjoint. The result
        // must still be a DECLARED tier — putting a tier the model never declared on
        // the wire is the guaranteed 400 this whole function exists to prevent — and it
        // must be exactly one tier (the weakest), not the whole declaration, so the
        // request stays as low as the model allows. The next 400 teaches us again.
        val narrowed = ThinkingEffortLadder.learnedWithinDeclared(listOf("low"), listOf("high", "max"))
        assertEquals(listOf("high"), narrowed)
        assertEquals("high", ThinkingEffortLadder.clampEffort("max", narrowed))
    }

    @Test
    fun `a declaration naming an off-ladder word never wins the weakest pick`() {
        // clampEffort ignores words it does not know; if the fallback returned only such
        // a word the request would go out unclamped instead of degrading.
        assertEquals(
            listOf("low"),
            ThinkingEffortLadder.learnedWithinDeclared(listOf("medium"), listOf("zzz", "low")),
        )
    }

    @Test
    fun `the narrowed set is ordered by the ladder regardless of the input order`() {
        assertEquals(
            listOf("low", "high"),
            ThinkingEffortLadder.learnedWithinDeclared(listOf("high", "low"), listOf("low", "high")),
        )
        assertEquals(
            listOf("low", "high"),
            ThinkingEffortLadder.learnedWithinDeclared(listOf("low", "high"), listOf("high", "low")),
        )
    }

    @Test
    fun `a declaration that repeats a tier does not duplicate it`() {
        assertEquals(
            listOf("low", "high"),
            ThinkingEffortLadder.learnedWithinDeclared(ceiling, listOf("low", "low", "high")),
        )
    }

    // ── F-02: learnedWithin — only a CEILING is narrowed ───────────────────────

    private fun learnedFile(entries: String): File {
        file.parentFile?.mkdirs()
        file.writeText("""{"entries":{$entries}}""")
        EffortTierLearner.reset()
        EffortTierLearner.initFile(file)
        return file
    }

    @Test
    fun `a ceiling-shaped answer is narrowed by the declaration`() {
        // Written by a THINKING_LEVEL_XHIGH rejection: the ladder prefix below xhigh.
        EffortTierLearner.record("https://gw.example/v1", """{"model":"m1"}""", thinkingLevel400)
        assertEquals(
            listOf("low", "high"),
            EffortTierLearner.learnedWithin("https://gw.example/v1", "m1", listOf("low", "high")),
        )
        // Without a declaration the ceiling is used as-is.
        assertEquals(
            ceiling,
            EffortTierLearner.learnedWithin("https://gw.example/v1", "m1", null),
        )
    }

    @Test
    fun `an enumerated answer is NOT narrowed - it is wider than the catalog on purpose`() {
        // The reason this class exists: sensenova under-declares. The gateway said
        // `one of: low, high, xhigh`; the catalog only knows `low`. Narrowing would
        // throw away the one-hand truth and re-400 on the very tier it taught us.
        EffortTierLearner.record(
            "https://gw.example/v1", """{"model":"m2"}""",
            """{"error":{"message":"field ReasoningEffort invalid, should be one of: low, high, xhigh"}}""",
        )
        assertEquals(
            listOf("low", "high", "xhigh"),
            EffortTierLearner.learnedWithin("https://gw.example/v1", "m2", listOf("low")),
        )
    }

    @Test
    fun `nothing learned returns the declaration untouched`() {
        assertEquals(
            listOf("low", "high"),
            EffortTierLearner.learnedWithin("https://gw.example/v1", "never-seen", listOf("low", "high")),
        )
        assertNull(EffortTierLearner.learnedWithin("https://gw.example/v1", "never-seen", null))
    }

    @Test
    fun `an entry written before the shape field existed is treated as an enum`() {
        // Upgrade path: the old file has no `shape`. Treating it as a CEILING would
        // start narrowing sets that were never narrowed, silently dropping tiers on
        // every device that upgrades. ENUM = "behaviour identical to pre-fix".
        val model = "legacy-shape"
        learnedFile(
            """"https://gw.example/v1/$model":{"tiers":["low","high","xhigh"],"at":${System.currentTimeMillis()}}""",
        )
        assertEquals(
            listOf("low", "high", "xhigh"),
            EffortTierLearner.learnedWithin("https://gw.example/v1", model, listOf("low")),
        )
    }

    @Test
    fun `a later enum replaces a learned ceiling and stops being narrowed`() {
        // The fuller truth wins: once the gateway spells out the set, the narrowing
        // (which exists only because a ceiling is a guess) must stop applying.
        val scope = "https://gw.example/v1"
        EffortTierLearner.record(scope, """{"model":"m3"}""", thinkingLevel400)
        assertEquals(
            listOf("low", "high"),
            EffortTierLearner.learnedWithin(scope, "m3", listOf("low", "high")),
        )
        EffortTierLearner.record(
            scope, """{"model":"m3"}""",
            """{"error":"field ReasoningEffort invalid, should be one of: low, medium, high, xhigh"}""",
        )
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            EffortTierLearner.learnedWithin(scope, "m3", listOf("low", "high")),
        )
    }

    @Test
    fun `the shape survives a restart`() {
        val scope = "https://gw.example/v1"
        EffortTierLearner.record(scope, """{"model":"m4"}""", thinkingLevel400)
        EffortTierLearner.reset()
        EffortTierLearner.initFile(file)
        assertEquals(
            listOf("low", "high"),
            EffortTierLearner.learnedWithin(scope, "m4", listOf("low", "high")),
        )
    }

    // ── wiring: both wire paths key on the scope and narrow by the declaration ──
    //
    // The pure functions above can be correct while the call sites still pass a bare
    // host and still hand `clampEffort` the raw prefix. These pin the wiring on the
    // two places the wire value is decided; comments are stripped so a KDoc mention
    // cannot satisfy them.

    private val OPENAI = "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt"
    private val TRUTH = "src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/GatewayEffortTruth.kt"
    private val LEARNER = "src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/EffortTierLearner.kt"

    private fun code(relative: String): String = stripComments(readRepoFile(relative))

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    sb.append(c)
                }
                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i += 1
                }
                inString -> {
                    sb.append(c)
                    if (c == '\\') {
                        sb.append(next)
                        i += 1
                    } else if (c == '"') {
                        inString = false
                    }
                }
                else -> when {
                    c == '"' -> {
                        inString = true
                        sb.append(c)
                    }
                    c == '/' && next == '/' -> {
                        inLine = true
                        i += 1
                    }
                    c == '/' && next == '*' -> {
                        inBlock = true
                        i += 1
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    @Test
    fun `both wire paths read the learner through the shape-aware narrowing`() {
        val openai = code(OPENAI)
        // Chat Completions (ctx.declaredEffortValues) and Responses
        // (learnedEffortValues) both ask the learner for a set that is already
        // narrowed when — and only when — the learned answer was a CEILING.
        assertEquals(
            "expected exactly the two wire paths to read the learner",
            2,
            Regex("EffortTierLearner\\.learnedWithin\\(").findAll(openai).count(),
        )
        assertFalse(
            "the Chat Completions path still feeds the raw learned set to the resolver",
            "declaredEffortValues = EffortTierLearner.learned(" in openai,
        )
        assertFalse(
            "the Responses path still feeds the raw learned set to the clamp",
            "learnedEffortValues = EffortTierLearner.learned(" in openai,
        )
        // Both must hand over the model's declaration: without it the narrowing has
        // nothing to narrow against.
        assertTrue(
            "the Chat Completions read must pass the declaration",
            "scope, model.id, model.reasoningEffortValues," in openai,
        )
        assertTrue(
            "the Responses read must pass the declaration",
            "model.reasoningEffortValues,\n                )," in openai,
        )
    }

    @Test
    fun `the learner is read through the endpoint scope on every call site`() {
        val openai = code(OPENAI)
        // The two record sites (HTTP 400 and the inline SSE error).
        assertEquals(
            "both record sites must key on the endpoint scope, not on request.url.host",
            2,
            Regex("EffortTierLearner\\.record\\(effortScope \\?: request\\.url\\.host").findAll(openai).count(),
        )
        assertFalse(
            "the Chat Completions path still reads the learner by bare host",
            "EffortTierLearner.learned(host, model.id)" in openai,
        )
        assertTrue(
            "the Responses path must read the learner by scope",
            "effortScope ?: basePath.toHttpUrlOrNull()?.host ?: \"\"" in openai,
        )
        assertTrue(
            "the Chat Completions path must read the learner by scope",
            "EffortTierLearner.learnedWithin(\n                scope, model.id," in openai,
        )
    }

    @Test
    fun `no production file still keys the learner by a bare host`() {
        // requestHost was the host-only surface; leaving it behind anywhere would keep
        // one read path blind to ports and path prefixes.
        for (rel in listOf(OPENAI, TRUTH, LEARNER, "src/android/app/src/main/java/com/rikkaminis/app/provider/LLMProvider.kt")) {
            assertFalse("$rel still exposes requestHost", "requestHost" in code(rel))
        }
    }
}
