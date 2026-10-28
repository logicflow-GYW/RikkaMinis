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
import com.rikkaminis.app.R
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.ui.sessions.categoryStyle
import com.rikkaminis.app.ui.sessions.relativeDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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

    // [da1023-F] Keyed on currentSessionId, not Unit: the caller passes the
    // ACTIVE id (ChatInputArea ← ChatScreen), which changes when a draft is
    // promoted mid-screen — the effect must re-run so the self-filter drops
    // the now-current row instead of the stale draft key.
    LaunchedEffect(currentSessionId) {
        sessions = withContext(Dispatchers.IO) {
            // [FIX-C8/F-11] Ghost rows must not be offered as a move target.
            // The sheet used to exclude only the current session, so sessions
            // the history drawer deliberately hides — a materialised-but-empty
            // chat from a /memory or /thinking toggle, or an abandoned draft
            // with no preview — showed up here as untitled rows the user could
            // "move into" and see nothing. isDrawerRowVisible is the drawer's
            // own contract (dv24-b3), so the two lists now agree on what counts
            // as a real session; message counts come from the same live map the
            // drawer reads (one snapshot per sheet open).
            val messageCounts = chatRepository.observeMessageCountsPerSession().first()
            chatRepository.dao.listSessions()
                .filter { session ->
                    isMoveToTargetVisible(
                        sessionId = session.id,
                        currentSessionId = currentSessionId,
                        pinnedAt = session.pinnedAt,
                        messageCount = messageCounts[session.id] ?: 0,
                        lastMessage = session.lastMessage,
                    )
                }
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
 * [FIX-C8/F-11] Which sessions the move-to picker may offer.
 *
 * Two terms, and only two:
 *  - never the session the composer is already in (the caller's original
 *    filter, unchanged);
 *  - only rows the history drawer would render — [isDrawerRowVisible] is that
 *    contract (pinned ∨ has messages ∨ has a non-blank preview), so a ghost
 *    row the user cannot see in the drawer is not offered as a destination
 *    either. Before this the picker listed them as untitled "empty" targets.
 *
 * Pure on purpose: the sheet is a @Composable (not JVM-drivable), so the
 * decision lives here where a unit test can pin the ghost-row cases against the
 * drawer's own predicate instead of a copy of it.
 */
internal fun isMoveToTargetVisible(
    sessionId: String,
    currentSessionId: String,
    pinnedAt: Long?,
    messageCount: Int,
    lastMessage: String?,
): Boolean = sessionId != currentSessionId &&
    isDrawerRowVisible(
        pinnedAt = pinnedAt,
        messageCount = messageCount,
        lastMessage = lastMessage,
    )

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

// [sweep1014-B30] No local `relativeDate` copy any more — it was a drift
// clone of `sessions.SessionsShared.relativeDate` and had drifted: it indexed
// `DateFormatSymbols.weekdays` with `get(DAY_OF_WEEK) - 1`, but that array is
// length 8 with [0] == "" (day-of-week is 1-based), so the weekday branch
// rendered the wrong day (an off-by-one skid, and Sunday → "" for weekdays
// yields a blank timestamp). The shared implementation indexes with
// `dayNames[get(DAY_OF_WEEK)]` and is the single source of truth.
