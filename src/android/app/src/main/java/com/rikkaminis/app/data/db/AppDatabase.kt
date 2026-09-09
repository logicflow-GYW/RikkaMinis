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
    version = 12,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
