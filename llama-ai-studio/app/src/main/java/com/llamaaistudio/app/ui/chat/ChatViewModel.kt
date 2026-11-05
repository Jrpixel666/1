package com.llamaaistudio.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.datastore.SettingsRepository
import com.llamaaistudio.app.data.db.ChatMessageEntity
import com.llamaaistudio.app.data.db.ChatSessionEntity
import com.llamaaistudio.app.data.db.LocalModelEntity
import com.llamaaistudio.app.data.repo.ChatRepository
import com.llamaaistudio.app.data.repo.DEFAULT_SYSTEM_PROMPT
import com.llamaaistudio.app.data.repo.ModelRepository
import com.llamaaistudio.app.engine.EngineState
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.engine.LlamaEngine
import com.llamaaistudio.app.engine.ModelLoadParams
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessions: List<ChatSessionEntity> = emptyList(),
    val currentSessionId: Long? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    /** Session the in-flight reply belongs to (may differ from the one on screen). */
    val generatingSessionId: Long? = null,
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val liveGenTps: Double = 0.0,
    val error: String? = null,
    val info: String? = null,
) {
    val currentSession: ChatSessionEntity? get() = sessions.find { it.id == currentSessionId }
}

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository,
    private val llamaEngine: LlamaEngine,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    val engineState: StateFlow<EngineState> = llamaEngine.state

    val genParams = MutableStateFlowHolder(GenerationParams())

    private var createdDefaultSession = false

    init {
        viewModelScope.launch {
            // Settings load first so the preferred session id and remembered generation
            // params are in hand before we start reacting to the sessions list — otherwise
            // reopening the app would race and fall back to "just pick the first session"
            // more often than not.
            val settings = settingsRepository.settings.first()
            genParams.update { settings.chatParams }
            val preferredSessionId = chatRepository.activeSessionId.value ?: settings.lastChatSessionId

            launch { observeActiveSession() }

            chatRepository.sessions.collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
                val active = chatRepository.activeSessionId.value
                if (active == null || sessions.none { it.id == active }) {
                    when {
                        sessions.isNotEmpty() -> {
                            val preferred = preferredSessionId?.let { id -> sessions.find { it.id == id } }
                            selectSession((preferred ?: sessions.first()).id)
                        }
                        // Fresh install / all sessions deleted: without this, sendMessage()
                        // has nothing to attach to and silently no-ops — the chat screen
                        // just looks permanently blank with no error shown.
                        !createdDefaultSession -> {
                            createdDefaultSession = true
                            newSession()
                        }
                    }
                } else {
                    createdDefaultSession = false
                }
            }
        }
    }

    /**
     * Follows the app-wide active session (which the Sessions workspace can also change) and
     * streams exactly one session's messages at a time. The previous implementation launched a
     * new, never-cancelled collector on every switch, so each switch leaked a Room observer.
     */
    private suspend fun observeActiveSession() {
        // StateFlow already only emits distinct values.
        chatRepository.activeSessionId
            .flatMapLatest { id ->
                _uiState.update { it.copy(currentSessionId = id, messages = emptyList()) }
                if (id == null) emptyFlow() else chatRepository.messages(id)
            }
            .collectLatest { msgs -> _uiState.update { it.copy(messages = msgs) } }
    }

    fun selectSession(sessionId: Long) {
        chatRepository.setActiveSession(sessionId)
        viewModelScope.launch { settingsRepository.setLastChatSessionId(sessionId) }
    }

    fun newSession() {
        viewModelScope.launch {
            val id = chatRepository.createSession(
                systemPrompt = DEFAULT_SYSTEM_PROMPT,
                modelFilePath = llamaEngine.currentModelPath(),
            )
            selectSession(id)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            if (_uiState.value.generatingSessionId == sessionId) stopGeneration()
            chatRepository.deleteSession(sessionId)
            // The sessions collector picks the next most recent session (or creates one).
        }
    }

    fun renameCurrentSession(title: String) {
        val id = _uiState.value.currentSessionId ?: return
        viewModelScope.launch { chatRepository.renameSession(id, title) }
    }

    fun updateCurrentSystemPrompt(prompt: String) {
        val id = _uiState.value.currentSessionId ?: return
        viewModelScope.launch { chatRepository.updateSystemPrompt(id, prompt) }
    }

    fun clearCurrentSession() {
        val id = _uiState.value.currentSessionId ?: return
        if (_uiState.value.generatingSessionId == id) stopGeneration()
        viewModelScope.launch { chatRepository.clearMessages(id) }
    }

    /** Renders the open session as a Markdown transcript and hands it to [onReady] (share sheet). */
    fun exportCurrentSession(onReady: (title: String, markdown: String) -> Unit) {
        val id = _uiState.value.currentSessionId ?: return
        viewModelScope.launch {
            val (title, markdown) = chatRepository.exportAsMarkdown(id) ?: return@launch
            onReady(title, markdown)
        }
    }

    fun showInfo(message: String) {
        _uiState.update { it.copy(info = message) }
    }

    fun updateGenerationParams(update: (GenerationParams) -> GenerationParams) {
        genParams.update(update)
        viewModelScope.launch { settingsRepository.setChatParams(genParams.value) }
    }

    fun loadModel(model: LocalModelEntity, loadParams: ModelLoadParams) {
        viewModelScope.launch {
            val result = llamaEngine.loadModel(model.filePath, loadParams)
            if (result.isSuccess) {
                modelRepository.markUsed(model.filePath)
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message) }
            }
        }
    }

    private var generationJob: Job? = null

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(isGenerating = false, streamingText = "", generatingSessionId = null) }
    }

    fun sendMessage(text: String) {
        val sessionId = _uiState.value.currentSessionId ?: return
        if (text.isBlank() || _uiState.value.isGenerating) return
        generationJob = viewModelScope.launch {
            // Fetched directly rather than from the in-memory sessions list: right after
            // newSession() the list may not have caught up with the just-inserted row yet.
            val session = chatRepository.getSession(sessionId) ?: return@launch
            _uiState.update {
                it.copy(isGenerating = true, generatingSessionId = sessionId, streamingText = "", error = null)
            }
            val startTime = System.currentTimeMillis()
            var tokenCount = 0
            val streamed = StringBuilder()
            chatRepository.sendMessage(session, text, genParams.value).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        tokenCount++
                        streamed.append(event.piece)
                        val elapsedS = (System.currentTimeMillis() - startTime) / 1000.0
                        _uiState.update {
                            it.copy(
                                streamingText = streamed.toString(),
                                liveGenTps = if (elapsedS > 0) tokenCount / elapsedS else 0.0,
                            )
                        }
                    }
                    is GenerationEvent.Done -> {
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                generatingSessionId = null,
                                streamingText = "",
                                liveGenTps = event.stats.genTps,
                            )
                        }
                    }
                    is GenerationEvent.Failed -> {
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                generatingSessionId = null,
                                streamingText = "",
                                error = event.reason,
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(info = null) }
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(
                app.chatRepository,
                app.modelRepository,
                app.llamaEngine,
                app.settingsRepository,
            ) as T
    }
}

/** Small helper so the generation-params slider UI can read/write without a second StateFlow field. */
class MutableStateFlowHolder<T>(initial: T) {
    val value: T get() = _flow.value
    private val _flow = MutableStateFlow(initial)
    val flow: StateFlow<T> = _flow
    fun update(fn: (T) -> T) { _flow.value = fn(_flow.value) }
}
