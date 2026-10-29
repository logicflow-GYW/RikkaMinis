package com.rikkaminis.app.config

import com.rikkaminis.app.config.confirm.ConfigAutoApprovePolicy
import com.rikkaminis.app.config.confirm.ConfigConfirmationGate
import com.rikkaminis.app.config.confirm.ConfirmOutcome
import com.rikkaminis.app.config.confirm.PendingConfigChange
import com.rikkaminis.app.config.confirm.PendingConfigChangeItem
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bridge between the offload handler (Kotlin / shell args / JSON
 * stdout) and the rest of the Config module (registry, gate). Mirrors
 * iOS `ConfigOffloadBridge.swift`.
 *
 * Returns plain [JSONObject] envelopes so the handler just wraps them
 * in [com.rikkaminis.app.sandbox.NativeOffloadResult]. The handler
 * thread is on a background pool, so write paths block via runBlocking
 * on the gate suspend — matching the iOS semaphore-on-detached-Task
 * pattern.
 */
object ConfigBridge {
    private const val TAG = "ConfigBridge"
    private const val DEFAULT_PAGE_SIZE = 20
    private const val MAX_PAGE_SIZE = 100

    /** First-line gate — returns true when the master switch is on. */
    fun isEnabled(): Boolean = MinisConfigPermissionStore.isEnabled

    /**
     * [T-android-config-feature-unavailable] Envelope for a field whose feature
     * does not exist on this device / OS version. Deliberately a DISTINCT code
     * from `permission_denied` so the agent knows this isn't something the user
     * can grant or the agent can retry into — the hardware/OS simply lacks it.
     * Mirrors iOS `unavailableErrorEnvelope` (dba46d42).
     */
    fun unavailableErrorEnvelope(path: String, reason: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", "feature_unavailable")
        put("reason", reason)
        put("user_message", "The setting '$path' isn't available on this device: $reason")
    }

    /** Standardised "minis-config disabled" envelope. Includes user_message. */
    fun disabledErrorEnvelope(): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", "permission_denied")
        put("reason", "minis-config is disabled in Settings → Permissions.")
        put(
            "user_message",
            "I tried to change a setting but minis-config is currently disabled. " +
                "You can enable it at [Settings → Permissions](minis://settings/permissions), " +
                "then ask me again. Or change the setting yourself directly through the relevant Settings screen."
        )
    }

    // -- list-topics / topic-help --

    fun allTopics(): JSONArray =
        JSONArray().also { arr -> for (t in ConfigRegistry.get().topics()) arr.put(t) }

    fun fieldsForTopic(topic: String): JSONArray =
        JSONArray().also { arr ->
            for (f in ConfigRegistry.get().fields(topic = topic)) {
                arr.put(JSONObject().apply {
                    put("path", f.path)
                    put("display_name", f.displayName)
                    put("description", f.description)
                    put("schema", f.valueSchema.helpDescription)
                    put("access", f.access.name.lowercase())
                    put("risk", f.risk.name.lowercase())
                    put("revertable", f.revertable)
                })
            }
        }

    // -- read --

    /**
     * Read a single field with optional filter + pagination. Both
     * filter and pagination only apply to JSON arrays; scalar values
     * pass through unchanged. Mirrors the iOS bridge contract exactly.
     */
    fun readField(
        path: String,
        filter: String? = null,
        page: Int = 0,
        pageSize: Int = 0,
    ): JSONObject {
        val base = readFieldRaw(path)
        if (base.optBoolean("ok", false) != true) return base

        val filterText = filter?.trim()?.takeIf { it.isNotEmpty() }
        val terms: List<String> = filterText
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val valueJSON = base.optString("value", "null")
        val parsed = ConfigValue.decode(valueJSON) ?: return base

        fun matches(candidate: ConfigValue): Boolean {
            if (terms.isEmpty()) return true
            val hay = candidate.jsonString().lowercase()
            return terms.all { hay.contains(it) }
        }

        val out = JSONObject()
        // Copy base.
        val keys = base.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, base.opt(k))
        }

        if (parsed is ConfigValue.Arr) {
            val totalRaw = parsed.value.size
            val postFilter = if (terms.isEmpty()) parsed.value else parsed.value.filter(::matches)
            val postFilterCount = postFilter.size

            if (terms.isNotEmpty()) {
                out.put("filtered", true)
                out.put("filter", filterText ?: "")
                out.put("total", totalRaw)
                out.put("matched", postFilterCount)
            }

            val wantsPagination = page > 0 || pageSize > 0
            if (wantsPagination) {
                val size = (if (pageSize > 0) pageSize else DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
                val totalPages = maxOf(1, (postFilterCount + size - 1) / size)
                val requestedPage = if (page > 0) page else 1
                val pageSlice: List<ConfigValue> =
                    if (requestedPage < 1 || requestedPage > totalPages || postFilterCount == 0) {
                        emptyList()
                    } else {
                        val start = (requestedPage - 1) * size
                        val end = minOf(start + size, postFilterCount)
                        postFilter.subList(start, end)
                    }
                out.put("value", ConfigValue.Arr(pageSlice).jsonString())
                val hasNext = requestedPage < totalPages && postFilterCount > 0
                val hasPrev = requestedPage > 1
                out.put("pagination", JSONObject().apply {
                    put("page", requestedPage)
                    put("page_size", size)
                    put("total", postFilterCount)
                    put("total_pages", totalPages)
                    put("has_next", hasNext)
                    put("has_prev", hasPrev)
                })
                out.put(
                    "agent_hint",
                    paginationHint(
                        path = path, filter = filterText,
                        page = requestedPage, pageSize = size,
                        totalPages = totalPages, total = postFilterCount,
                        pageCount = pageSlice.size,
                    )
                )
            } else if (terms.isNotEmpty()) {
                out.put("value", ConfigValue.Arr(postFilter).jsonString())
            }
        } else if (terms.isNotEmpty()) {
            // Scalar value with filter — pass-or-null.
            val kept: ConfigValue = if (matches(parsed)) parsed else ConfigValue.Null
            out.put("value", kept.jsonString())
            out.put("filtered", true)
            out.put("filter", filterText ?: "")
            out.put("total", 1)
            out.put("matched", if (kept === ConfigValue.Null) 0 else 1)
        }

        return out
    }

    private fun readFieldRaw(path: String): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val field = ConfigRegistry.get().resolveField(path) ?: return JSONObject().apply {
            put("ok", false)
            put("error", "unknown_path")
            put("reason", "No registered field at '$path'.")
        }
        if (field.access == ConfigAccess.HIDDEN) return JSONObject().apply {
            put("ok", false)
            put("error", "permission_denied")
            put("reason", "'$path' is intentionally not exposed to minis-config.")
        }
        // [T-android-config-feature-unavailable] The device/OS lacks the
        // feature entirely — answer precisely instead of reporting a value the
        // user can't act on.
        field.unavailableReason?.let { return unavailableErrorEnvelope(path, it) }
        return try {
            val v = field.read()
            JSONObject().apply {
                put("ok", true)
                put("value", v.jsonString())
                put("schema", field.valueSchema.helpDescription)
                put("display_name", field.displayName)
            }
        } catch (e: ConfigError.PermissionDenied) {
            // [T-minis-config-provider-add] Forward PermissionDenied verbatim
            // — used by fields that are editable but unreadable (notably
            // providers.<id>.apiKey: writes accepted, reads guarded). Without
            // this branch the previous generic catch reported "read_failed"
            // which is misleading and would confuse the agent's retry logic.
            JSONObject().apply {
                put("ok", false)
                put("error", "permission_denied")
                put("reason", e.message ?: "Read access denied.")
            }
        } catch (e: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("error", "read_failed")
                put("reason", e.message ?: e.toString())
            }
        }
    }

    private fun paginationHint(
        path: String,
        filter: String?,
        page: Int,
        pageSize: Int,
        totalPages: Int,
        total: Int,
        pageCount: Int,
    ): String {
        val filterFragment = if (filter.isNullOrEmpty()) "" else " --filter \"$filter\""
        if (total == 0) {
            return if (!filter.isNullOrEmpty()) {
                "No items match filter '$filter' under '$path'."
            } else {
                "No items under '$path'."
            }
        }
        if (page > totalPages) {
            val pgWord = if (totalPages == 1) "page" else "pages"
            return "Page $page is out of range (only $totalPages $pgWord available). " +
                "Try: minis-config get $path$filterFragment --page $totalPages"
        }
        if (totalPages <= 1) {
            val itemWord = if (total == 1) "item" else "items"
            return "Showing all $total $itemWord (1 page)."
        }
        if (page < totalPages) {
            return "Showing page $page of $totalPages ($pageCount of $total items). " +
                "To get more, use: minis-config get $path$filterFragment --page ${page + 1} --page-size $pageSize"
        }
        return "Showing page $page of $totalPages ($pageCount of $total items). This is the last page."
    }

    // -- write (single or batch) --

    /**
     * Write one or more fields. Each item is a JSON object
     * `{ "path": ..., "value_json": ... }`. Returns the standard
     * envelope including `applied[]` and `user_message`.
     */
    fun writeFields(
        items: JSONArray,
        caption: String?,
        actorRaw: String,
        sessionId: String?,
    ): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        // Bridge runs on the offload worker thread; the gate itself
        // does its own context switch to Main for the dialog.
        return runBlocking {
            performWriteBatch(
                items = items,
                caption = caption,
                actorRaw = actorRaw,
                sessionId = sessionId,
                skipConfirmation = false,
            )
        }
    }

    /**
     * Internal write path — used by [writeFields] and routed through the
     * confirmation gate. `skipConfirmation` lets the debug/offload RPC apply
     * without a UI dialog when the caller has already collected consent.
     *
     * [R5-E F-03] OUTCOME CONTRACT (every arm answers one of exactly four
     * shapes — the whole-batch verdict is built by [buildWriteEnvelope]):
     *
     * | situation                                   | `ok`  | `error`           | payload                |
     * |---------------------------------------------|-------|-------------------|------------------------|
     * | nothing applied, some row failed            | false | row's own code    | `reason`               |
     * | nothing applied, nothing failed             | false | `all_rejected`    | `user_message`         |
     * | some applied, **some failed**               | false | `partial_write`   | `applied[]` + `failed[]` |
     * | everything applied                          | true  | —                 | `applied[]`            |
     *
     * The partial case is the one this used to get wrong: as long as ONE row
     * applied it answered `ok=true` / "Settings updated.", so
     * `minis-config set-batch` exited 0 and the agent told the user all N
     * settings were saved while row 2 had been refused. `ok` stays false
     * because it is the field the offload handler turns into the process exit
     * code (see `ConfigOffloadHandler.exitCodeFromEnvelope`) — a
     * successful-looking envelope would exit 0 again. `applied[]` is carried
     * either way, so nothing that DID land is hidden from the caller.
     *
     * A cancelled coroutine is not a write outcome: [classifyWriteFailure]
     * answers null for `CancellationException` and the loop rethrows, so
     * cancellation reaches the caller instead of being recorded as a failed
     * row.
     */
    suspend fun performWriteBatch(
        items: JSONArray,
        caption: String?,
        actorRaw: String,
        sessionId: String?,
        skipConfirmation: Boolean,
    ): JSONObject {
        // 1. Resolve every path; reject the entire batch on any unknown
        //    or hidden path before bothering the user.
        val resolved = ArrayList<Resolved>(items.length())
        val resolvedItems = ArrayList<PendingConfigChangeItem>(items.length())
        for (i in 0 until items.length()) {
            val raw = items.optJSONObject(i) ?: continue
            val rawPath = raw.optString("path", "")
            val valueJSON = raw.optString("value_json", "null")

            val isAppend = rawPath.endsWith(".append")
            val isRemove = !isAppend && rawPath.endsWith(".remove")
            val resolvePath = when {
                isAppend -> rawPath.removeSuffix(".append")
                isRemove -> rawPath.removeSuffix(".remove")
                else -> rawPath
            }

            // Collection-level append/remove: when `<base>.append` or
            // `<base>.remove` addresses a registered ConfigCollection
            // (not a field), route to collection.add() / remove(). The
            // collection owns dedup, validation, and is_custom enforcement.
            if ((isAppend || isRemove) && ConfigRegistry.get().collection(resolvePath) != null) {
                val coll = ConfigRegistry.get().collection(resolvePath)!!
                val parsedValue = ConfigValue.decode(valueJSON) ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "invalid_value")
                    put("reason", "Value JSON is not parseable for '$rawPath'.")
                }
                if (isAppend && !coll.addable) {
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", "permission_denied")
                        put("reason", "Collection '${coll.basePath}' does not allow .append.")
                    }
                }
                if (isRemove && !coll.removable) {
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", "permission_denied")
                        put("reason", "Collection '${coll.basePath}' does not allow .remove.")
                    }
                }
                // [T-minis-config-provider-add] Redact credential values
                // (apiKey / oauthToken / manualOAuthToken) BEFORE they reach
                // the confirmation sheet. The collection's
                // add() still receives the un-redacted `parsedValue` so it
                // can persist the real secret to the encrypted store; only
                // the displayed copy is masked. A `$$ENV`
                // reference passes through (it's a pointer, not the
                // secret). Mirrors iOS ConfigOffloadBridge:435-441.
                val redactedNewValue = if (isAppend) parsedValue.redactingSecrets() else parsedValue
                resolved.add(
                    Resolved(
                        field = null,
                        collection = coll,
                        collectionVerb = if (isAppend) CollectionVerb.APPEND else CollectionVerb.REMOVE,
                        rawPath = rawPath,
                        scope = coll.basePath,
                        oldValue = ConfigValue.Null,
                        newValue = parsedValue,
                        // Audit / display copy is always the redacted form;
                        // the executor still applies `newValue` un-redacted.
                        auditNewValue = redactedNewValue,
                    )
                )
                resolvedItems.add(
                    PendingConfigChangeItem(
                        displayName = coll.displayName,
                        path = rawPath,
                        oldDisplay = if (isAppend) "" else parsedValue.displayString,
                        newDisplay = if (isAppend) redactedNewValue.displayString else "",
                        verb = if (isAppend) "add" else "remove",
                        risk = coll.risk,
                    )
                )
                continue
            }

            val field = ConfigRegistry.get().resolveField(resolvePath)
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "unknown_path")
                    put("reason", "No registered field at '$rawPath'.")
                }

            // [T-android-config-feature-unavailable] Feature-unavailable gate
            // runs BEFORE the access check, before validation, and before the
            // confirmation dialog — never ask the user to approve a change that
            // cannot possibly apply on this device (e.g. the Android 16 Live
            // Updates surface on hardware without it).
            field.unavailableReason?.let { return unavailableErrorEnvelope(rawPath, it) }

            if (field.access != ConfigAccess.READWRITE) {
                val reason = if (field.access == ConfigAccess.HIDDEN) {
                    "'$rawPath' is intentionally not exposed to minis-config."
                } else {
                    "'$rawPath' is read-only."
                }
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "permission_denied")
                    put("reason", reason)
                }
            }

            val parsedValue = ConfigValue.decode(valueJSON) ?: return JSONObject().apply {
                put("ok", false)
                put("error", "invalid_value")
                put("reason", "Value JSON is not parseable for '$rawPath'.")
            }

            val oldValue: ConfigValue = try {
                field.read()
            } catch (_: Throwable) {
                ConfigValue.Null
            }

            val newValue: ConfigValue
            val displayVerb: String
            val displayPath: String
            val displayOld: String
            val displayNew: String

            when {
                isAppend -> {
                    val arrSchema = field.valueSchema as? ConfigSchema.Array
                        ?: return JSONObject().apply {
                            put("ok", false)
                            put("error", "invalid_value")
                            put("reason", "'.append' is only valid on array-typed fields; '${field.path}' is not an array.")
                        }
                    try {
                        arrSchema.inner.validate(parsedValue)
                    } catch (e: ConfigError) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "validation_failed")
                            put("reason", e.message ?: e.toString())
                        }
                    }
                    val existing = (oldValue as? ConfigValue.Arr)?.value ?: emptyList()
                    newValue = ConfigValue.Arr(existing + parsedValue)
                    displayVerb = "append"
                    displayPath = rawPath
                    displayOld = ""
                    displayNew = parsedValue.displayString
                }
                isRemove -> {
                    if (field.valueSchema !is ConfigSchema.Array) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "invalid_value")
                            put("reason", "'.remove' is only valid on array-typed fields; '${field.path}' is not an array.")
                        }
                    }
                    val existing = (oldValue as? ConfigValue.Arr)?.value ?: emptyList()
                    val filtered = existing.filter { it != parsedValue }
                    if (filtered.size == existing.size) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "not_found")
                            put("reason", "No occurrence of value ${parsedValue.displayString} in '${field.path}'.")
                        }
                    }
                    newValue = ConfigValue.Arr(filtered)
                    displayVerb = "remove"
                    displayPath = rawPath
                    displayOld = parsedValue.displayString
                    displayNew = ""
                }
                else -> {
                    try {
                        field.valueSchema.validate(parsedValue)
                    } catch (e: ConfigError) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "validation_failed")
                            put("reason", e.message ?: e.toString())
                        }
                    }
                    newValue = parsedValue
                    displayVerb = "set"
                    displayPath = field.path
                    displayOld = oldValue.displayString
                    displayNew = newValue.displayString
                }
            }

            // [T-minis-config-provider-add] Mask credential writes the
            // same way collection-add does: detect by the path's last
            // segment matching ConfigValue.SECRET_KEYS (apiKey /
            // oauthToken / manualOAuthToken). Field still gets the un-
            // redacted newValue (so the writer can persist the real
            // secret); only audit / display copies are masked. `$$ENV`
            // references are preserved by redactingSecrets.
            val isSecretField = field.path.substringAfterLast('.') in ConfigValue.SECRET_KEYS
            val redactedNewValue = if (isSecretField) maskScalarSecret(newValue) else newValue
            resolved.add(
                Resolved(
                    field = field,
                    collection = null,
                    collectionVerb = null,
                    rawPath = field.path,
                    scope = field.scope,
                    oldValue = oldValue,
                    newValue = newValue,
                    auditNewValue = if (isSecretField) redactedNewValue else null,
                )
            )
            resolvedItems.add(
                PendingConfigChangeItem(
                    displayName = field.displayName,
                    path = displayPath,
                    oldDisplay = displayOld,
                    newDisplay = if (isSecretField) redactedNewValue.displayString else displayNew,
                    verb = displayVerb,
                    risk = field.risk,
                )
            )
        }

        // 2. Enqueue confirmation. Suspends until user acts or timeout —
        //    or skipped when the caller already collected user consent
        //    (see [skipConfirmation] doc).
        //
        //    [auto-approve] The user's opt-in "免确认模式" skips the dialog for
        //    batches that pass [ConfigAutoApprovePolicy] (all-NORMAL rows by
        //    default; SENSITIVE rows only behind their sub-flag; DESTRUCTIVE
        //    rows never). Every skipped dialog is logged so the change remains
        //    traceable in Logs → Config Changes.
        //
        //    A Resolved without a ConfigField (collection verbs like
        //    `envvars.<key>`) carries no declared risk level — it maps to
        //    SENSITIVE, i.e. confirmation is KEPT by default. Unscoped writes
        //    touch env-var credentials too often to assume they are routine.
        val resolvedRisks = resolved.map { it.field?.risk ?: ConfigRisk.SENSITIVE }
        val autoSkip = !skipConfirmation &&
            ConfigAutoApprovePolicy.shouldSkip(
                risks = resolvedRisks,
                autoApproveEnabled = AutoApproveStore.isEnabled,
                sensitiveAutoApproveEnabled = AutoApproveStore.isSensitiveEnabled,
            )
        if (autoSkip) {
            AppLogger.info(
                TAG,
                "auto-approve: dialog skipped for ${resolved.size} item(s) " +
                    "(risk=${resolvedRisks.joinToString { it.name }} actor=$actorRaw)",
            )
        }
        val outcome: ConfirmOutcome = when {
            skipConfirmation || autoSkip -> ConfirmOutcome.Approved(resolvedItems)
            else -> {
                val pending = PendingConfigChange(items = resolvedItems, caption = caption)
                ConfigConfirmationGate.requestConfirmation(pending)
            }
        }

        // 3. Map outcome → confirmation status/user_message.
        return when (outcome) {
            is ConfirmOutcome.TimedOut -> {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "timeout")
                    put("reason", "Confirmation timed out after ${ConfigConfirmationGate.TIMEOUT_MS / 1000}s.")
                    put("user_message", "I waited but you didn't confirm the change. Tap me again if you want me to retry.")
                }
            }
            is ConfirmOutcome.Rejected -> {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "user_rejected")
                    put("reason", "User cancelled the change.")
                    put("user_message", "Change cancelled.")
                }
            }
            is ConfirmOutcome.Approved -> {
                val applied = JSONArray()
                // [R5-E F-03] Per-row failures, in the order they happened.
                // `failed` is what lets a partial batch answer precisely
                // (`partial_write` + which items failed) instead of the old
                // all-or-nothing pair of `earlyError` / `applied`.
                val failed = JSONArray()
                // Per-row error envelope captured for any collection.add
                // that surfaces a structural error (already_exists,
                // permission_denied) — we abort the batch and surface
                // that as the call's primary error so the agent's
                // contract holds even when the user pre-approved.
                var earlyError: JSONObject? = null
                for ((idx, row) in outcome.items.withIndex()) {
                    if (idx >= resolved.size) continue
                    val r = resolved[idx]
                    if (!row.isApproved) {
                        continue
                    }
                    try {
                        // Two op kinds: field write vs collection add/remove.
                        var effectiveOldJSON = r.oldValue.jsonString()
                        var effectiveNewJSON = r.newValue.jsonString()
                        var effectiveOldDisplay = r.oldValue.displayString
                        var effectiveNewDisplay = r.newValue.displayString
                        val displayName: String
                        val displayPath: String
                        if (r.collection != null && r.collectionVerb != null) {
                            when (r.collectionVerb) {
                                CollectionVerb.APPEND -> {
                                    // newValue is the un-redacted payload —
                                    // the collection persists the real
                                    // credential. Display rows pull from
                                    // auditNewValue (redacted copy) when
                                    // supplied so the secret never lands on
                                    // the user's screen or write envelope.
                                    val newId = r.collection.add(r.newValue)
                                    val auditPayload = r.auditNewValue ?: r.newValue
                                    val withId = augmentWithEntryId(auditPayload, newId)
                                    effectiveNewJSON = withId.jsonString()
                                    effectiveNewDisplay = withId.displayString
                                }
                                CollectionVerb.REMOVE -> {
                                    val entryId = (r.newValue as? ConfigValue.Str)?.value
                                        ?: throw ConfigError.InvalidValue(
                                            "${r.collection.basePath}.remove expects a string entry id."
                                        )
                                    // Snapshot the entry pre-removal so
                                    // revert can re-add it verbatim.
                                    val snapshot = snapshotCollectionChild(r.collection, entryId)
                                    r.collection.remove(entryId)
                                    effectiveOldJSON = snapshot.jsonString()
                                    effectiveOldDisplay = snapshot.displayString
                                    effectiveNewJSON = ConfigValue.Null.jsonString()
                                    effectiveNewDisplay = ""
                                }
                            }
                            displayName = r.collection.displayName
                            displayPath = r.rawPath
                        } else if (r.field != null) {
                            r.field.write(r.newValue)
                            displayName = r.field.displayName
                            displayPath = r.field.path
                            // [T-minis-config-provider-add] If this is a
                            // credential field, display rows pull from the
                            // redacted copy so the secret never reaches the
                            // user's screen or write envelope.
                            if (r.auditNewValue != null) {
                                effectiveNewJSON = r.auditNewValue.jsonString()
                                effectiveNewDisplay = r.auditNewValue.displayString
                            }
                        } else {
                            continue
                        }
                        applied.put(JSONObject().apply {
                            put("path", displayPath)
                            put("display_name", displayName)
                            put("old", effectiveOldDisplay)
                            put("new", effectiveNewDisplay)
                        })
                    } catch (e: Throwable) {
                        // [R5-E F-03] ONE arm for every row failure; the code
                        // mapping lives in [classifyWriteFailure] so it can be
                        // asserted off-device. Two defects lived in the four
                        // separate arms this replaces:
                        //  * a non-ConfigError (IllegalState, SQLite, …) was only
                        //    logged by the last arm [r3-b08 F-10] — it fell
                        //    through to `all_rejected` / "User rejected every
                        //    row." for a change the user had just approved;
                        //  * nothing rethrew CancellationException, so a
                        //    cancelled batch recorded the cancellation as a
                        //    failed row and then reported the rows that had
                        //    already landed as `ok:true` ("Settings updated.").
                        val failure = classifyWriteFailure(e)
                        if (failure == null) throw e
                        val (code, reason) = failure
                        failed.put(writeFailureEntry(r.rawPath, code, reason))
                        if (earlyError == null) {
                            earlyError = JSONObject().apply {
                                put("ok", false)
                                put("error", code)
                                put("reason", reason)
                            }
                        }
                        // Log level mirrors the old arms: a classified
                        // ConfigError is a rejected value (warning), anything
                        // else is the genuine write_failed path (error).
                        if (code == "write_failed") {
                            AppLogger.error(TAG, "write failed for ${r.rawPath}: ${e.message}")
                        } else {
                            AppLogger.warning(TAG, "${r.rawPath}: ${e.message}")
                        }
                    }
                }
                buildWriteEnvelope(applied = applied, failed = failed, earlyError = earlyError)
            }
        }
    }

    /**
     * One resolved entry in a write batch. Either a field op (when
     * [field] is non-null) or a collection-level add/remove (when
     * [collection] + [collectionVerb] are set). Collection ops carry
     * their own [rawPath] (e.g. "models.append") and [scope] (the
     * collection basePath, e.g. "models") because there is no
     * ConfigField to source them from.
     */
    private data class Resolved(
        val field: ConfigField?,
        val collection: ConfigCollection?,
        val collectionVerb: CollectionVerb?,
        val rawPath: String,
        val scope: String,
        val oldValue: ConfigValue,
        val newValue: ConfigValue,
        // [T-minis-config-provider-add] When non-null, audit / display
        // logging uses this instead of `newValue` — the executor still
        // applies `newValue` un-redacted so the credential reaches the
        // collection's add(). null = no redaction needed (use newValue
        // for both surfaces).
        val auditNewValue: ConfigValue? = null,
    )

    private enum class CollectionVerb { APPEND, REMOVE }

    /**
     * Mask a scalar credential write. Mirrors the per-key logic in
     * [ConfigValue.redactingSecrets] but for the case where the secret
     * IS the whole value (e.g. `providers.<id>.apiKey = "sk-…"`),
     * which the object-walking redactor wouldn't touch. `$$ENV`
     * references pass through (they're a pointer, not the secret);
     * empty strings pass through (used to clear the credential).
     * Non-string values are returned untouched (shouldn't happen for
     * credential fields, but defensive).
     */
    private fun maskScalarSecret(v: ConfigValue): ConfigValue {
        if (v !is ConfigValue.Str) return v
        if (v.value.startsWith("\$\$")) return v
        if (v.value.isEmpty()) return v
        return ConfigValue.Str("••• (hidden)")
    }

    /**
     * Splice the just-created entry id into the returned payload so the
     * write envelope's `new` display shows the id the collection minted.
     */
    private fun augmentWithEntryId(payload: ConfigValue, entryId: String): ConfigValue {
        val baseMap = (payload as? ConfigValue.Obj)?.value?.toMutableMap()
            ?: LinkedHashMap()
        baseMap["entry_id"] = ConfigValue.Str(entryId)
        return ConfigValue.Obj(baseMap)
    }

    /**
     * Build a JSON snapshot of a collection child by walking its
     * exposed fields. Used by the .remove path so the applied row's
     * `old` value shows the full removed child. Only includes scalar /
     * array fields the collection actually surfaces.
     */
    private fun snapshotCollectionChild(coll: ConfigCollection, childId: String): ConfigValue {
        val out = LinkedHashMap<String, ConfigValue>()
        out["entry_id"] = ConfigValue.Str(childId)
        for (field in coll.fields(forId = childId)) {
            val leaf = field.path.substringAfterLast('.', missingDelimiterValue = field.path)
            val v = try { field.read() } catch (_: Throwable) { ConfigValue.Null }
            out[leaf] = v
        }
        return ConfigValue.Obj(out)
    }
}

/**
 * [R5-E F-03] Classify one row's write failure into the envelope's
 * `(error, reason)` pair.
 *
 * Returns **null** for [CancellationException]: a cancelled coroutine is not a
 * failed write. The single `catch (e: Throwable)` this replaces recorded the
 * cancellation as `write_failed` and then answered `ok:true` for whatever had
 * already landed, so the caller never learned the batch had been abandoned.
 * The null return is the ONE expression of that rule — the apply loop rethrows
 * on null rather than repeating an `is CancellationException` test.
 *
 * Order matters: [ConfigError.AlreadyExists] and
 * [ConfigError.PermissionDenied] are [ConfigError] subclasses, and each keeps
 * the exact code string the pre-existing per-arm catches answered, so a caller
 * that branched on `error` sees no change.
 */
internal fun classifyWriteFailure(e: Throwable): Pair<String, String>? = when {
    e is CancellationException -> null
    e is ConfigError.AlreadyExists -> "already_exists" to (e.message ?: "already exists")
    e is ConfigError.PermissionDenied -> "permission_denied" to (e.message ?: "permission denied")
    e is ConfigError -> "invalid_value" to (e.message ?: "invalid value")
    else -> "write_failed" to (e.message ?: "Write failed.")
}

/** [R5-E F-03] One `failed[]` row: which path, which code, why. */
internal fun writeFailureEntry(path: String, error: String, reason: String): JSONObject =
    JSONObject().apply {
        put("path", path)
        put("error", error)
        put("reason", reason)
    }

/**
 * [R5-E F-03] The whole-batch verdict, from the three lists the apply loop
 * fills. Pure and total, so the partial-write contract is testable off-device
 * (the loop around it needs the confirmation gate, the registry and a live
 * repository).
 *
 * Arms, in order — see the contract table on
 * [ConfigBridge.performWriteBatch]:
 *  1. nothing applied, a failure recorded → that failure's envelope (the
 *     pre-existing shape: `ok=false` + the row's own `error`);
 *  2. nothing applied, no failure → `all_rejected` (unchanged);
 *  3. some applied **and** some failed → `ok=false` + `error="partial_write"` +
 *     `applied[]` + `failed[]`;
 *  4. else → `ok=true` + `applied[]` (unchanged).
 *
 * Arm 3 is the fix: it used to fall into arm 4, so one refused row out of N
 * was reported as "Settings updated." with exit code 0.
 */
internal fun buildWriteEnvelope(
    applied: JSONArray,
    failed: JSONArray,
    earlyError: JSONObject?,
): JSONObject {
    val appliedCount = applied.length()
    val failedCount = failed.length()
    return when {
        appliedCount == 0 && earlyError != null -> earlyError
        appliedCount == 0 -> JSONObject().apply {
            put("ok", false)
            put("error", "all_rejected")
            put("reason", "User rejected every row.")
            put("user_message", "No changes applied.")
        }
        failedCount > 0 -> JSONObject().apply {
            put("ok", false)
            put("error", "partial_write")
            put("reason", "$appliedCount change(s) applied, $failedCount failed.")
            put("applied", applied)
            put("failed", failed)
            put(
                "user_message",
                "Only part of that went through: $appliedCount setting(s) were saved and " +
                    "$failedCount failed. Retry just the failed ones — nothing was rolled back.",
            )
        }
        else -> JSONObject().apply {
            put("ok", true)
            put("applied", applied)
            put("user_message", "Settings updated.")
        }
    }
}
