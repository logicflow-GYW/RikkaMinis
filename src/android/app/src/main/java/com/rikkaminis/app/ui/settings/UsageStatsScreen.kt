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
import com.rikkaminis.app.data.usage.UsageAggregator
import com.rikkaminis.app.data.usage.UsageRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ModelStats(
    val modelId: String,
    val displayName: String,
    val provider: String,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cacheCreationTokens: Long = 0,
    var cacheReadTokens: Long = 0,
    val distinctDays: MutableSet<String> = mutableSetOf(),
    val distinctSessions: MutableSet<String> = mutableSetOf(),
) {
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens
}

private data class ProviderGroup(val name: String, val models: List<ModelStats>)

/** Time-range filter for the usage page. ALL keeps the legacy full scan. */
private enum class UsageRange { ALL, DAYS_7, DAYS_30 }

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
    var isLoaded by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(UsageRange.ALL) }

    LaunchedEffect(range) {
        // Loading skeleton only on the first load; subsequent range flips keep
        // showing stale data instead of flashing blank. (T6-L2: isLoaded is set
        // to true at the end of this block and never reset, so the deleted
        // `if (!isLoaded) isLoaded = false` was a no-op that only looked like
        // half-written logic.)
        val records = when (range) {
            UsageRange.ALL -> chatDao.allUsageRecords()
            else -> {
                val now = System.currentTimeMillis()
                val since = when (range) {
                    UsageRange.DAYS_7 -> now - 7L * 24 * 60 * 60 * 1000
                    UsageRange.DAYS_30 -> now - 30L * 24 * 60 * 60 * 1000
                    UsageRange.ALL -> 0L
                }
                chatDao.usageRecordsBetween(since, now + 1)
            }
        }

        // modelId → (displayName, provider), builtin models first then custom
        // entries from the live config.
        val modelLookup = mutableMapOf<String, Pair<String, String>>()
        for (m in LLMModel.allModels) modelLookup[m.id] = m.displayName to m.provider
        providerConfig?.let { config ->
            for (entry in config.modelEntries) {
                if (entry.model.id !in modelLookup) {
                    val instance = config.instances.find { it.id == entry.providerInstanceId }
                    val providerName = instance?.providerType?.displayName ?: entry.model.provider
                    modelLookup[entry.model.id] = entry.model.displayName to providerName
                }
            }
        }

        // Device-local timezone day formatter for distinct-day bucketing
        // (matches the pre-refactor behavior). Created per-load — cheap.
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val aggregated = UsageAggregator.aggregate(
            rows = records.map {
                UsageRow(it.modelId, it.tokenUsage, it.createdAt, it.sessionId)
            },
            dayFormat = { ms -> dateFormat.format(Date(ms)) },
        )

        val statsMap = aggregated.mapValues { (modelId, stats) ->
            val (displayName, provider) = modelLookup[modelId] ?: (modelId to "Unknown")
            ModelStats(
                modelId = modelId,
                displayName = displayName,
                provider = provider,
                inputTokens = stats.inputTokens,
                outputTokens = stats.outputTokens,
                cacheCreationTokens = stats.cacheCreationTokens,
                cacheReadTokens = stats.cacheReadTokens,
                distinctDays = stats.distinctDays.toMutableSet(),
                distinctSessions = stats.distinctSessions.toMutableSet(),
            )
        }

        val providerOrder = listOf("OpenAI", "Anthropic", "Google Gemini", "Google", "Antigravity", "Unknown")
        val grouped = statsMap.values.groupBy { it.provider }
        val sortedGroups = grouped.entries.sortedBy { (name, _) ->
            val idx = providerOrder.indexOf(name)
            if (idx >= 0) idx else providerOrder.size
        }.map { (name, models) ->
            ProviderGroup(name, models.sortedByDescending { it.totalInput })
        }

        val allStats = statsMap.values
        grandTotal = GrandTotal(
            totalInput = allStats.sumOf { it.totalInput },
            outputTokens = allStats.sumOf { it.outputTokens },
            cacheReadTokens = allStats.sumOf { it.cacheReadTokens },
            cacheCreationTokens = allStats.sumOf { it.cacheCreationTokens },
        )

        providerGroups = sortedGroups
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
                val days = model.distinctDays.size
                val sessions = model.distinctSessions.size
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
