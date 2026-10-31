package com.rikkaminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.theme.ChatColors

/**
 * [feat/paste-fold-marker] Chip for one folded paste, in the composer's chip
 * row next to the attachment thumbnails.
 *
 * Geometry is copied deliberately from [AttachmentChip] rather than shared: the
 * 72×70 outer box, the 64dp body anchored BottomStart and the 20dp remove badge
 * at TopEnd are what let the badge hang half-off the corner without being
 * clipped. A pasted block and an attached file are the same KIND of thing
 * (content held outside the text field, previewable, removable), so they must be
 * the same shape and height in one row — drift here reads as a misaligned row.
 *
 * The label is the id plus the character count: those are the two things that
 * tell two pastes apart at a glance, and both are language-neutral. Tapping
 * opens the full text.
 */
@Composable
internal fun PastedTextChip(
    pasted: PastedText,
    onRemove: () -> Unit,
) {
    var showPreview by remember(pasted.id) { mutableStateOf(false) }
    val chipShape = RoundedCornerShape(8.dp)
    val description = stringResource(R.string.paste_chip_desc, pasted.id, pasted.charCount)
    Box(modifier = Modifier.size(width = 72.dp, height = 70.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(64.dp)
                .clip(chipShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, chipShape)
                .border(1.dp, ChatColors.thumbnailBorder, chipShape)
                .clickable { showPreview = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                // Text glyph: this IS a block of text, and the preview dialog is
                // the only thing behind it.
                Icons.AutoMirrored.Filled.Article,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "#${pasted.id}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = "${pasted.charCount}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // Remove badge, matching AttachmentChip: half on / half off the
        // top-right corner, hairline border so it stays legible.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(0.5.dp, ChatColors.thumbnailBorder, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.common_remove),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp),
            )
        }
    }

    if (showPreview) {
        PastedTextPreviewDialog(pasted = pasted, onDismiss = { showPreview = false })
    }
}

/**
 * [feat/paste-fold-marker] Read-only full-text preview.
 *
 * Read-only on purpose: the buffered text is what will be sent verbatim, and an
 * editable sheet raises questions this feature has no answer for — whether an
 * edit re-runs the length threshold, what happens to a marker whose content
 * shrank below it, how an edit interacts with the same entry being referenced
 * twice. Viewing is the need users actually have ("what did I paste?").
 *
 * A plain [Dialog] rather than the alert-dialog helper because the body must
 * SCROLL: these are thousand-character blocks by definition, and a fixed Text
 * would overflow the screen.
 */
@Composable
private fun PastedTextPreviewDialog(
    pasted: PastedText,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.paste_preview_title, pasted.placeholder),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.paste_folded_summary, pasted.charCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = pasted.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        // Capped so a huge paste cannot push the close button
                        // off-screen; the body scrolls within the cap.
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    // fillMaxWidth is what makes Arrangement.End mean anything:
                    // without it the Row wraps to the button's own width, so
                    // "End" aligns inside a box exactly its size and the button
                    // renders flush LEFT.
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}

/**
 * [feat/paste-fold-marker] Fail-closed notice shown above the composer while
 * [unexpandedPasteMarkers] is non-empty.
 *
 * Inline rather than a Snackbar because the Snackbar host lives in ChatScreen
 * and this feature is deliberately confined to the composer surface — and
 * because the condition persists for as long as the marker does, which a
 * transient bar would misrepresent.
 */
@Composable
internal fun PasteSendBlockedNotice(
    markers: List<String>,
    modifier: Modifier = Modifier,
) {
    if (markers.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // The marker itself is quoted so the user can find it in the field.
            text = stringResource(R.string.paste_unresolved_notice, markers.joinToString(" ")),
            style = MaterialTheme.typography.labelSmall,
            color = ChatColors.error,
        )
    }
}
