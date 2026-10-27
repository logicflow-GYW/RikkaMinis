package com.rikkaminis.app.backup

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [D-C fix/backup-bootstrap-size] One cap, one unit.
 *
 * The bug: export compared `payload.length` (UTF‑16 code units) with
 * `MAX_PAYLOAD_BYTES` while the import side compared UTF‑8 BYTES
 * (`readSafBytes`, and the gate in `ConfigBackup.import`). For Chinese text
 * bytes/chars ≈ 1.08, so a 71,427,943‑byte / 66,092,088‑char backup passed the
 * export gate and was then permanently impossible to import — the user's only
 * backup, and the one case where a backup matters.
 *
 * What can be pinned from a JVM test:
 *  - the ruler itself ([ConfigBackup.utf8Length]) against an independent
 *    oracle (`String.toByteArray(UTF_8).size`) and against the boundary shape
 *    that broke (chars under the cap, bytes over it);
 *  - the chat packer charging BYTES (a CJK message that a char budget would
 *    keep is dropped by the byte budget);
 *  - the export size ladder ([ConfigBackup.buildWithinCap]) — decisions,
 *    ordering, bounded rounds, and that nothing over the cap is ever returned.
 *
 * What cannot: a real 64MiB document (no unit test should allocate one) and a
 * full `export()`/`import()` round trip (they need Android repositories — see
 * ConfigBackupPayloadTest's header). The ladder is therefore exercised through
 * its fake-builder seam, and "the produced document passes the importer's byte
 * gate" is asserted with [ConfigBackup.documentExceedsPayloadCap], the very
 * predicate `import()` calls.
 */
class ConfigBackupPayloadSizeTest {

    // ── The ruler ────────────────────────────────────────────────────────

    @Test
    fun `utf8Length matches the byte encoding, not the char count`() {
        // Oracle is java's own encoder — independent of the counter.
        for (sample in listOf(
            "",
            "abc",
            "é",              // 2 bytes, 1 char
            "中",             // 3 bytes, 1 char
            "中文abcé",        // mixed
            "😀",             // surrogate pair: 4 bytes, 2 chars
            "中😀a",
        )) {
            assertEquals(
                "byte length of ${sample.length}-char sample",
                sample.toByteArray(Charsets.UTF_8).size.toLong(),
                ConfigBackup.utf8Length(sample),
            )
        }
    }

    @Test
    fun `the repaired ruler keeps counting when chars and bytes diverge`() {
        // Explicit numbers: 3 CJK chars → 3 chars, 9 bytes.
        val s = "中中中"
        assertEquals(3, s.length)
        assertEquals(9L, ConfigBackup.utf8Length(s))
    }

    // ── The boundary shape that broke ────────────────────────────────────

    @Test
    fun `a document the char ruler accepts is rejected by the byte ruler`() {
        // 683 CJK chars = 2049 bytes. A 1024-byte cap: the pre-fix export gate
        // (chars) said "fits", the import gate (bytes) said "never".
        val document = "中".repeat(683)
        val capBytes = 1024L
        assertEquals(683, document.length)
        assertEquals(2049L, ConfigBackup.utf8Length(document))
        assertFalse(
            "the char ruler (export, pre-fix) accepts it — that is the bug",
            document.length.toLong() > capBytes,
        )
        assertTrue(
            "the byte ruler (import + export, post-fix) rejects it",
            ConfigBackup.documentExceedsPayloadCap(document, capBytes),
        )
    }

    @Test
    fun `the byte gate is exact on both sides of the cap`() {
        val capBytes = 1024L
        assertFalse(ConfigBackup.documentExceedsPayloadCap("a".repeat(1024), capBytes))
        assertTrue(ConfigBackup.documentExceedsPayloadCap("a".repeat(1025), capBytes))
        // Same char count, over the cap only because of the encoding.
        assertTrue(ConfigBackup.documentExceedsPayloadCap("中".repeat(342), capBytes))
        assertFalse(ConfigBackup.documentExceedsPayloadCap("中".repeat(341), capBytes))
    }

    // ── The chat packer charges bytes ────────────────────────────────────

    private fun message(text: String) = BudgetChatMessage(
        id = "m-1",
        sessionId = "s0",
        role = "user",
        partsJson = """[{"type":"text","value":"$text"}]""",
        createdAt = 101L,
        sortOrder = 1,
    )

    private fun sanitize(s: String?): String? = ConfigBackup.sanitizeChatParts(s)
    private fun capReasoning(s: String?): String? = ConfigBackup.capReasoningContent(s)

    /**
     * Serialized sizes the arms below depend on (hand-computed from the fixed
     * field set, NOT read from production constants — the message JSON is
     * {"id","sessionId","role","partsJson","createdAt","sortOrder",
     * "reasoningContent"} plus the 100-char body):
     *  - 100 ASCII chars → 248 chars = 248 bytes
     *  - 100 CJK chars   → 248 chars = 448 bytes
     *  - empty session object → 2 bytes
     *  - budget 350 → 348 bytes left for the message after the session charge
     */
    @Test
    fun `a cjk message a char budget would keep is dropped by the byte budget`() {
        val result = packChatHistoryWithBudget(
            skeletonBytes = 0L,
            budgetTotalBytes = 350L,
            sessionsInOrder = listOf(JSONObject() to listOf(message("中".repeat(100)))),
            sanitize = ::sanitize,
            capReasoning = ::capReasoning,
        )
        // 448 bytes > 348 available ⇒ dropped. A char ruler would have charged
        // 248 and kept it.
        assertEquals(0, result.messages.size)
        assertEquals(1, result.messagesDropped)
    }

    @Test
    fun `the same body in ascii still fits the same budget`() {
        // Control arm: same char count, same budget, different encoding. This
        // is what proves the arm above fails on the RULER, not on the size.
        val result = packChatHistoryWithBudget(
            skeletonBytes = 0L,
            budgetTotalBytes = 350L,
            sessionsInOrder = listOf(JSONObject() to listOf(message("a".repeat(100)))),
            sanitize = ::sanitize,
            capReasoning = ::capReasoning,
        )
        assertEquals(1, result.messages.size)
        assertEquals(0, result.messagesDropped)
    }

    @Test
    fun `the frontier stops at the first message the bytes do not allow`() {
        // Newest-first: the CJK message (448B) lands, the ASCII one (248B) has
        // only 150B left and is NOT carried — under a char budget both would
        // have fit (496 ≤ 598).
        val result = packChatHistoryWithBudget(
            skeletonBytes = 0L,
            budgetTotalBytes = 600L,
            sessionsInOrder = listOf(
                JSONObject() to listOf(message("中".repeat(100)), message("a".repeat(100))),
            ),
            sanitize = ::sanitize,
            capReasoning = ::capReasoning,
        )
        assertEquals(1, result.messages.size)
        assertTrue(
            "the kept message must be the newest one",
            result.messages[0].getString("partsJson").contains("中"),
        )
        assertEquals(1, result.messagesDropped)
    }

    // ── The export size ladder ───────────────────────────────────────────

    @Test
    fun `a payload that already fits is returned on the first attempt`() = runBlocking {
        var calls = 0
        val out = ConfigBackup.buildWithinCap(100_000L, safetyMarginBytes = 0L) { _, _ ->
            calls++
            ConfigBackup.BuiltPayload(
                text = "x".repeat(999),
                nonChatBytes = 500,
                hadArtifacts = false,
            )
        }
        assertEquals("x".repeat(999), out)
        assertEquals(1, calls)
    }

    @Test
    fun `chat-attributable oversize shrinks the chat budget, then gives up`() =
        runBlocking {
            val caps = mutableListOf<Long>()
            var calls = 0
            val over: suspend (Long, Boolean) -> ConfigBackup.BuiltPayload =
                { attemptCap, artifacts ->
                    calls++
                    caps.add(attemptCap)
                    ConfigBackup.BuiltPayload(
                        // Always over the cap (independent of the attempt),
                        // with a small skeleton and a huge chat share ⇒ only
                        // trimming chat could help, and it never does here.
                        text = "x".repeat(150_000),
                        nonChatBytes = 100,
                        hadArtifacts = artifacts,
                    )
                }
            try {
                ConfigBackup.buildWithinCap(100_000L, safetyMarginBytes = 0L, build = over)
                fail("must not return an oversize document")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "message must name the cap: ${e.message}",
                    e.message!!.contains("100000"),
                )
                assertTrue(
                    "message must be actionable: ${e.message}",
                    e.message!!.contains("export again"),
                )
            }
            // 1 initial attempt + 4 shrink rounds + 1 artifact-drop attempt =
            // 6 builds, then it throws. Bounded: an unbounded ladder would spin
            // forever here (this fake payload never shrinks).
            assertEquals(6, calls)
            assertEquals(100_000L, caps.first())
            assertEquals("the artifact-drop attempt keeps the last scale", caps[4], caps[5])
            for (round in 1..4) {
                val previous = caps[round - 1]
                // Hand-written band around the documented 0.7 shrink: the exact
                // truncation depends on double rounding, the CONTRACT is that
                // each round visibly shrinks the budget and none collapses.
                assertTrue(
                    "round $round must shrink but not collapse: $caps",
                    caps[round] in (previous * 69 / 100)..(previous * 71 / 100),
                )
            }
        }

    @Test
    fun `a skeleton over the cap drops the artifact archive instead of chat`() =
        runBlocking {
            val flags = mutableListOf<Boolean>()
            val out = ConfigBackup.buildWithinCap(
                100_000L,
                safetyMarginBytes = 0L,
            ) { cap, includeArtifacts ->
                flags.add(includeArtifacts)
                if (includeArtifacts) {
                    ConfigBackup.BuiltPayload(
                        // Skeleton alone busts the cap ⇒ no amount of chat
                        // trimming can help; the archive is the thing to drop.
                        text = "x".repeat((cap + 5_000).toInt()),
                        nonChatBytes = cap + 4_000,
                        hadArtifacts = true,
                    )
                } else {
                    ConfigBackup.BuiltPayload(
                        text = "y".repeat(1_000),
                        nonChatBytes = 900,
                        hadArtifacts = false,
                    )
                }
            }
            assertEquals("y".repeat(1_000), out)
            assertEquals("one attempt per artifact setting — chat rounds skipped", 2, flags.size)
            assertEquals(listOf(true, false), flags)
        }

    @Test
    fun `nothing over the cap is ever returned and the result passes the import gate`() =
        runBlocking {
            // End-to-end shape with a small cap: build a JSON document that
            // shrinks as the chat budget shrinks, exactly like buildSections.
            val cap = 4_000L
            val out = ConfigBackup.buildWithinCap(
                cap,
                safetyMarginBytes = 0L,
            ) { attemptCap, _ ->
                val chat = "c".repeat((attemptCap / 2).toInt())
                val text = JSONObject()
                    .put("format", "openminis.config.backup")
                    .put("chatMessages", chat)
                    .put("pad", "p".repeat(3_000))
                    .toString()
                ConfigBackup.BuiltPayload(
                    text = text,
                    nonChatBytes = 3_100,
                    hadArtifacts = false,
                )
            }
            assertTrue(
                "returned document must fit the cap",
                ConfigBackup.utf8Length(out) <= cap,
            )
            assertFalse(
                "and must pass the gate import() applies",
                ConfigBackup.documentExceedsPayloadCap(out, cap),
            )
        }

    @Test
    fun `an oversized payload with no chat and no artifacts fails loudly`() =
        runBlocking {
            var calls = 0
            try {
                ConfigBackup.buildWithinCap(10_000L, safetyMarginBytes = 0L) { cap, _ ->
                    calls++
                    ConfigBackup.BuiltPayload(
                        text = "x".repeat((cap + 1).toInt()),
                        // Skeleton alone busts the cap: nothing left to trim.
                        nonChatBytes = cap + 1,
                        hadArtifacts = false,
                        sectionBreakdown = { "skills=9000KB, memoryFiles=100KB" },
                    )
                }
                fail("must throw")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Sections:"))
                assertTrue(e.message!!.contains("skills"))
            }
            assertEquals("no pointless rebuild rounds", 1, calls)
        }
}
