package com.xiaoqiu.ui.chat

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
import com.xiaoqiu.agent.Level
import com.xiaoqiu.agent.ToolLoopDetector
import com.xiaoqiu.browser.BrowserActionInput
import com.xiaoqiu.browser.BrowserTabPool
import com.xiaoqiu.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.xiaoqiu.data.BPETokenizer
import com.xiaoqiu.data.ContextOffload
import com.xiaoqiu.data.ContextPolicy
import com.xiaoqiu.logging.AppLogger
import com.xiaoqiu.data.FileMentionIndex
import com.xiaoqiu.data.db.CompactMarkerEntity
import com.xiaoqiu.data.model.AgentContentPart
import com.xiaoqiu.data.model.AgentToolDefinition
import com.xiaoqiu.data.model.LLMMessage
import com.xiaoqiu.data.model.LLMModel
import com.xiaoqiu.data.model.LLMStreamChunk
import com.xiaoqiu.data.model.LLMUsage
import com.xiaoqiu.data.model.ModelGroup
import com.xiaoqiu.data.model.ThinkingLevel
import com.xiaoqiu.R
import com.xiaoqiu.data.repository.ChatRepository
import com.xiaoqiu.data.repository.MemoryRepository
import com.xiaoqiu.data.repository.ProviderRepository
import com.xiaoqiu.provider.ImageBudget
import com.xiaoqiu.provider.LLMProvider
import com.xiaoqiu.provider.ProviderFactory
import com.xiaoqiu.sandbox.ExecutionCoordinator
import com.xiaoqiu.terminal.MinisOpenUrlBroker
import com.xiaoqiu.terminal.MinisUrlMarker
import com.xiaoqiu.tools.AgentTools
import com.xiaoqiu.tools.FileEditTool
import com.xiaoqiu.tools.FileReadTool
import com.xiaoqiu.tools.FileWriteTool
import com.xiaoqiu.tools.MemoryTools
import com.xiaoqiu.tools.ReadImageTool
import com.xiaoqiu.tools.ToolExecutionResult
import com.xiaoqiu.offload.OffloadPermissionManager
import com.xiaoqiu.service.SessionActivityTracker
import com.xiaoqiu.service.SessionConcurrencyManager
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
