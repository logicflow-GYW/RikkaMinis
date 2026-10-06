package com.rikkaminis.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps the downgrade guard's decision policy and its two constants honest.
 *
 * ## What this protects
 *
 * [DatabaseVersionGuard.CODE_DB_VERSION] duplicates the `version` in
 * [AppDatabase]'s `@Database` annotation, because that annotation has BINARY
 * retention and is not readable at runtime. Two copies of one number is
 * exactly what drifts on the next schema bump, and drift is silent in both
 * directions:
 *
 *  - constant too LOW → the guard fires on every launch of a healthy install
 *    and the user is stuck on the "your data is from a newer version" screen
 *    forever;
 *  - constant too HIGH → the guard never fires and a real downgrade goes back
 *    to crashing at first database access.
 *
 * This repo has `exportSchema = false` (no committed schema JSON to compare
 * against), so the cross-check reads the annotation straight out of the
 * source file instead. Same assertion, different source of truth.
 *
 * ## Why the policy is tested through [DatabaseVersionGuard.decide]
 *
 * That is the whole decision table — `decide` and [isHandledDowngrade] are
 * pure, so they can be driven table-driven with hard-coded literals. Every
 * expected value below is a literal, never derived from the constant under
 * test: a mutated constant must turn a row RED rather than quietly re-pass.
 *
 * [DatabaseVersionGuard.evaluate] is deliberately NOT exercised here — it
 * needs a real `Context` and a real `SQLiteDatabase`, which only exist on a
 * device. Its only two outputs are `null` (no file / unreadable file) and an
 * integer; both feed `decide` and are covered by the rows below.
 */
class DatabaseVersionGuardTest {

    private val proceed = DatabaseVersionGuard.Decision.PROCEED
    private val guidance = DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE

    // ───────────────────────────── decision table ─────────────────────────

    @Test
    fun `decide - table driven with hard-coded literals`() {
        data class Row(val onDisk: Int?, val code: Int, val expected: DatabaseVersionGuard.Decision)

        val rows = listOf(
            // fresh install: no database file at all
            Row(null, 12, proceed),
            Row(null, 1, proceed),
            Row(null, 0, proceed),
            // healthy current build
            Row(12, 12, proceed),
            // older database — a normal upgrade, Room's job
            Row(11, 12, proceed),
            Row(5, 12, proceed),
            Row(1, 12, proceed),
            // downgrade covered by the wired MIGRATION_13_12 reverse ->
            // normal open, no guidance
            Row(13, 12, proceed),
            // downgrades with no registered migration -> guidance, never a crash
            Row(14, 12, guidance),
            Row(99, 12, guidance),
            // the covered jump is specific: 13 is not the same as 14
            Row(14, 13, guidance),
            Row(15, 13, guidance),
            // a hypothetical future bump must not be auto-handled
            Row(16, 13, guidance),
            Row(17, 14, guidance),
        )

        for (row in rows) {
            assertEquals(
                "decide(onDisk=${row.onDisk}, code=${row.code})",
                row.expected,
                DatabaseVersionGuard.decide(row.onDisk, row.code),
            )
        }
        assertEquals("all rows executed", 14, rows.size)
    }

    @Test
    fun `decide - an uncovered downgrade never resolves to proceed`() {
        // The failure this whole mechanism exists to prevent: an uncovered
        // downgrade must NOT resolve to PROCEED, because that is where Room
        // throws and the app stops starting. The one wired covered pair
        // (13 → 12, MIGRATION_13_12) is excluded by literal — NOT by asking
        // isHandledDowngrade, so a silently widened whitelist still turns
        // this sweep red.
        for (code in 1..25) {
            for (onDisk in (code + 1)..(code + 25)) {
                if (onDisk == 13 && code == 12) continue // wired: MIGRATION_13_12
                if (DatabaseVersionGuard.decide(onDisk, code) == proceed) {
                    fail(
                        "decide(onDisk=$onDisk, code=$code) unexpectedly PROCEEDed an " +
                            "uncovered downgrade",
                    )
                }
            }
        }
    }

    // ───────────────────────── handled-downgrade whitelist ─────────────────

    @Test
    fun `isHandledDowngrade - whitelists exactly the wired 13-to-12`() {
        // The 12 → 13 bump wired MIGRATION_13_12 (empty-body reverse of a
        // partial ADD INDEX). The whitelist must name exactly that pair: the
        // 25x25 sweep below is the guard against a half-landed or silently
        // widened bump. See HANDLED_DOWNGRADES.
        val hits = (0..25).flatMap { code ->
            (0..25).map { onDisk -> onDisk to code }
        }.filter { DatabaseVersionGuard.isHandledDowngrade(it.first, it.second) }
        assertEquals(
            "the whitelist must name exactly the wired MIGRATION_13_12; got $hits",
            setOf(13 to 12),
            hits.toSet(),
        )

        // Argument order is load-bearing: (onDisk, code). A flipped call
        // would read 12 -> 13 as "the database is ahead", and the narrow
        // whitelist above would not notice either way.
        assertFalse("12 is not a downgrade at all",
            DatabaseVersionGuard.isHandledDowngrade(12, 12))
        assertFalse("11 -> 12 is an upgrade, not a downgrade",
            DatabaseVersionGuard.isHandledDowngrade(11, 12))
        assertFalse("13 -> 14 is an upgrade, not a downgrade",
            DatabaseVersionGuard.isHandledDowngrade(13, 14))
        // the covered jump is specific: 13 is not the same as 14
        assertFalse("14 -> 12 has no wired reverse",
            DatabaseVersionGuard.isHandledDowngrade(14, 12))
    }

    // ───────────── constant vs @Database(version = N) ──────────────────────

    @Test
    fun `guard constant matches the @Database version annotation`() {
        val version = databaseVersion(databaseSource())
        assertNotNull(
            "no version = N inside AppDatabase's @Database annotation — " +
                "CODE_DB_VERSION is unchecked without it",
            version,
        )
        assertEquals(
            "DatabaseVersionGuard.CODE_DB_VERSION must be bumped together with " +
                "@Database(version = ...) in AppDatabase — bump both, add a Migration for " +
                "the new version, and extend isHandledDowngrade",
            version,
            DatabaseVersionGuard.CODE_DB_VERSION,
        )
    }

    // ── isHandledDowngrade must match the migrations that are actually wired ──

    @Test
    fun `no registered downgrade starts above the database version`() {
        // Room's MigrationManager.buildMap walks the registered list in order
        // and throws IllegalArgumentException ("Migrations must be chained ...
        // expected a migration starting at version N") for the first migration
        // whose `from` is neither already a known target nor the current walk
        // head — and the walk tops out at the @Database version, because no
        // legal migration can target past it. So a downgrade whose source
        // version sits above the database version cannot be REGISTERED at all:
        // the failure happens in RoomOpenHelper's constructor, on every launch,
        // for every user, on a database that was never downgraded.
        //
        // This branch's first attempt at the fix was exactly that: a
        // MIGRATION_13_12 registered into a version-12 database. It compiled
        // and every test passed, and it would have replaced one crash on
        // downgrade with a crash on every start. The scanner's BOUNDS check
        // catches it too; this is the JVM-level copy, so the rule holds even
        // when the scanner is not on the path.
        val src = databaseSource()
        val version = requireNotNull(databaseVersion(src)) {
            "no version = N inside AppDatabase's @Database annotation"
        }
        val (declared, _) = migrationPairs(src)

        for ((onDisk, code) in declared.filter { it.first > it.second }) {
            assertFalse(
                "Migration($onDisk, $code) starts above @Database version $version, " +
                    "so Room rejects the whole chain at build time and the app crashes " +
                    "on every launch. A downgrade can only be registered in the build " +
                    "that also carries the forward migration creating version $onDisk.",
                onDisk > version,
            )
        }
    }

    @Test
    fun `whitelisted pairs and wired downgrades are the same set`() {
        // The load-bearing coupling, in both directions. The two directions
        // cost different things when they break:
        //
        //  * the whitelist accepts a jump Room cannot open → the guard
        //    PROCEEDs, Room throws, the app stops starting. This feature
        //    exists to remove that crash, so it is the direction that must
        //    never break.
        //  * Room can open a downgrade the guard does not know about → the
        //    app shows "your data is from a newer version" on a database it
        //    could have opened safely. Wrong, and recoverable: the user taps
        //    through and the migration runs.
        //
        // Reading the pairs out of the source rather than hard-coding them is
        // what keeps this alive across bumps. When 12 → 13 lands, both sides
        // grow and the equality still holds; skipping either half — adding
        // MIGRATION_13_12 without the HANDLED_DOWNGRADES entry, or the entry
        // without the migration — turns this red.
        val src = databaseSource()
        val (declared, wired) = migrationPairs(src)

        val declaredDowngrades = declared.filter { it.first > it.second }.toSet()
        val wiredDowngrades = wired.filter { it.first > it.second }.toSet()

        // The whitelist is closed and small, so it can be enumerated exactly
        // instead of inferred. Anything outside 0..200 is out of reach of the
        // schema versions this project has ever used.
        val whitelisted = mutableSetOf<Pair<Int, Int>>()
        for (onDisk in 0..200) {
            for (code in 0..200) {
                if (DatabaseVersionGuard.isHandledDowngrade(onDisk, code)) {
                    whitelisted += onDisk to code
                }
            }
        }

        assertEquals(
            "the guard whitelist must name exactly the downgrades that are wired " +
                "into addMigrations (wired downgrades: $wiredDowngrades, " +
                "whitelisted: $whitelisted)",
            wiredDowngrades,
            whitelisted,
        )
        assertEquals(
            "every wired downgrade must be declared in AppDatabase " +
                "(declared downgrades: $declaredDowngrades, " +
                "wired downgrades: $wiredDowngrades)",
            declaredDowngrades,
            wiredDowngrades,
        )
    }

    // ───────────────────────────── helpers ────────────────────────────────

    /** The pair list from the source: (declared pairs, pairs wired in addMigrations). */
    private fun migrationPairs(src: String): Pair<Set<Pair<Int, Int>>, Set<Pair<Int, Int>>> {
        // Match whole DECLARATIONS, not bare names: prose such as
        // "Superseded by MIGRATION_8_9 below" must not count as a migration
        // that has to be wired. The negative lookahead additionally enforces
        // that the declaration's name agrees with the step it performs, which
        // is what makes the "declared == wired pairs" comparison sound below.
        val declRe = Regex(
            """val\s+MIGRATION_(\d+)_(\d+)(?!\1_(?:\2)\b)\s*=\s*object\s*:\s*Migration\s*\(\s*(\1)\s*,\s*(\2)\s*\)""",
        )
        val declared = declRe.findAll(src)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toSet()
        // Inside addMigrations(...) the names are bare, so a plain
        // name -> pair read is unambiguous.
        val pairRe = Regex("""MIGRATION_(\d+)_(\d+)""")
        val wiredBlock = Regex("""addMigrations\s*\(([^)]*)\)""").find(src)?.groupValues[1]
        assertNotNull("no addMigrations(...) call in AppDatabase.kt", wiredBlock)
        val wired = pairRe.findAll(wiredBlock!!)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toSet()
        return declared to wired
    }

    /** The `version = N` inside AppDatabase's @Database annotation, or null. */
    private fun databaseVersion(src: String): Int? =
        Regex("version\\s*=\\s*(\\d+)").find(databaseAnnotationBody(src))
            ?.groupValues?.get(1)?.toInt()

    /** The text between `@Database` and `class AppDatabase`. */
    private fun databaseAnnotationBody(src: String): String {
        val start = src.indexOf("@Database")
        val end = src.indexOf("class AppDatabase", start)
        assertTrue(
            "could not find @Database(...) on AppDatabase in AppDatabase.kt",
            start >= 0 && end > start,
        )
        return src.substring(start, end)
    }

    private fun databaseSource(): String {
        val file = locateDatabaseFile() ?: error(
            "AppDatabase.kt not found from ${File(".").absoluteFile} — " +
                "cannot cross-check CODE_DB_VERSION",
        )
        return file.readText()
    }

    /**
     * Locate AppDatabase.kt from the test's working directory. Gradle runs
     * unit tests with cwd = the app module directory, so `src/main/java/...`
     * works; the upward walk covers a repo-root cwd as well.
     */
    private fun locateDatabaseFile(): File? {
        val rel = "src/main/java/com/rikkaminis/app/data/db/AppDatabase.kt"
        val viaAndroid = "src/android/app/src/main/java/com/rikkaminis/app/data/db/AppDatabase.kt"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        return null
    }
}
