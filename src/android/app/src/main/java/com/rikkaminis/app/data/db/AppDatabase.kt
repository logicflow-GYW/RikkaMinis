package com.rikkaminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        WebAppShortcutEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun webAppShortcutDao(): WebAppShortcutDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN last_message TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN model_binding TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning_content TEXT")
            }
        }

        /**
         * compact_markers: add Phase-A id-first boundary columns. The legacy
         * sort_order columns stay for backfill; when both are present the
         * id-first fields win on lookup (see ChatDao.latestCompactMarker).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN first_kept_message_id TEXT")
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN last_compacted_message_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_first_kept_message_id ON compact_markers(first_kept_message_id)")
            }
        }

        /**
         * T239: per-session thinking-mode override. Nullable so existing
         * sessions transparently keep "unset" semantics; only sessions where
         * the user explicitly chooses a level start storing a non-null value.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN thinking_override TEXT")
            }
        }

        /**
         * T-pwa-1: pwa_shortcuts table backs the home-screen PWA pinning
         * flow. Pure additive migration — no existing entity is modified
         * and no data is rewritten.
         *
         * Superseded by MIGRATION_8_9 below (Pwa → WebApp rename); kept
         * here so users who already migrated from <=6 land on a
         * consistent state before the rename runs.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pwa_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * compact_markers: add `version` column for marker schema versioning.
         * Mirrors iOS Phase v2 — version=1 = legacy multi-field model,
         * version=2 = simplified id-only anchor model. Existing rows default
         * to 1 so legacy resolution code keeps running for them.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Pwa → WebApp rename: copy every row from `pwa_shortcuts` into a
         * new `webapp_shortcuts` table with identical schema, then drop
         * the old table. Row contents (UUIDs, html paths, icon refs) are
         * preserved verbatim — only the table name changes — so existing
         * in-app shortcut lists keep showing the same entries.
         *
         * Note: pinned launcher icons created before this rename still
         * carry the old `ACTION_OPEN_PWA` intent action and will be dead
         * after the upgrade (manifest no longer registers it). The user
         * has to re-pin from inside the app. Per
         * `feedback_no_destructive_git` we do NOT silently delete data —
         * the DB row stays, only the launcher-side icon dies.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS webapp_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO webapp_shortcuts (
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    )
                    SELECT
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    FROM pwa_shortcuts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS pwa_shortcuts")
            }
        }

        /**
         * [T-error-persist-android] messages.error_info — persist the terminal
         * error sticker on an assistant turn so the inline error survives a
         * session reload (mirrors iOS messages.error_info). Pure additive,
         * nullable column; existing rows read back NULL (= no error). No data
         * rewrite.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN error_info TEXT")
            }
        }

        /**
         * [RC15] Enforce a unique (session_id, sort_order) combination on
         * `messages`. `nextSortOrder` reads MAX(sort_order)+1 outside a
         * transaction, so two concurrent appends in the same session can race
         * onto the same value and (with the prior non-unique index) silently
         * REPLACE each other — losing a message. A unique index turns that into
         * a hard constraint violation the append path can detect and retry.
         *
         * Before creating the unique index we have to defensively renumber any
         * existing duplicate (session_id, sort_order) groups in the field — a
         * legacy DB may already contain dupes (e.g. from the race above, or an
         * odd import). Duplicate sort_order values are reassigned in ascending
         * rowid order so the resulting order is stable and the unique index can
         * be created without failing on dirty data. This is a read-then-write
         * over the full table and must run inside a transaction (Room wraps
         * each Migration in one) so the renumber + index creation are atomic.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Renumber sort_order within each session into a dense,
                //    gap-free 0..N-1 sequence ordered by (sort_order, rowid).
                //    This repairs any legacy duplicate (session_id, sort_order)
                //    groups in the field — the exact race we're closing, also
                //    reachable via odd imports — so the unique index can be
                //    created without failing on dirty data.
                //
                //    We snapshot the rank into a temp table FIRST, then apply
                //    it. A self-referencing UPDATE whose subquery reads the
                //    table being written would see rows land in undefined order
                //    and could mis-rank; the temp table forces a single stable
                //    read pass (rank = number of rows in the same session with
                //    a strictly-lower (sort_order, rowid) tuple — the
                //    AUTOINCREMENT-free rowid is a unique, stable tiebreaker).
                db.execSQL(
                    """
                    CREATE TEMP TABLE _sort_order_renumber (
                        rowid INTEGER PRIMARY KEY,
                        new_sort_order INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO _sort_order_renumber (rowid, new_sort_order)
                    SELECT m.rowid,
                           (SELECT COUNT(*)
                            FROM messages AS other
                            WHERE other.session_id = m.session_id
                              AND (other.sort_order < m.sort_order
                                   OR (other.sort_order = m.sort_order
                                       AND other.rowid < m.rowid)))
                    FROM messages AS m
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE messages
                    SET sort_order = (
                        SELECT new_sort_order
                        FROM _sort_order_renumber
                        WHERE _sort_order_renumber.rowid = messages.rowid
                    )
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE _sort_order_renumber")

                // 2. Drop the old non-unique index (houskeeping) and create the
                //    unique one. Room names generated indices
                //    `index_<table>_<col>_<col>`; the older non-unique index has
                //    the same name so we replace it wholesale.
                db.execSQL("DROP INDEX IF EXISTS index_messages_session_id_sort_order")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_session_id_sort_order " +
                        "ON messages(session_id, sort_order)"
                )
            }
        }

        /**
         * [T-usage-attribution] messages.usage_model_id / usage_entry_id —
         * carry the identity of the provider+model that actually produced a
         * turn's token usage (recorded at persist time, after fallback
         * resolution). Pure additive, nullable columns; legacy rows read back
         * NULL and allUsageRecords falls back to sessions.model_id. No data
         * rewrite.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN usage_model_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN usage_entry_id TEXT")
            }
        }

        /**
         * [feat/usage-stats-perf-1007] Partial index on the usage rows. The
         * usage stats screen scans `messages WHERE token_usage IS NOT NULL`
         * (no other index applies), which is a full table scan of every
         * message — a multi-second spinner once history grows. The partial
         * index holds only usage rows, so `allUsageRecords` /
         * `usageRecordsBetween` and the SQL-side aggregation query
         * (`usageStatsAggregated`) stop scanning non-usage messages.
         * Purely additive — see [MIGRATION_13_12] for the reverse.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_usage_created ON messages(created_at) WHERE token_usage IS NOT NULL")
            }
        }

        /**
         * [T-android-downgrade-compat] Empty-body reverse of
         * [MIGRATION_12_13]. An extra index on disk is ignored by Room's
         * schema validation and costs a few dozen KB, so the round trip is
         * lossless — same reasoning as the ADD COLUMN case in the
         * MIGRATION_3_4 doc block. MUST ship in the same commit as
         * [MIGRATION_12_13] (Room rejects a downgrade whose `from` exceeds
         * the @Database version) and with the matching `13 to 12` entry in
         * [com.rikkaminis.app.data.db.DatabaseVersionGuard.HANDLED_DOWNGRADES].
         */
        val MIGRATION_13_12 = object : Migration(13, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Empty: ADD INDEX has a lossless no-op reverse (an extra
                // index is ignored on open).
            }
        }

        /**
         * [T-android-downgrade-compat] There is deliberately NO downgrade
         * migration in this file, even though Room supports them. This is a
         * hard Room constraint, not a style choice:
         *
         * `MigrationManager.buildMap` walks the registered list in order, and
         * throws `IllegalArgumentException("Invalid migration ... Migrations
         * must be chained. Starting from migration X, expected a migration
         * starting at version 12")` for the first migration whose `from` is
         * neither already a known target nor the current walk head. With
         * `version = 12` the walk tops out at 12, so `Migration(13, 12)` is
         * rejected the moment `AppDatabase.getInstance` builds the helper —
         * at launch, for every user, on a database that was never downgraded.
         * Verified against the bytecode of `room-common:2.6.1`, the exact
         * version this build pins (`MigrationManager.buildMap`,
         * `RoomOpenHelper.buildOpenHelper`).
         *
         * So a downgrade pair may only be registered in the build that also
         * contains the forward migration creating its `from` version. The
         * 12 → 13 bump (feat/usage-stats-perf-1007) did exactly that:
         * MIGRATION_12_13 + MIGRATION_13_12 landed in the same commit and
         * [com.rikkaminis.app.data.db.DatabaseVersionGuard.isHandledDowngrade]
         * grew the matching `13 to 12` entry. The unit tests `no registered
         * downgrade starts above the database version` and `whitelisted
         * pairs and wired downgrades are the same set` fail if either half
         * is added without the other.
         *
         * The 13 → 12 reverse should then be an empty body: `ADD COLUMN` is
         * the one schema change with a safe reverse, because
         *
         * - Room's generated DAOs bind by column NAME, never by position;
         * - open-time schema validation requires every column the entity
         *   declares, and extra columns left on disk are ignored;
         * - the new columns are nullable with no DEFAULT, so old `INSERT`s
         *   are unaffected.
         *
         * Not `DROP COLUMN` — that would genuinely delete whatever the newer
         * build captured, so a user who downgrades to look at something and
         * upgrades back would silently lose it, the opposite of the point.
         * Keeping the columns costs a few dozen bytes per row and makes the
         * round trip lossless. (`ALTER TABLE ... DROP COLUMN` also needs
         * SQLite 3.35+ / API 34+, and does a full table rebuild below that.)
         *
         * The precondition for all of this: whoever writes MIGRATION_12_13
         * must keep it purely additive (ADD COLUMN / ADD TABLE / ADD INDEX).
         * Renames, drops, type changes and — most dangerously — changes to
         * the MEANING of existing data have no safe automatic reverse. If the
         * bump is anything else, skip MIGRATION_13_12 and the
         * `isHandledDowngrade` entry, so the pre-check in
         * [com.rikkaminis.app.data.db.DatabaseVersionGuard] takes over and
         * shows the guidance screen instead of trusting a lossy reverse.
         *
         * There is no MIGRATION_12_11 either. 11 → 12 is purely additive, so a
         * no-op reverse would be just as lossless — but the build that would
         * actually run 12 → 11 is a v11 APK that predates this file, so nothing
         * in this branch can help it. That is what the guidance screen is for.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // sessions: add iOS-parity columns
                db.execSQL("ALTER TABLE sessions ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN memory_enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned_at INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN edit_count INTEGER NOT NULL DEFAULT 0")

                // messages: add iOS-parity columns
                db.execSQL("ALTER TABLE messages ADD COLUMN stream_interrupt_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN updated_at INTEGER")

                // compact_markers: new table mirroring iOS
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS compact_markers (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        first_kept_sort_order INTEGER NOT NULL,
                        compacted_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        ui_boundary_sort_order INTEGER,
                        boundary_message_id TEXT,
                        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_session_id ON compact_markers(session_id)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minis.db"
                )
                    // [T-android-downgrade-compat] 11 upgrades, 0 downgrades — see the
                    // MIGRATION_3_4 doc block above for why a downgrade pair belongs
                    // with its forward bump, not with this build.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_12)
                    // Fresh installs jump straight to the current version —
                    // migrations never run for them, so the usage partial
                    // index has to be created here as well (upgrades get it
                    // from MIGRATION_12_13).
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_usage_created ON messages(created_at) WHERE token_usage IS NOT NULL")
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
