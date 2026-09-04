package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Standalone Room database for provider config. Lives in `provider.db`,
 * separate from `minis.db` (sessions/messages/etc), so that downgrading
 * to a version that doesn't know about these tables does NOT crash on
 * `minis.db`. Old builds simply ignore provider.db and continue to read
 * provider config from the legacy SharedPreferences JSON mirror — which
 * we keep writing on every save so it's never stale.
 *
 * On re-upgrade, ProviderRepository compares a stored hash of the JSON
 * mirror against the live mirror to detect "old build wrote JSON behind
 * our back during the downgrade window" and re-imports if needed; that
 * way provider.db can never become authoritative-but-stale relative to
 * what the user did while downgraded.
 *
 * Schema starts at version 1; future column adds use Migration like
 * AppDatabase does. We deliberately do NOT enable
 * fallbackToDestructiveMigration: provider.db is the only copy of
 * structured provider state, and the JSON mirror is the safety net, not
 * a substitute for proper migrations.
 */
@Database(
    entities = [
        ProviderInstanceEntity::class,
        ProviderModelEntryEntity::class,
        ProviderModelGroupEntity::class,
        ProviderAgentLoopIdEntity::class,
        ProviderConfigMetaEntity::class,
        ProviderThinkingRuleEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class ProviderDatabase : RoomDatabase() {
    abstract fun providerConfigDao(): ProviderConfigDao

    companion object {
        @Volatile
        private var INSTANCE: ProviderDatabase? = null

        /**
         * [T-android-azure-openai] Add the Azure OpenAI mode column. Pure
         * additive ALTER with NOT NULL DEFAULT 0 so every existing provider row
         * backfills to "off" — no data rewrite, no provider drop. This is the
         * first migration on provider.db (introduced at v1 with all columns
         * inline); older builds that don't know the column keep reading the JSON
         * mirror, and re-upgrade re-imports if they wrote behind our back.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN azure_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * [GH#68 T-android-image-endpoint-persist] Add the image-endpoint
         * picker columns that the Room migration of the provider store
         * (4dd24ecf) missed — the JSON model carried them but every Room
         * round-trip dropped the value, snapping the picker back to Auto.
         * Pure additive nullable TEXT ALTERs; existing rows read as null →
         * auto / no cached probe, no data rewrite, no provider drop.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_mode TEXT")
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_resolved TEXT")
            }
        }

        /**
         * [P0-pinned-providers] Add the pinned favorite column. Pure additive
         * ALTER with NOT NULL DEFAULT 0 so every existing provider row
         * backfills to "not pinned" — no data rewrite, no provider drop. This
         * mirrors the [GH#68] image-endpoint bug exactly: the JSON model
         * carried the field but ProviderInstanceEntity had no column, so every
         * Room round-trip dropped the value and the "pin to Favorites" toggle
         * silently did nothing.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * [T-recovery] Add the recovery column to provider_model_groups so
         * the user can choose between continueLast (existing — stay on the
         * fallback member) / honorFirst (always try the first member first,
         * skip rate-limited members) / cooldown (skip rate-limited members
         * only). Pure additive ALTER with NOT NULL DEFAULT 'continueLast'
         * so existing rows backfill to the current behaviour — no data
         * rewrite, no group drop.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_model_groups ADD COLUMN recovery TEXT NOT NULL DEFAULT 'continueLast'")
            }
        }

        /**
         * [T-four-way-sync-gate] recovery column was removed from the
         * Entity (fff19a2) but MIGRATION_4_5 already added it to the
         * database, so a schema hash mismatch appeared. An empty migration
         * is NOT enough — Room re-validates the schema after migration and
         * fails with "Migration didn't properly handle: provider_model_groups"
         * because the DB still has the orphaned recovery column. This
         * migration DROPs the recovery column so the DB schema exactly
         * matches the current Entity.
         *
         * ALTER TABLE DROP COLUMN preserves every other column's type/NULL/
         * default exactly as Room created it — safer than a manual table
         * rebuild, which risks column-definition drift and re-triggering
         * "Migration didn't properly handle". Requires SQLite >= 3.35
         * (Android 12+ = 3.32, Android 15 = 3.38+), all supported.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop the orphaned recovery column left behind by MIGRATION_4_5
                // (fff19a2 removed it from the Entity). ALTER TABLE DROP COLUMN
                // requires SQLite >= 3.35 (Android 12+ = 3.32, Android 15 = 3.38+),
                // which is NOT available on our minSdk=26. Use the standard
                // CREATE → INSERT → DROP → RENAME pattern instead.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS provider_model_groups_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        strategy TEXT NOT NULL,
                        fallback_strategy TEXT NOT NULL,
                        default_thinking_level TEXT,
                        context_limit_tokens INTEGER,
                        last_context_limit_tokens INTEGER,
                        member_entry_ids_json TEXT NOT NULL,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO provider_model_groups_new (
                        id, name, strategy, fallback_strategy, default_thinking_level,
                        context_limit_tokens, last_context_limit_tokens, member_entry_ids_json, sort_order
                    )
                    SELECT id, name, strategy, fallback_strategy, default_thinking_level,
                        context_limit_tokens, last_context_limit_tokens, member_entry_ids_json, sort_order
                    FROM provider_model_groups
                """.trimIndent())
                db.execSQL("DROP TABLE IF EXISTS provider_model_groups")
                db.execSQL("ALTER TABLE provider_model_groups_new RENAME TO provider_model_groups")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // [T-recovery] Cost tier for cheapestFirst group strategy.
                // Nullable — existing rows are unannotated (sort last).
                db.execSQL("ALTER TABLE provider_model_entries ADD COLUMN cost_tier INTEGER")
            }
        }

        /**
         * [T-android-thinking-rules-phase2] Create the user-authored custom
         * thinking-rules table (parity with upstream iOS 4d7fb9b4). Pure additive
         * CREATE — no existing row is touched. Custom rules are per-provider-instance
         * user data, orthogonal to the config-snapshot round-trip; built-in vendor
         * rules are never stored.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_thinking_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        provider_instance_id TEXT NOT NULL,
                        label TEXT NOT NULL,
                        scope_kind TEXT NOT NULL,
                        scope_pattern TEXT,
                        wire_format_json TEXT,
                        reasoning_echo_json TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_provider_thinking_rules_provider_instance_id " +
                        "ON provider_thinking_rules(provider_instance_id)",
                )
            }
        }

        fun getInstance(context: Context): ProviderDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProviderDatabase::class.java,
                    "provider.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
