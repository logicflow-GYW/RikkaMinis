package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.usage.InternalSourceStats
import com.rikkaminis.app.data.usage.InternalUsageSources
import com.rikkaminis.app.data.usage.ModelIdentity
import com.rikkaminis.app.data.usage.UNKNOWN_PROVIDER
import com.rikkaminis.app.data.usage.UsageAggregator
import com.rikkaminis.app.data.usage.UsageRow
import com.rikkaminis.app.data.usage.aggregateInternalUsageBySource
import com.rikkaminis.app.data.usage.toRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ModelStats(
    val modelId: String,
    val displayName: String,
    val provider: String,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    // Pre-aggregated counts: the SQL fast path returns them directly; the
    // legacy path derives them from the aggregator's distinct sets (only
    // .size was ever consumed).
    val distinctDays: Int = 0,
    val distinctSessions: Int = 0,
) {
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens
}

private data class ProviderGroup(val name: String, val models: List<ModelStats>)

/** Time-range filter for the usage page. ALL keeps the legacy full scan. */
private enum class UsageRange { ALL, DAYS_7, DAYS_30, DAYS_90 }

private data class GrandTotal(
    val totalInput: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
) {
    val cacheHitRate: Double?
        get() = if (totalInput <= 0 || cacheReadTokens <= 0) null
        else (cacheReadTokens.toDouble() / totalInput) * 100
}

@Composable
fun UsageStatsScreen(
    chatDao: ChatDao,
    providerConfig: ProviderConfig? = null,
    onBack: () -> Unit,
) {
    var grandTotal by remember { mutableStateOf(GrandTotal()) }
    var providerGroups by remember { mutableStateOf<List<ProviderGroup>>(emptyList()) }
    var internalSources by remember { mutableStateOf<List<InternalSourceStats>>(emptyList()) }
    var isLoaded by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(UsageRange.ALL) }

    LaunchedEffect(range, providerConfig) {
        // A (usage-stats-perf-1007): the whole compute used to run on the main
        // dispatcher — with enough history the spinner froze for seconds.
        // Query + aggregation off-main, then hand the results back to
        // composition (state writes stay on main).
        // `providerConfig` is a key too: computeUsageStats resolves custom
        // model entries out of it, so a config update must re-run the
        // aggregation instead of leaving the page on the previous provider /
        // model labelling.
        val (total, groups, ledgerRows) = withContext(Dispatchers.Default) {
            computeUsageStats(range, chatDao, providerConfig)
        }
        grandTotal = total
        providerGroups = groups
        internalSources = ledgerRows
        // Loading skeleton only on the first load; subsequent range flips keep
        // showing stale data instead of flashing blank. (T6-L2: isLoaded is set
        // to true at the end of this block and never reset, so the deleted
        // `if (!isLoaded) isLoaded = false` was a no-op that only looked like
        // half-written logic.)
        isLoaded = true
    }

    // top-level page: rely on system back gesture / bottom nav (no back arrow)
    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = null) {
        if (!isLoaded) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@SettingsScaffold
        }

        // Time range selector — All / 7d / 30d. Switching re-runs the
        // LaunchedEffect(range) query above.
        SettingsSection {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UsageRange.entries.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { if (range != r) range = r },
                        label = {
                            Text(
                                when (r) {
                                    UsageRange.ALL -> stringResource(R.string.usage_filter_all)
                                    UsageRange.DAYS_7 -> stringResource(R.string.usage_filter_7d)
                                    UsageRange.DAYS_30 -> stringResource(R.string.usage_filter_30d)
                                    UsageRange.DAYS_90 -> stringResource(R.string.usage_filter_90d)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                }
            }
        }

        SettingsSection(header = stringResource(R.string.usage_section_total)) {
            val stats = listOfNotNull(
                stringResource(R.string.usage_label_total_input) to formatCount(grandTotal.totalInput),
                stringResource(R.string.usage_label_output) to formatCount(grandTotal.outputTokens),
                if (grandTotal.cacheReadTokens > 0) stringResource(R.string.usage_label_cache_read) to formatCount(grandTotal.cacheReadTokens) else null,
                if (grandTotal.cacheCreationTokens > 0) stringResource(R.string.usage_label_cache_creation) to formatCount(grandTotal.cacheCreationTokens) else null,
                grandTotal.cacheHitRate?.let { rate -> stringResource(R.string.usage_label_cache_hit_rate) to String.format("%.1f%%", rate) },
            )
            stats.forEachIndexed { idx, (label, value) ->
                SettingsValueRow(
                    title = label,
                    value = value,
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    showDivider = idx < stats.size - 1,
                )
            }
        }

        // [T-internal-usage-events] Separate section on purpose: these tokens
        // never became a message, so they are not in the totals above — a user
        // reconciling the page against a per-token relay bill needs to see them
        // without them silently inflating the chat numbers.
        if (internalSources.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.usage_section_internal)) {
                internalSources.forEachIndexed { idx, source ->
                    SettingsValueRow(
                        title = stringResource(
                            R.string.usage_source_row_title,
                            internalSourceLabel(source.source),
                            source.requests,
                        ),
                        value = "${formatCount(source.totalInput)} / ${formatCount(source.outputTokens)}",
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        showDivider = idx < internalSources.size - 1,
                    )
                }
            }
        }

        for (group in providerGroups) {
            SettingsSection(header = group.name) {
                group.models.forEachIndexed { idx, model ->
                    ExpandableModelRow(
                        model = model,
                        showDivider = idx < group.models.size - 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private suspend fun computeUsageStats(
    range: UsageRange,
    chatDao: ChatDao,
    providerConfig: ProviderConfig?,
): Triple<GrandTotal, List<ProviderGroup>, List<InternalSourceStats>> {
    val now = System.currentTimeMillis()
    // Half-open window [since, until) — identical bounds to the legacy path.
    val window = when (range) {
        UsageRange.ALL -> 0L to Long.MAX_VALUE
        UsageRange.DAYS_7 -> (now - 7L * 24 * 60 * 60 * 1000) to (now + 1)
        UsageRange.DAYS_30 -> (now - 30L * 24 * 60 * 60 * 1000) to (now + 1)
        UsageRange.DAYS_90 -> (now - 90L * 24 * 60 * 60 * 1000) to (now + 1)
    }

    // modelId → (displayName, provider). [da1033-I06] The LIVE config wins over the
    // static catalog: a model the user renamed must show the renamed label, and a
    // catalog id that is also configured must not keep the catalog's hardcoded provider
    // ("Google" / "Kimi") — that split one Gemini instance across two group cards. The
    // catalog is still the fallback for ids the config does not know (historical rows).
    val modelLookup = UsageAggregator.modelDisplayLookup(
        configured = providerConfig?.modelEntries.orEmpty().map { entry ->
            val instance = providerConfig?.instances?.find { it.id == entry.providerInstanceId }
            ModelIdentity(
                modelId = entry.model.id,
                displayName = entry.model.displayName,
                provider = instance?.providerType?.displayName ?: entry.model.provider,
            )
        },
        builtins = LLMModel.allModels.map {
            ModelIdentity(it.id, it.displayName, it.provider)
        },
    )

    fun statsFor(
        modelId: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long,
        cacheReadTokens: Long,
        days: Int,
        sessions: Int,
    ): ModelStats {
        val (displayName, provider) = modelLookup[modelId] ?: (modelId to UNKNOWN_PROVIDER)
        return ModelStats(
            modelId = modelId,
            displayName = displayName,
            provider = provider,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheCreationTokens = cacheCreationTokens,
            cacheReadTokens = cacheReadTokens,
            distinctDays = days,
            distinctSessions = sessions,
        )
    }

    // C (usage-stats-perf-1007): SQL-side aggregation — one GROUP BY query
    // returns ~10 rows instead of materializing every usage row into Kotlin.
    // Requires SQLite JSON1: on platforms without it (minSdk 26 → Android 8
    // ships SQLite 3.18) the probe throws and we fall back to the legacy
    // full-materialization path below — same tolerance, just slower.
    val fast = try {
        if (chatDao.json1Probe() == 1) {
            chatDao.usageStatsAggregated(window.first, window.second)
        } else {
            null
        }
    } catch (e: CancellationException) {
        // [F3-ce-rethrow] A scope cancellation landing on the probe or the
        // aggregated query must unwind at the next suspend point, not be
        // misread as "JSON1 unavailable" (which would silently re-run the
        // legacy full-materialization path).
        throw e
    } catch (_: Exception) {
        null
    }

    val statsList: List<ModelStats> = if (fast != null) {
        fast.map { row ->
            statsFor(
                modelId = row.modelId,
                inputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                cacheCreationTokens = row.cacheCreationTokens,
                cacheReadTokens = row.cacheReadTokens,
                days = row.distinctDays,
                sessions = row.distinctSessions,
            )
        }
    } else {
        // Legacy path: full materialization + pure-JVM aggregator. Also the
        // behavioral reference the SQL path must stay in sync with.
        val records = when (range) {
            UsageRange.ALL -> chatDao.allUsageRecords()
            else -> chatDao.usageRecordsBetween(window.first, window.second)
        }
        // Device-local timezone day formatter for distinct-day bucketing
        // (matches the pre-refactor behavior). Created per-load — cheap.
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        UsageAggregator.aggregate(
            rows = records.map {
                UsageRow(it.modelId, it.tokenUsage, it.createdAt, it.sessionId)
            },
            dayFormat = { ms -> dateFormat.format(Date(ms)) },
        ).map { (modelId, stats) ->
            statsFor(
                modelId = modelId,
                inputTokens = stats.inputTokens,
                outputTokens = stats.outputTokens,
                cacheCreationTokens = stats.cacheCreationTokens,
                cacheReadTokens = stats.cacheReadTokens,
                days = stats.distinctDays.size,
                sessions = stats.distinctSessions.size,
            )
        }
    }

    val grouped = statsList.groupBy { it.provider }
    // [da1033-I06] Known providers keep their order, any other real provider follows
    // them, and the "Unknown" bucket (unattributed/modelId-less rows) is always last.
    val sortedGroups = grouped.entries.sortedBy { (name, _) ->
        UsageAggregator.providerGroupSortKey(name)
    }.map { (name, models) ->
        ProviderGroup(name, models.sortedByDescending { it.totalInput })
    }

    val total = GrandTotal(
        totalInput = statsList.sumOf { it.totalInput },
        outputTokens = statsList.sumOf { it.outputTokens },
        cacheReadTokens = statsList.sumOf { it.cacheReadTokens },
        cacheCreationTokens = statsList.sumOf { it.cacheCreationTokens },
    )

    // [T-internal-usage-events] Calls that never became a message (compaction
    // summary, session title, connection probe) live in their own ledger, so
    // they cannot be double-counted against the message-derived numbers above —
    // and they are shown as a separate section rather than folded into the
    // per-model rows, because folding them in would corrupt the distinct-day /
    // distinct-session counters the SQL path pre-aggregates as plain counts.
    // ponytail: separate section, not merged per model.
    // 天花板: no per-model split of internal spend, no day-over-day trend.
    // 升级触发: someone asks "which model burned my compaction tokens", or the
    // ledger grows enough that a per-day chart earns its own GROUP BY.
    val ledgerRows = aggregateInternalUsageBySource(
        chatDao.internalUsageEventsBetween(window.first, window.second).map { it.toRecord() },
    )
    return Triple(total, sortedGroups, ledgerRows)
}

/** Ledger source id → label; unknown ids fall back to the raw id. */
@Composable
private fun internalSourceLabel(source: String): String = when (source) {
    InternalUsageSources.COMPACT -> stringResource(R.string.usage_source_compact)
    InternalUsageSources.TITLE -> stringResource(R.string.usage_source_title)
    InternalUsageSources.QUICK_TEST -> stringResource(R.string.usage_source_quick_test)
    else -> source
}

@Composable
private fun ExpandableModelRow(model: ModelStats, showDivider: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val summary = "${formatCount(model.totalInput)} / ${formatCount(model.outputTokens)}"

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
                DetailRow(stringResource(R.string.usage_detail_input), formatCount(model.inputTokens))
                DetailRow(stringResource(R.string.usage_detail_output), formatCount(model.outputTokens))
                if (model.cacheReadTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_read), formatCount(model.cacheReadTokens))
                if (model.cacheCreationTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_creation), formatCount(model.cacheCreationTokens))
                val modelTotalInput = model.totalInput
                if (modelTotalInput > 0 && model.cacheReadTokens > 0) {
                    val rate = (model.cacheReadTokens.toDouble() / modelTotalInput) * 100
                    DetailRow(stringResource(R.string.usage_label_cache_hit_rate), String.format("%.1f%%", rate))
                }
                val days = model.distinctDays
                val sessions = model.distinctSessions
                if (days > 0) {
                    DetailRow(stringResource(R.string.usage_detail_daily_avg), formatCount((model.inputTokens + model.outputTokens) / days))
                }
                if (sessions > 0) {
                    DetailRow(stringResource(R.string.usage_detail_session_avg), formatCount((model.inputTokens + model.outputTokens) / sessions))
                }
                DetailRow(stringResource(R.string.usage_detail_sessions), sessions.toString())
                DetailRow(stringResource(R.string.usage_detail_active_days), days.toString())
            }
        }

        if (showDivider) {
            val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Default)
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> {
        val k = n / 1000.0
        if (k == k.toLong().toDouble()) "${k.toLong()}k"
        else String.format("%.1fk", k)
    }
    else -> n.toString()
}
