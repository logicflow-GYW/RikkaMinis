package com.rikkaminis.app.ui.chat

// [T-android-split-chat] Small UI-state toggle methods extracted from
// ChatViewModel as extension functions (verbatim): tool-detail sheet,
// browser sheet, memory sheet, attachment list. The 4 backing state fields
// were flipped private->internal. No logic change.

import com.rikkaminis.app.R

internal fun ChatViewModel.openToolDetail(toolBlockId: String) {
    _selectedToolDetailId.value = toolBlockId
}

internal fun ChatViewModel.closeToolDetail() {
    _selectedToolDetailId.value = null
}

internal fun ChatViewModel.toggleBrowserSheet() {
    val opening = !_showBrowserSheet.value
    if (opening) browserTabPool.ensureTabForUI()
    _showBrowserSheet.value = opening
}

internal fun ChatViewModel.dismissBrowserSheet() {
    _showBrowserSheet.value = false
}

/**
 * Open the session browser sheet, focused on the tab whose URL matches
 * [url]. If no pool tab currently has that URL, a new tab is created and
 * loaded. Used by the tool-call preview's globe button so the agent's
 * existing browser_use page is reused when available instead of spawning
 * a duplicate tab.
 */
internal fun ChatViewModel.openBrowserSheetForUrl(url: String) {
    if (url.isBlank()) {
        browserTabPool.ensureTabForUI()
    } else {
        browserTabPool.selectOrCreateTabForURL(url)
    }
    _showBrowserSheet.value = true
}

internal fun ChatViewModel.toggleMemorySheet() {
    _showMemorySheet.value = !_showMemorySheet.value
}

internal fun ChatViewModel.dismissMemorySheet() {
    _showMemorySheet.value = false
}

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) {
    // §27a: image attachments to a non-vision model still land here —
    // deliberately NOT blocked. The provider gate (StreamTimeouts
    // .visionPlaceholder) downgrades the image part to a text placeholder on
    // the wire, AND the prompt builder mirrors the full-res file into the
    // uploads dir so agent shell tools (read_image / cat) can still read it.
    // Blocking the attach would kill that tool path. The user just gets a
    // hint that the model won't see the picture natively.
    if (attachment.isImage && !currentModelSupportsImages) {
        android.widget.Toast.makeText(
            context.applicationContext,
            context.getString(com.rikkaminis.app.R.string.chat_image_no_vision_hint),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
    _attachments.value = _attachments.value + attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filter { it.id != id }
    // [sweep-p1] While editing, the composer represents the edited turn and
    // the pre-edit snapshot is what cancelEdit() restores. Deleting from the
    // list must also delete from the snapshot, otherwise canceling edit
    // revives an attachment the user deliberately removed.
    if (_editingMessageId.value != null) {
        _preEditAttachments.value = _preEditAttachments.value?.filter { it.id != id }
    }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
    // [sweep-p1] Same as removeAttachment: while editing, a deliberate clear
    // must clear the snapshot too — or cancelEdit() resurrects the cleared
    // attachments. Empty list (not null) so editMessage()'s snapshot-once
    // semantics stay intact.
    if (_editingMessageId.value != null) {
        _preEditAttachments.value = emptyList()
    }
}
