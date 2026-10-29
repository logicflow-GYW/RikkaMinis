package com.rikkaminis.app.ui.sessions

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.ui.components.MinisTextButton
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput
import com.rikkaminis.app.ui.theme.CategoryAccents
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

internal data class CategoryStyle(val icon: ImageVector, val color: Color)

// 16 categories matching iOS (ContentView.swift:1897-1916)
internal fun categoryStyle(category: String?): CategoryStyle {
    return when (category?.lowercase()) {
        "code"         -> CategoryStyle(Icons.Outlined.Code, CategoryAccents.code)
        "writing"      -> CategoryStyle(Icons.Outlined.Description, CategoryAccents.writing)
        "research"     -> CategoryStyle(Icons.Outlined.Language, CategoryAccents.research)
        "analysis"     -> CategoryStyle(Icons.Outlined.BarChart, CategoryAccents.analysis)
        "creative"     -> CategoryStyle(Icons.Outlined.Brush, CategoryAccents.creative)
        "chat"         -> CategoryStyle(Icons.Outlined.Forum, CategoryAccents.chat)
        "math"         -> CategoryStyle(Icons.Outlined.Calculate, CategoryAccents.math)
        "translation"  -> CategoryStyle(Icons.Outlined.Translate, CategoryAccents.translation)
        "health"       -> CategoryStyle(Icons.Outlined.Favorite, CategoryAccents.health)
        "finance"      -> CategoryStyle(Icons.Outlined.Payments, CategoryAccents.finance)
        "travel"       -> CategoryStyle(Icons.Outlined.Map, CategoryAccents.travel)
        "education"    -> CategoryStyle(Icons.Outlined.Book, CategoryAccents.education)
        "design"       -> CategoryStyle(Icons.Outlined.Palette, CategoryAccents.design)
        "productivity" -> CategoryStyle(Icons.Outlined.CalendarMonth, CategoryAccents.productivity)
        "support"      -> CategoryStyle(Icons.Outlined.Settings, CategoryAccents.support)
        "other"        -> CategoryStyle(Icons.Outlined.GridView, CategoryAccents.other)
        else           -> CategoryStyle(Icons.Outlined.Forum, CategoryAccents.fallback)
    }
}

// Date period for section grouping (matching iOS)
internal enum class DatePeriod(val label: String) {
    PINNED("Pinned"),     // labels are i18n'd at render time via sectionLabelFor
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    EARLIER("Earlier"),
}

/**
 * Map a session's updatedAt timestamp to its display bucket. Mirrors iOS
 * `ContentView.groupedSessions`:
 *   - Today / Yesterday: calendar-day match
 *   - This Week: within the last 7 days (rolling window, not current week)
 *   - This Month: within the last 30 days (rolling window, NOT current calendar month)
 *   - Earlier: everything else
 */
internal fun datePeriod(timestamp: Long, nowMillis: Long = System.currentTimeMillis()): DatePeriod {
    // [R4-V F-14] Clock skew / restored future timestamps: `now - timestamp`
    // is negative, which satisfied every `diff < N` comparison below, so a
    // session dated in the future landed in "This Week" — a *past* bucket for
    // the newest row in the list. It belongs at the top, so the future shape
    // maps to TODAY (the newest non-pinned bucket) instead.
    if (timestamp > nowMillis) return DatePeriod.TODAY

    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val cal = Calendar.getInstance().apply { time = Date(timestamp) }

    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.TODAY

    val yesterday = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, -1)
    }
    if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.YESTERDAY

    val diffDays = TimeUnit.MILLISECONDS.toDays(nowMillis - timestamp)
    if (diffDays < 7) return DatePeriod.THIS_WEEK

    // iOS uses `monthAgo = now - 1 month` (rolling window). A calendar-month
    // match would push e.g. a March 30 session into "Earlier" on April 2 —
    // iOS still shows it in "This Month" until May 2.
    val monthAgo = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.MONTH, -1)
    }.timeInMillis
    if (timestamp > monthAgo) return DatePeriod.THIS_MONTH

    return DatePeriod.EARLIER
}

internal fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<Pair<DatePeriod, List<ChatSessionEntity>>> {
    val pinned = sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val unpinned = sessions.filter { it.pinnedAt == null }
    val grouped = unpinned.groupBy { datePeriod(it.updatedAt) }
    val result = mutableListOf<Pair<DatePeriod, List<ChatSessionEntity>>>()
    if (pinned.isNotEmpty()) {
        result.add(DatePeriod.PINNED to pinned)
    }
    for (period in DatePeriod.entries) {
        if (period == DatePeriod.PINNED) continue
        grouped[period]?.let { result.add(period to it) }
    }
    return result
}

/**
 * [R4-V F-14] Which relative-date label a timestamp earns.
 *
 * The decision is split out of [relativeDate] so the boundary table can be
 * pinned on the JVM (the formatter needs a `Context` for `getString`).
 *
 * A **future** timestamp (clock skew, a session restored/imported from another
 * device) used to satisfy `seconds < 60` and render "Just now" — the one label
 * that is provably wrong, and it also short-circuited the weekday/date arms
 * (`days < 7` is true for negative days). It now falls straight through to the
 * absolute date: honest, and it cannot masquerade as the newest row.
 */
internal enum class RelativeDateBucket { JUST_NOW, MINUTES_AGO, HOURS_AGO, YESTERDAY, WEEKDAY, ABSOLUTE_DATE }

internal fun relativeDateBucket(nowMillis: Long, timestamp: Long): RelativeDateBucket {
    if (timestamp > nowMillis) return RelativeDateBucket.ABSOLUTE_DATE

    val diff = nowMillis - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    if (seconds < 60) return RelativeDateBucket.JUST_NOW
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    if (minutes < 60) return RelativeDateBucket.MINUTES_AGO
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    if (hours < 24) return RelativeDateBucket.HOURS_AGO

    val dateCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val yesterdayCal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, -1)
    }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return RelativeDateBucket.YESTERDAY
    }

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) return RelativeDateBucket.WEEKDAY
    return RelativeDateBucket.ABSOLUTE_DATE
}

internal fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when (relativeDateBucket(now, timestamp)) {
        RelativeDateBucket.JUST_NOW -> context.getString(R.string.time_just_now)
        RelativeDateBucket.MINUTES_AGO ->
            context.getString(R.string.time_minutes_ago, TimeUnit.MILLISECONDS.toMinutes(diff).toInt())
        RelativeDateBucket.HOURS_AGO ->
            context.getString(R.string.time_hours_ago, TimeUnit.MILLISECONDS.toHours(diff).toInt())
        RelativeDateBucket.YESTERDAY -> context.getString(R.string.time_yesterday)
        RelativeDateBucket.WEEKDAY -> {
            // T172: device-locale weekday names via java.text.DateFormatSymbols.
            val dateCal = Calendar.getInstance().apply { timeInMillis = timestamp }
            val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
            dayNames[dateCal.get(Calendar.DAY_OF_WEEK)]
        }
        RelativeDateBucket.ABSOLUTE_DATE -> {
            val dateCal = Calendar.getInstance().apply { timeInMillis = timestamp }
            val month = dateCal.get(Calendar.MONTH) + 1
            val day = dateCal.get(Calendar.DAY_OF_MONTH)
            "$month/$day"
        }
    }
}

// ─── Edit Title & Category Sheet (matching iOS SessionEditSheet) ──────────

private val allCategories = listOf(
    "Code", "Writing", "Research", "Analysis",
    "Creative", "Chat", "Math", "Translation",
    "Health", "Finance", "Travel", "Education",
    "Design", "Productivity", "Support", "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionEditSheet(
    session: ChatSessionEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String?) -> Unit,
    // [T-android-sessionedit-regenerate-button] Regenerate-Title support,
    // matching iOS SessionEditSheet. `liveSession` is the DB-backed row that
    // updates when regeneration writes a new title/category; `isRegenerating`
    // drives the button's loading/disabled state; `onRegenerate` reuses the
    // existing SessionListViewModel.regenerateTitle logic. Defaults make the
    // button a no-op when a caller doesn't wire them up.
    liveSession: ChatSessionEntity = session,
    isRegenerating: Boolean = false,
    onRegenerate: () -> Unit = {},
) {
    var title by remember(session.id) { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember(session.id) { mutableStateOf(session.category) }
    val defaultSessionTitle = stringResource(R.string.session_new_chat_default)

    // [T-android-sessionedit-regenerate-button] When a regeneration run writes a
    // new title/category to the DB, `liveSession` updates — mirror those values
    // into the sheet's local edit state so the Title field and Category grid
    // refresh in place (iOS reads the fresh ChatStore session on completion).
    // [da1032-A] Keys carry liveSession.id too: re-pointing the sheet at another
    // row must re-seed rather than leave the previous session's text behind.
    // (`liveSession.title?.let { … }` stays: a null title means "no new title to
    // mirror", and blanking the field is a different decision — onSave already
    // substitutes the "New Chat" default.)
    LaunchedEffect(liveSession.id, liveSession.title, liveSession.category) {
        liveSession.title?.let { title = it }
        selectedCategory = liveSession.category
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            // Title bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.session_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                MinisTextButton(
                    onClick = { onSave(title.ifBlank { defaultSessionTitle }, selectedCategory) },
                ) { Text(stringResource(R.string.common_save)) }
            }

            Spacer(Modifier.height(16.dp))

            // Title field
            OutlinedTextField(
                value = title,
                onValueChange = { title = sanitizeSingleLineInput(it) },
                label = { Text(stringResource(R.string.session_title_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "Category",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Category grid (4 columns, matching iOS LazyVGrid)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(240.dp),
            ) {
                items(allCategories) { cat ->
                    val isSelected = selectedCategory?.equals(cat, ignoreCase = true) == true
                    val style = categoryStyle(cat.lowercase())
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) style.color.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable {
                                selectedCategory = if (isSelected) null else cat.lowercase()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = style.icon,
                                contentDescription = null,
                                tint = style.color,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                cat,
                                fontSize = 11.sp,
                                color = if (isSelected) style.color
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // [T-android-sessionedit-regenerate-button] Regenerate Title —
            // matches iOS SessionEditSheet's dedicated section below Category.
            // Reuses SessionListViewModel.regenerateTitle; shows a spinner and
            // disables while running (regeneratingIds) to prevent double taps.
            OutlinedButton(
                onClick = onRegenerate,
                enabled = !isRegenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRegenerating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerating_title))
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerate_title))
                }
            }
        }
    }
}