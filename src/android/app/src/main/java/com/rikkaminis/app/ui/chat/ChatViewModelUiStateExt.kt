package com.rikkaminis.app.ui.chat

// [T-android-split-chat] Small UI-state toggle methods extracted from
// ChatViewModel as extension functions (verbatim): tool-detail sheet,
// browser sheet, memory sheet, attachment list. The 4 backing state fields
// were flipped private->internal. No logic change.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.rikkaminis.app.agent.Level
import com.rikkaminis.app.agent.ToolLoopDetector
import com.rikkaminis.app.browser.BrowserActionInput
import com.rikkaminis.app.browser.BrowserTabPool
import com.rikkaminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.rikkaminis.app.data.BPETokenizer
import com.rikkaminis.app.data.ContextOffload
import com.rikkaminis.app.data.ContextPolicy
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.data.FileMentionIndex
import com.rikkaminis.app.data.db.CompactMarkerEntity
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.R
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.data.repository.MemoryRepository
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.provider.ImageBudget
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.provider.ProviderFactory
import com.rikkaminis.app.sandbox.ExecutionCoordinator
import com.rikkaminis.app.terminal.MinisOpenUrlBroker
import com.rikkaminis.app.terminal.MinisUrlMarker
import com.rikkaminis.app.tools.AgentTools
import com.rikkaminis.app.tools.FileEditTool
import com.rikkaminis.app.tools.FileReadTool
import com.rikkaminis.app.tools.FileWriteTool
import com.rikkaminis.app.tools.MemoryTools
import com.rikkaminis.app.tools.ReadImageTool
import com.rikkaminis.app.tools.ToolExecutionResult
import com.rikkaminis.app.offload.OffloadPermissionManager
import com.rikkaminis.app.service.SessionActivityTracker
import com.rikkaminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

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
    _attachments.value = _attachments.value + attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filter { it.id != id }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
}
