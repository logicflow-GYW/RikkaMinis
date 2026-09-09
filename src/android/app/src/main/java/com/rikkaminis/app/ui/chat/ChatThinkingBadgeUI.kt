package com.rikkaminis.app.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.share.ChatExporter
import com.rikkaminis.app.ui.theme.ChatColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [T-android-thinking-badge-navbar] Compact thinking-level pill shown on the
 * navbar's "provider · model" line (iOS AIChatView.thinkingLevelBadge parity).
 *
 * Deliberately smaller than the 11sp model-name text next to it — a 9dp
 * lightbulb + 9sp level label — so it reads as secondary auxiliary info and
 * never crowds out the model name. Uses [Icons.Default.Lightbulb], the same
 * glyph the `/thinking` slash command uses.
 *
 * Colors mirror iOS AIChatView.thinkingLevelBadge exactly — a NEUTRAL look, not
 * an accent one. iOS uses `foregroundStyle(secondaryText)` on a
 * `Capsule().fill(Color.secondary.opacity(0.10))` background; the Compose
 * equivalents are `onSurfaceVariant` (secondary grey) for the icon+label and
 * `onSurface.copy(alpha = 0.08f)` (a faint translucent grey) for the capsule.
 * We deliberately do NOT use `primary` / `primaryContainer` / the app's blue
 * thinking accent here: the badge is passive status ("thinking is on, at this
 * level"), not a call-to-action, so a blue highlight would over-emphasize it
 * and clash with the grey "provider · model" text it sits beside. Both colors
 * are theme tokens, so the badge adapts to light/dark automatically.
 *
 * The caller mounts this whenever the current model supports thinking. The
 * label may therefore be "Off"; keeping the badge visible in that state is
 * intentional because it is the only direct entry point to re-enable thinking
 * after the user turns it off.
 *
 * It carries its OWN clickable (which consumes the tap) so a tap on the badge
 * opens the thinking-level sheet instead of the model picker owned by the
 * enclosing subtitle Column — see the call site for the full gesture-separation
 * rationale.
 */
@Composable
internal fun ThinkingLevelBadge(
    level: ThinkingLevel,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Secondary grey for icon + label (iOS secondaryText parity) — no accent.
    val badgeColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            // Faint translucent-grey capsule (iOS Color.secondary.opacity(0.10)).
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            // Own clickable → consumes the tap, opens the thinking sheet.
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(9.dp),
        )
        Text(
            text = level.localizedName(context),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = badgeColor,
            maxLines = 1,
        )
    }
}

/**
 * [T-android-thinking-badge-navbar] Bottom-sheet thinking-level selector opened
 * from [ThinkingLevelBadge]. Mirrors iOS ThinkingLevelSheetView: an Off row
 * followed by every level the current model supports; the active level shows a
 * trailing check. Selecting any row calls [onSelect] (which also dismisses).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThinkingLevelSheet(
    currentLevel: ThinkingLevel,
    availableLevels: List<ThinkingLevel>,
    onSelect: (ThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    // Off is always offered (turns thinking off); availableLevels already
    // excludes Off, so prepend it. De-dup defensively in case a caller ever
    // includes it.
    val rows = remember(availableLevels) {
        listOf(ThinkingLevel.OFF) +
            availableLevels.filter { it != ThinkingLevel.OFF }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.thinking_level_sheet_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = ChatColors.toolBorder, thickness = 0.5.dp)
            val context = LocalContext.current
            rows.forEach { level ->
                // "Off selected" = the current level is disabled; otherwise an
                // exact match.
                val isSelected = if (level == ThinkingLevel.OFF) {
                    !currentLevel.isEnabled
                } else {
                    currentLevel == level
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(level) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = ChatColors.thinking.copy(
                            alpha = if (level == ThinkingLevel.OFF) 0.4f else 1f,
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = level.localizedName(context),
                        fontSize = 15.sp,
                        color = ChatColors.primaryText,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = ChatColors.thinking,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

// ─── Export Current Conversation ────────────────────────────────────────────

/**
 * Export the active conversation via the streaming [ChatExporter]
 * — the same pipeline the session list's long-press Export uses (paginated
 * reads, staged zip, FileProvider share sheet), so long chats stay
 * bounded-memory. Draft sessions ("__new__" aliases, no DB row yet) get a
 * toast instead of an empty archive.
 */
internal fun exportCurrentChat(
    context: Context,
    viewModel: ChatViewModel,
    chatRepository: ChatRepository,
    scope: CoroutineScope,
    format: String,
) {
    scope.launch {
        val sid = viewModel.activeSessionId
        if (sid.startsWith("__new__")) {
            Toast.makeText(
                context,
                context.getString(R.string.export_empty_hint),
                Toast.LENGTH_SHORT,
            ).show()
            return@launch
        }
        val session = chatRepository.observeSessions().first().firstOrNull { it.id == sid }
        if (session == null) {
            Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        try {
            val (uri, _) = ChatExporter.exportToZip(
                context = context,
                session = session,
                repository = chatRepository,
                format = format,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, session.title ?: "Conversation")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.sessionlist_export),
            ).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
