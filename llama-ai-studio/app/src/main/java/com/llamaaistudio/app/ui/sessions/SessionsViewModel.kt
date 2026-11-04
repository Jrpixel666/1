package com.llamaaistudio.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.ChatSessionSummary
import com.llamaaistudio.app.data.repo.DEFAULT_SYSTEM_PROMPT
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the Sessions screen: browse, search (titles and message text), pin, rename,
 * duplicate, share, clear and delete chat sessions. Which session is "open" lives in
 * [com.llamaaistudio.app.data.repo.ChatRepository.activeSessionId], shared with Chat.
 */
@OptIn(FlowPreview::class)
class SessionsViewModel(private val app: LlamaAiStudioApp) : ViewModel() {
    private val chatRepository = app.chatRepository

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val sessions: StateFlow<List<ChatSessionSummary>?> = _query
        .debounce(200)
        .distinctUntilChanged()
        .flatMapLatest { chatRepository.sessionSummaries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeSessionId: StateFlow<Long?> = chatRepository.activeSessionId

    fun setQuery(q: String) {
        _query.value = q
    }

    fun open(sessionId: Long) {
        chatRepository.setActiveSession(sessionId)
        viewModelScope.launch { app.settingsRepository.setLastChatSessionId(sessionId) }
    }

    fun newSession(onCreated: () -> Unit) {
        viewModelScope.launch {
            val id = chatRepository.createSession(
                systemPrompt = DEFAULT_SYSTEM_PROMPT,
                modelFilePath = app.llamaEngine.currentModelPath(),
            )
            open(id)
            onCreated()
        }
    }

    fun rename(sessionId: Long, title: String) {
        viewModelScope.launch { chatRepository.renameSession(sessionId, title) }
    }

    fun togglePinned(summary: ChatSessionSummary) {
        viewModelScope.launch { chatRepository.setPinned(summary.session.id, !summary.session.pinned) }
    }

    fun updateSystemPrompt(sessionId: Long, prompt: String) {
        viewModelScope.launch { chatRepository.updateSystemPrompt(sessionId, prompt) }
    }

    fun duplicate(sessionId: Long) {
        viewModelScope.launch {
            val id = chatRepository.duplicateSession(sessionId)
            _message.value = if (id != null) "Session duplicated" else "Session no longer exists"
        }
    }

    fun export(sessionId: Long, onReady: (title: String, markdown: String) -> Unit) {
        viewModelScope.launch {
            val (title, markdown) = chatRepository.exportAsMarkdown(sessionId) ?: return@launch
            onReady(title, markdown)
        }
    }

    fun clearMessages(sessionId: Long) {
        viewModelScope.launch { chatRepository.clearMessages(sessionId) }
    }

    fun delete(sessionId: Long) {
        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
            _message.update { "Session deleted" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionsViewModel(app) as T
    }
}
