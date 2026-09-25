package com.rikkaminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.ui.theme.ChatColors

/**
 * Shared monospace editor used by Settings → Memory file editor and
 * Session Memory sheet auto-file detail. The caller owns the
 * [TextFieldState] (and Save logic). Renders the state-holding
 * [BasicTextField] overload plus optional error banner below it.
 *
 * [fix-memory-editor-jump-to-top] Uses the new state-holding overload —
 * the legacy `value`/`onValueChange` overload keeps the cursor position
 * only in the hoisted state, which flows back a frame late, so the
 * same-frame caret bringIntoView after a tap uses the PREVIOUS cursor
 * position and the content jumps to the top (Google issuetracker
 * 235693496, unfixed upstream). TextFieldState stores the selection in
 * the field's own state immediately, removing the one-frame staleness.
 */
@Composable
fun MemoryFileEditorContent(
    state: TextFieldState,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = ChatColors.primaryText,
    ),
) {
    Column(modifier = modifier) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )

        if (errorMessage != null) {
            Text(
                errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
