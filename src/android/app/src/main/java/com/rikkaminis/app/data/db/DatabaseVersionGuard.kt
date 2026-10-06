package com.rikkaminis.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rikkaminis.app.logging.AppLogger

/**
 * Detects a database written by a NEWER build than the one now running,
 * before Room ever opens it.
 *
 * ## The failure this prevents
 *
 * Room resolves a downgrade by calling `onUpgrade(from, to)` and looking for
 * a migration path. Finding none it consults `isMigrationRequired`, which
 * either throws `IllegalStateException` or — with a destructive fallback
 * enabled — calls `dropAllTables`. We enable no fallback (deliberately:
 * dropping would destroy the user's entire chat history), so an unmatched
 * downgrade means the app throws at first database access and simply will
 * not start.
 *
 * This fork is a sideloaded self-signed build: users roll back to the
 * previous APK to diagnose a problem, and move between stable and branch
 * builds on the same install. That is exactly the on-disk version 13 /
 * code version 12 situation this guard exists for.
 *
 * Note the guard is not optional even if a downgrade migration could be
 * registered: Room 2.6.1 only accepts a downgrade whose `from` version is
 * reachable from `@Database` `version`, so a `Migration(13, 12)` cannot
 * exist in a `version = 12` build at all (`MigrationManager.buildMap`
 * throws "Migrations must be chained"). Each schema bump therefore has to
 * ship its reverse in the SAME commit that lands the bump — and any bump
 * that is not purely additive has no safe reverse whatsoever.
 *
 * ADD COLUMN / ADD TABLE is the one shape with a lossless reverse (Room
 * binds by column name and ignores extra columns), but renames, drops, type
 * changes and — most dangerously — changes to the MEANING of existing data
 * have no safe automatic downgrade. And a missing link anywhere in the chain
 * (13 → 12 → 11) breaks the whole path, which is the plain human case of
 * forgetting to add the downgrade migration at all. This guard is the
 * backstop for all of that.
 *
 * ## Why a pre-check instead of catching Room's exception
 *
 * `Room.databaseBuilder(...).build()` is lazy: nothing opens until the first
 * DAO call, so the exception surfaces from an arbitrary coroutine somewhere
 * deep in the app. Every one of those call sites would need a catch, and one
 * miss is still a crash. Reading the version first is deterministic and
 * happens once, in one place ([MinisApp.onCreate] via [evaluate]).
 *
 * Crucially, this runs BEFORE Room is constructed — so at the moment we
 * decide, no migration and no `dropAllTables` can possibly have executed.
 * The database file is guaranteed untouched, which is what lets the
 * guidance screen promise the user that nothing was lost.
 *
 * ## Why the decision logic is split out from the probe
 *
 * [decide] and [isHandledDowngrade] are pure: no `Context`, no `SQLiteDatabase`,
 * no logging. They are what the JVM unit test (`DatabaseVersionGuardTest`)
 * drives table-driven with hard-coded version numbers, so the policy cannot
 * drift without a test failing. Everything that touches an Android type lives
 * in the two members below them.
 */
object DatabaseVersionGuard {

    private const val TAG = "DbVersionGuard"

    /**
     * The schema version this build understands. MUST equal the `version` in
     * [AppDatabase]'s `@Database` annotation.
     *
     * Kept as a separate constant because that annotation has BINARY
     * retention — it is not readable at runtime without reflection.
     * [DatabaseVersionGuardTest] asserts the two agree, so they cannot drift
     * apart silently: a stale copy here would either disable the guard or
     * trip it on every launch.
     */
    const val CODE_DB_VERSION = 13

    /** Filename must match the one passed to `Room.databaseBuilder`. */
    private const val DB_NAME = "minis.db"

    /** Result of the launch-time check. */
    enum class Decision {
        /** Normal path — open the database. */
        PROCEED,

        /**
         * Database is from a newer build with no downgrade path. Show guidance
         * and do NOT touch the file; upgrading restores everything.
         */
        SHOW_NEWER_DB_GUIDANCE,
    }

    // ───────────────────────── pure decision policy ─────────────────────────

    /**
     * [T-android-downgrade-compat] Downgrade jumps this build can open
     * losslessly, keyed (onDisk, code). The 12 → 13 bump
     * (feat/usage-stats-perf-1007) added `13 to 12`: MIGRATION_12_13 is a
     * partial ADD INDEX whose empty-body reverse (AppDatabase.MIGRATION_13_12)
     * is lossless — Room ignores extra indexes on open, same reasoning as
     * the ADD COLUMN case.
     */
    private val HANDLED_DOWNGRADES: Set<Pair<Int, Int>> = setOf(13 to 12)

    /**
     * Whether a registered downgrade migration covers this jump, in which case
     * the app can open the database normally and the guidance screen is
     * unnecessary.
     *
     * Only versions with an explicit, verified no-op downgrade belong here.
     * Anything else falls through to the guidance screen rather than being
     * optimistically opened — being wrong in that direction costs the user a
     * screen they can dismiss by upgrading; being wrong the other way costs
     * them a crash.
     *
     * Room 2.6.1 refuses to REGISTER a downgrade whose `from` exceeds the
     * `@Database` version (`MigrationManager.buildMap` throws "Migrations
     * must be chained" — verified against the room-common 2.6.1 bytecode).
     * So each entry here must ship in the same commit as the forward bump
     * that creates its `from` version, with an empty-body reverse migration
     * in [AppDatabase]; `DatabaseVersionGuardTest` asserts the whitelist and
     * the wired downgrades stay the same set. Any future bump that is NOT
     * purely additive (rename / drop / data-rewrite) has no safe reverse and
     * must NOT add an entry here.
     */
    fun isHandledDowngrade(onDiskVersion: Int, codeVersion: Int): Boolean =
        onDiskVersion to codeVersion in HANDLED_DOWNGRADES

    /**
     * The whole policy in one place:
     *
     * - `null` on-disk version = no database file, or a file we could not read
     *   = PROCEED. A probe failure must NEVER be allowed to lock the user out
     *   of their own app; let Room report the real problem instead.
     * - on-disk ≤ code = PROCEED (fresh install, or a normal upgrade — the
     *   upgrade path is Room's job).
     * - on-disk > code and a verified downgrade migration covers the jump =
     *   PROCEED.
     * - on-disk > code with no covered jump = SHOW_NEWER_DB_GUIDANCE.
     */
    fun decide(onDiskVersion: Int?, codeVersion: Int): Decision {
        if (onDiskVersion == null) return Decision.PROCEED
        if (onDiskVersion <= codeVersion) return Decision.PROCEED
        if (isHandledDowngrade(onDiskVersion, codeVersion)) return Decision.PROCEED
        return Decision.SHOW_NEWER_DB_GUIDANCE
    }

    // ─────────────── Android-facing probe (never called by the test) ────────

    /**
     * Read `user_version` without going through Room.
     *
     * Opened READ-ONLY on purpose: it makes it impossible for the probe itself
     * to modify, migrate or corrupt the file. Returns null when the database
     * does not exist yet (a fresh install) or cannot be read — in both cases
     * the caller should just proceed and let Room do its normal thing.
     *
     * Private because it is not a policy decision: the only place the probe's
     * result may be turned into an action is [decide], so there is exactly one
     * code path from "what is on disk" to "what the user sees".
     */
    private fun readOnDiskVersion(context: Context): Int? {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
        }.getOrElse {
            // A file we cannot even open read-only is not a downgrade signal;
            // let Room report whatever the real problem is.
            AppLogger.warning(
                TAG,
                "could not probe db version: ${it.javaClass.simpleName}",
            )
            null
        }
    }

    /**
     * Launch-time entry point. Must be called BEFORE [AppDatabase.getInstance]
     * — once Room has opened the file the decision is too late.
     */
    fun evaluate(context: Context): Decision =
        decide(readOnDiskVersion(context), CODE_DB_VERSION)
}
