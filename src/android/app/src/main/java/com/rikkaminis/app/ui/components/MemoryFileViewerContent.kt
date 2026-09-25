package com.rikkaminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.ui.theme.ChatColors

/**
 * [T-android-memory-file-jank] Virtualized read-only viewer for memory files.
 *
 * Why this exists: both memory-file readers previously handed the entire file
 * to one `Text` inside a `verticalScroll` Column. Compose text layout is not
 * incremental across scroll, so every scroll frame re-measured the whole
 * string. On-device measurement (Redmi Note 12 Turbo / Android 15) shows a
 * cliff between 90KB and 110KB:
 *
 *   60KB -> p50 26ms, 426 frames    |  110KB -> p50 200ms, 50 frames
 *   90KB -> p50 44ms, 300 frames    |  206KB -> p50 200ms,  ~50 frames
 *
 * i.e. not just "each frame is slower" but the rendered-frame count collapsing
 * ~6x for the same gesture — each frame does ~6x the work. Per-scroll
 * measurement confirmed a constant ~250ms per frame regardless of scroll
 * depth, which rules out "only slow near the bottom": the whole text is
 * re-laid-out every frame.
 *
 * The fix: chunk the text ([chunkText]) and render one LazyColumn item per
 * chunk, so only on-screen chunks are ever measured. Cost becomes O(viewport)
 * instead of O(file).
 *
 * A single [SelectionContainer] wraps the WHOLE LazyColumn, matching the
 * pattern documented at `ChatScreen.kt` (search "SelectionContainer must wrap
 * the WHOLE LazyColumn"): a per-item container loses the long-press selection
 * when the item is disposed by scrolling. This is Compose's recommended
 * LazyColumn + selection arrangement.
 *
 * The caller owns edit mode; this composable is read-only by construction.
 */
// ponytail: 只读路径虚拟化 + 编辑路径窗口化（buildEditWindow，编辑器只持
// O(窗口) 文本，Save 按 spliceEditWindow 拼回整份文件）| 天花板: 编辑模式下
// 只能滚动窗口内 ~几屏（要编辑别处需退回只读滚过去再点 Edit）| 升级触发:
// 用户反馈"编辑框滚不出窗口"不可接受时，改做窗口自动平移（光标接近窗口边
// 界时重建窗口）。
@Composable
public fun MemoryFileViewerContent(
    text: String,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    // Hoisted so the Settings editor and the Session Memory sheet can read
    // the visible chunk range when building the edit window. Keyed on `text`
    // by callers that pass their own, so switching files starts at the top.
    listState: LazyListState = remember(text) { LazyListState() },
    textStyle: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = ChatColors.primaryText,
    ),
) {
    val chunks = remember(text) { chunkText(text) }

    if (chunks.isEmpty()) {
        if (emptyText != null) {
            Box(modifier = modifier.fillMaxSize()) {
                Text(
                    text = emptyText,
                    style = textStyle,
                    color = ChatColors.secondaryText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        return
    }

    SelectionContainer(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            itemsIndexed(chunks) { _, chunk ->
                // No separator prefix. [chunkText]'s chunks only need the "\n"
                // re-inserted when they are joined back into ONE string; here
                // each chunk is its own LazyColumn item, and vertical stacking
                // already provides the line break. Prefixing "\n" would render
                // one extra blank line per chunk boundary (measured: 54 extra
                // lines on a 204KB daily log) — and because SelectionManager
                // appends '\n' between selectables itself (SelectionManager
                // .getSelectedText), a cross-chunk copy would carry the blank
                // line into the clipboard too.
                Text(text = chunk, style = textStyle)
            }
        }
    }
}
