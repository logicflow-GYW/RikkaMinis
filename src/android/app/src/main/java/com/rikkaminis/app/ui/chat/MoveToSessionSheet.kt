package com.rikkaminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.rikkaminis.app.R
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.ui.sessions.categoryStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Bottom sheet listing all chat sessions except the current one. Tapping
 * a row hands the chosen session id back to the caller. Mirrors iOS
 * MoveToSessionSheet (Views/Chat/AIChatView.swift:3413) — same flow
 * (current input + attachments stash via ChatViewModelStore.pendingTransfer
 * → navigate → target session drains the cache).
 *
 * T167: rows now show a category icon + relative timestamp matching
 * SessionListScreen's main list, and the LazyColumn is wrapped in a
 * 16dp rounded surface card so the picker reads as a discrete control
 * inside the sheet rather than naked list items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToSessionSheet(
    currentSessionId: String,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sessions by remember { mutableStateOf<List<ChatSessionEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) {
            chatRepository.dao.listSessions().filter { it.id != currentSessionId }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.move_to_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.move_to_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                        ),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        MoveToPickerRow(
                            session = session,
                            onClick = { onSelect(session.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single row in the Move-to picker. Visual parity with SessionRow in
 * SessionListScreen.kt (44dp tinted-circle category icon + title +
 * relative timestamp), trimmed of the active-session spinning ring and
 * the second "last message" line so the row stays compact inside a
 * bottom sheet.
 */
@Composable
private fun MoveToPickerRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val style = remember(session.category) { categoryStyle(session.category) }
    val timeText = remember(session.updatedAt, context) { relativeDate(context, session.updatedAt) }
    val untitled = stringResource(R.string.move_to_sheet_untitled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = style.color.copy(alpha = 0.18f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.color,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = session.title ?: untitled,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}

// ─── Local copy note ───────────────────────────────────────────────────────
//
// `categoryStyle` is now shared from sessions.SessionsShared.kt (single source
// of truth). `relativeDate` is still file-private inside SessionListScreen.kt;
// mirroring it here keeps the picker visually consistent with the main list.

private fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    if (seconds < 60) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())
    val dateCal = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) return context.getString(R.string.time_yesterday)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        // T172: device-locale weekday name via java.text.DateFormatSymbols.
        val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
        return dayNames[dateCal.get(Calendar.DAY_OF_WEEK) - 1]
    }
    val month = dateCal.get(Calendar.MONTH) + 1
    val day = dateCal.get(Calendar.DAY_OF_MONTH)
    return "$month/$day"
}
