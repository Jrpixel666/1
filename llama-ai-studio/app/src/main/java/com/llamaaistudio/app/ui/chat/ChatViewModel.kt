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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessions: List<ChatSessionEntity> = emptyList(),
    val currentSessionId: Long? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val liveGenTps: Double = 0.0,
    val error: String? = null,
)

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
            val preferredSessionId = settings.lastChatSessionId

            chatRepository.sessions.collect { sessions ->
                _uiState.value = _uiState.value.copy(sessions = sessions)
                if (_uiState.value.currentSessionId == null) {
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
                }
            }
        }
    }

    fun selectSession(sessionId: Long) {
        _uiState.value = _uiState.value.copy(currentSessionId = sessionId, messages = emptyList())
        viewModelScope.launch { settingsRepository.setLastChatSessionId(sessionId) }
        viewModelScope.launch {
            chatRepository.messages(sessionId).collect { msgs ->
                if (_uiState.value.currentSessionId == sessionId) {
                    _uiState.value = _uiState.value.copy(messages = msgs)
                }
            }
        }
    }

    fun newSession(title: String = "New chat") {
        viewModelScope.launch {
            val id = chatRepository.createSession(
                title = title,
                systemPrompt = DEFAULT_SYSTEM_PROMPT,
                modelFilePath = (engineState.value as? EngineState.Ready)?.modelPath,
            )
            selectSession(id)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) {
                _uiState.value = _uiState.value.copy(currentSessionId = null, messages = emptyList())
            }
        }
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
                _uiState.value = _uiState.value.copy(error = result.exceptionOrNull()?.message)
            }
        }
    }

    private var generationJob: kotlinx.coroutines.Job? = null

    fun stopGeneration() {
        generationJob?.cancel()
        _uiState.value = _uiState.value.copy(isGenerating = false, streamingText = "")
    }

    fun sendMessage(text: String) {
        val sessionId = _uiState.value.currentSessionId ?: return
        if (text.isBlank() || _uiState.value.isGenerating) return
        generationJob = viewModelScope.launch {
            // Fetched directly rather than from the in-memory sessions list: right after
            // newSession() the list may not have caught up with the just-inserted row yet.
            val session = chatRepository.getSession(sessionId) ?: return@launch
            _uiState.value = _uiState.value.copy(isGenerating = true, streamingText = "", error = null)
            val startTime = System.currentTimeMillis()
            var tokenCount = 0
            chatRepository.sendMessage(session, text, genParams.value).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        tokenCount++
                        val elapsedS = (System.currentTimeMillis() - startTime) / 1000.0
                        _uiState.value = _uiState.value.copy(
                            streamingText = _uiState.value.streamingText + event.piece,
                            liveGenTps = if (elapsedS > 0) tokenCount / elapsedS else 0.0,
                        )
                    }
                    is GenerationEvent.Done -> {
                        _uiState.value = _uiState.value.copy(
                            isGenerating = false,
                            streamingText = "",
                            liveGenTps = event.stats.genTps,
                        )
                    }
                    is GenerationEvent.Failed -> {
                        _uiState.value = _uiState.value.copy(
                            isGenerating = false,
                            streamingText = "",
                            error = event.reason,
                        )
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(app.chatRepository, app.modelRepository, app.llamaEngine, app.settingsRepository) as T
    }
}

/** Small helper so the generation-params slider UI can read/write without a second StateFlow field. */
class MutableStateFlowHolder<T>(initial: T) {
    val value: T get() = _flow.value
    private val _flow = MutableStateFlow(initial)
    val flow: StateFlow<T> = _flow
    fun update(fn: (T) -> T) { _flow.value = fn(_flow.value) }
}
