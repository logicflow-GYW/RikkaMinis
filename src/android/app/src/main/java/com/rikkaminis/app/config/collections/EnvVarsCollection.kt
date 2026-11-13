package com.rikkaminis.app.config.collections

import com.rikkaminis.app.config.ConfigCollection
import com.rikkaminis.app.config.ConfigError
import com.rikkaminis.app.config.ConfigField
import com.rikkaminis.app.config.ConfigRisk
import com.rikkaminis.app.config.ConfigSchema
import com.rikkaminis.app.config.ConfigValue
import com.rikkaminis.app.config.fields.ClosureField
import com.rikkaminis.app.config.fields.HiddenField
import com.rikkaminis.app.config.fields.ReadOnlyField
import com.rikkaminis.app.data.repository.EnvVarRepository
import java.util.Locale

/**
 * Exposes `EnvVarRepository` entries under `envvars.<key>.…`. Mirrors
 * iOS `EnvVarsCollection`.
 *
 * IMPORTANT: values are NEVER exposed — neither read nor write here.
 * The agent can list keys, attach a note, or remove a variable, but
 * the actual secret only ever moves through the encrypted prefs and
 * the user-facing UI. Adding a variable goes through the deep link
 * `minis://settings/environments?create_key=…` so the user supplies
 * the value directly.
 */
class EnvVarsCollection(
    private val repo: EnvVarRepository,
) : ConfigCollection {
    override val basePath: String get() = "envvars"
    override val displayName: String get() = "Environment variables"
    override val description: String get() =
        "Metadata only — values are stored in encrypted prefs and never exposed."
    override val addable: Boolean get() = false
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    // [audit-0917] DateTimeFormatter (immutable, thread-safe) instead of a
    // shared SimpleDateFormat instance — this collection is read from config
    // snapshots on arbitrary threads, and SimpleDateFormat's mutable calendar
    // makes concurrent format() calls interleave.
    private val isoFormatter: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .withZone(java.time.ZoneOffset.UTC)

    override fun childIds(): List<String> = repo.entries.value.map { it.key }

    override fun fields(forId: String): List<ConfigField> {
        val entry = repo.entries.value.firstOrNull { it.key == forId } ?: return emptyList()
        return listOf(
            createdAtField(forId),
            noteField(forId),
            groupField(forId),
            // Hidden guard: an agent attempt to read or write the
            // value gets a precise permission_denied.
            HiddenField(
                path = "envvars.$forId.value",
                displayName = "Value",
                description = "Hidden — manage via Settings → Environment Variables.",
                reason = "Env var values are stored in encrypted prefs and never exposed to minis-config",
            ),
        )
    }

    override fun add(payload: ConfigValue): String {
        throw ConfigError.PermissionDenied(
            "Use the deep link `minis://settings/environments?create_key=…` so the user enters the value directly"
        )
    }

    override fun remove(id: String) {
        val entry = repo.entries.value.firstOrNull { it.key == id }
            ?: throw ConfigError.UnknownPath("envvars.$id")
        repo.delete(entry.id)
    }

    private fun createdAtField(key: String): ConfigField =
        ReadOnlyField(
            path = "envvars.$key.createdAt",
            displayName = "Created at",
            description = "ISO-8601 timestamp.",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null
                else ConfigValue.Str(
                    isoFormatter.format(java.time.Instant.ofEpochMilli(e.createdAt)),
                )
            },
        )

    private fun noteField(key: String): ConfigField =
        ClosureField(
            path = "envvars.$key.note",
            displayName = "Note",
            description = "Free-form description (not secret). Empty string clears it.",
            valueSchema = ConfigSchema.Str(maxLength = 500),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null
                else ConfigValue.Str(e.note)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val e = repo.entries.value.firstOrNull { it.key == key }
                    ?: throw ConfigError.UnknownPath("envvars.$key")
                // Metadata-only: if encrypted prefs is unavailable or the
                // key has no stored value, a note edit must never synthesize
                // an empty secret. A readable value can still use the normal
                // update path so key validation and value sanitization remain
                // centralized there.
                val currentValue = repo.getValue(e.key)
                if (currentValue == null) {
                    repo.updateNote(e.id, s, e.group)
                } else {
                    repo.update(e.id, e.key, currentValue, s, e.group)
                }
            },
        )

    private fun groupField(key: String): ConfigField =
        ClosureField(
            path = "envvars.$key.group",
            displayName = "Group",
            description = "Grouping label shown as a section header. Empty string clears it.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null
                else ConfigValue.Str(e.group)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val e = repo.entries.value.firstOrNull { it.key == key }
                    ?: throw ConfigError.UnknownPath("envvars.$key")
                // [R8-1114 A-F02] Same rule as the note writer: metadata-only.
                // A null read means prefs holds no value for this key (metadata
                // without a secret, or a keystore-degraded store), and writing
                // `""` through update() would replace a real secret with an
                // empty one. An empty string still clears the GROUP here.
                val currentValue = repo.getValue(e.key)
                if (currentValue == null) {
                    repo.updateNote(e.id, e.note, s)
                } else {
                    repo.update(e.id, e.key, currentValue, e.note, s)
                }
            },
        )
}
