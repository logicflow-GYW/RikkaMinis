package com.rikkaminis.app.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-internal-usage-events] Wiring test for the cost ledger.
 *
 * The ledger only works if EVERY non-message LLM call records into it — the
 * failure mode this guards against is the one this repo has paid for repeatedly:
 * fix one call site, leave the sibling, and the feature looks implemented while
 * silently under-reporting. That is not something the type system or a unit test
 * of the recorder can catch (both look green when a call site is missing), so it
 * is checked structurally against the real source tree, the same way
 * [com.rikkaminis.app.data.db.DatabaseVersionGuardTest] reads `AppDatabase.kt`.
 *
 * Three invariants:
 *  1. every file that calls `ProviderExecutionGateway.send(` also calls
 *     `InternalUsageRecorder.record(` — no unwired call site;
 *  2. every source id declared in [InternalUsageSources] is referenced by at
 *     least one call site — no constant that nothing writes;
 *  3. `insertInternalUsageEvent(` is called from exactly one file (the recorder)
 *     — one write path, so removing the funnel turns this red instead of
 *     quietly creating a second accounting path;
 *  4. every test double that implements `ChatDao` also implements the new DAO
 *     member — an abstract method added to the interface breaks all of them at
 *     once, and the JVM suite can say so in seconds instead of a 20-minute CI
 *     round (this arm was added after run #2979 failed exactly there).
 *
 * Deliberate exclusions must be listed in [EXCLUDED_GATEWAY_FILES] with a
 * reason; an empty list means "nothing is exempt today".
 */
class InternalUsageWiringTest {

    /** Gateway call sites that legitimately do not record, with the reason. */
    private val excludedGatewayFiles = emptySet<String>()

    @Test
    fun `every gateway call site records into the ledger`() {
        val all = mainSources()
        val sendSites = all.filter { it.readText().contains(SEND_CALL) }.map { it.name }.toSet()
        val wiredSites = all
            .filter { it.readText().contains(RECORD_CALL) }
            .map { it.name }
            .minus("InternalUsageRecorder.kt")
            .toSet()

        assertTrue(
            "no ProviderExecutionGateway.send( call site found — the scan is broken, " +
                "not the wiring (would be a vacuous pass)",
            sendSites.isNotEmpty(),
        )
        assertEquals(
            "every gateway call site must record into the cost ledger " +
                "(send sites: $sendSites, wired: $wiredSites, exempt: $excludedGatewayFiles). " +
                "A new internal LLM call must add InternalUsageRecorder.record(...) next to its " +
                "send(...) — otherwise its tokens are invisible on the usage page.",
            sendSites - excludedGatewayFiles,
            wiredSites,
        )
    }

    @Test
    fun `every declared source id is wired at a call site`() {
        val sources = mainSources()
        val referenced = sources
            .flatMap { SOURCE_REF.findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()
        assertEquals(
            "every id in InternalUsageSources must be referenced by a call site " +
                "(declared-but-never-written ids would render as an empty row)",
            setOf("COMPACT", "TITLE", "QUICK_TEST"),
            referenced,
        )
    }

    @Test
    fun `the ledger has exactly one write path`() {
        val writers = mainSources()
            .filter { it.readText().contains(INSERT_CALL) }
            .map { it.name }
            .toSet()
        assertEquals(
            "insertInternalUsageEvent( may only be called from the recorder " +
                "(one funnel keeps 'is every call site wired' answerable by grep)",
            setOf("ChatDao.kt", "InternalUsageRecorder.kt"),
            writers,
        )
    }

    @Test
    fun `every test double that implements ChatDao covers the ledger surface`() {
        val testRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, TEST_DIR).isDirectory }
            ?: error("src/test/java not found from ${File(".").absoluteFile}")
        val fakes = File(testRoot, TEST_DIR).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(CHAT_DAO_IMPL) }
            .toList()
        assertTrue(
            "no ChatDao test double found — the scan is broken, not the fakes",
            fakes.isNotEmpty(),
        )
        // Both members the ledger adds to ChatDao: the write funnel and the read
        // query. Checking only the write let run #2980 fail on the query, so the
        // arm names every missing member instead of asserting on one string.
        val required = listOf(INSERT_CALL, QUERY_CALL)
        val missing = fakes.flatMap { f ->
            val text = f.readText()
            required.filterNot { text.contains(it) }.map { "${f.name} lacks $it" }
        }
        assertEquals(
            "a ChatDao member added to the interface breaks every test double at compile time " +
                "(runs #2979 / #2980 failed on exactly this). Add inert overrides " +
                "(`insertInternalUsageEvent` → no-op, `internalUsageEventsBetween` → emptyList()): $missing",
            emptyList<String>(),
            missing,
        )
    }

    // ─────────────────────────────── helpers ───────────────────────────────
    private companion object {
        const val MAIN_DIR = "src/main/java"
        const val TEST_DIR = "src/test/java"
        const val CHAT_DAO_IMPL = ": ChatDao {"
        const val SEND_CALL = "ProviderExecutionGateway.send("
        const val RECORD_CALL = "InternalUsageRecorder.record("
        const val INSERT_CALL = "insertInternalUsageEvent("
        const val QUERY_CALL = "internalUsageEventsBetween("
        val SOURCE_REF = Regex("""InternalUsageSources\.([A-Z_]+)""")
    }

    /** Every `*.kt` under the app's `src/main/java`, located from the test cwd. */
    private fun mainSources(): List<File> {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, MAIN_DIR).isDirectory }
            ?: error("src/main/java not found from ${File(".").absoluteFile}")
        return File(root, MAIN_DIR).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }
}
