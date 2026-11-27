package com.rikkaminis.app.ui.chat

/** System back is cancellation/navigation only; saving remains an explicit action. */
internal enum class MemorySheetBackAction { CancelEdit, ShowList, Dismiss }

internal fun memorySheetBackAction(isEditing: Boolean, isDetail: Boolean): MemorySheetBackAction = when {
    isEditing -> MemorySheetBackAction.CancelEdit
    isDetail -> MemorySheetBackAction.ShowList
    else -> MemorySheetBackAction.Dismiss
}
