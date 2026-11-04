package com.llamaaistudio.app.data.repo

import com.llamaaistudio.app.data.db.ChatDao
import com.llamaaistudio.app.data.db.ChatMessageEntity
import com.llamaaistudio.app.data.db.ChatSessionEntity
import com.llamaaistudio.app.data.db.ChatSessionSummary
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.engine.LlamaEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful, concise AI assistant running fully on-device."

const val DEFAULT_SESSION_TITLE = "New chat"

private const val AUTO_TITLE_MAX_CHARS = 48

class ChatRepository(
    private val chatDao: ChatDao,
    private val llamaEngine: LlamaEngine,
) {
    val sessions: Flow<List<ChatSessionEntity>> = chatDao.observeSessions()

    /**
     * The session the Chat workspace is showing. App-scoped (not per-ViewModel) so the
     * Sessions workspace and Chat stay in sync: opening a session from the Sessions tab makes
     * Chat show it, and the session Chat is on is highlighted in the Sessions list.
     */
    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId

    fun setActiveSession(sessionId: Long?) {
        _activeSessionId.value = sessionId
    }

    fun sessionSummaries(query: String = ""): Flow<List<ChatSessionSummary>> =
        if (query.isBlank()) chatDao.observeSessionSummaries()
        else chatDao.searchSessionSummaries(query.trim())

    fun messages(sessionId: Long): Flow<List<ChatMessageEntity>> =
        chatDao.observeMessages(sessionId)

    suspend fun getSession(sessionId: Long): ChatSessionEntity? = chatDao.getSession(sessionId)

    suspend fun createSession(
        title: String = DEFAULT_SESSION_TITLE,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        modelFilePath: String? = null,
    ): Long = chatDao.insertSession(
        ChatSessionEntity(title = title, systemPrompt = systemPrompt, modelFilePath = modelFilePath)
    )

    suspend fun renameSession(sessionId: Long, newTitle: String) {
        val title = newTitle.trim().ifBlank { DEFAULT_SESSION_TITLE }
        chatDao.renameSession(sessionId, title, System.currentTimeMillis())
    }

    suspend fun setPinned(sessionId: Long, pinned: Boolean) = chatDao.setPinned(sessionId, pinned)

    suspend fun updateSystemPrompt(sessionId: Long, systemPrompt: String) =
        chatDao.updateSystemPrompt(sessionId, systemPrompt.trim(), System.currentTimeMillis())

    suspend fun clearMessages(sessionId: Long) {
        chatDao.clearMessages(sessionId)
        chatDao.touchSession(sessionId, System.currentTimeMillis())
    }

    suspend fun duplicateSession(sessionId: Long): Long? {
        val source = chatDao.getSession(sessionId) ?: return null
        return chatDao.duplicateSession(sessionId, "${source.title} (copy)")
    }

    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSession(sessionId)
        if (_activeSessionId.value == sessionId) _activeSessionId.value = null
    }

    /** Renders a session as a Markdown transcript (used for "Export to note"). */
    suspend fun exportAsMarkdown(sessionId: Long): Pair<String, String>? {
        val session = chatDao.getSession(sessionId) ?: return null
        val messages = chatDao.getMessages(sessionId)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val body = buildString {
            append("# ").append(session.title).append("\n\n")
            append("_Exported ").append(dateFormat.format(Date())).append(" · ")
            append(messages.size).append(" messages_\n\n")
            if (session.systemPrompt.isNotBlank()) {
                append("> **System:** ").append(session.systemPrompt.replace("\n", "\n> ")).append("\n\n")
            }
            messages.forEach { msg ->
                val who = when (msg.role) {
                    "user" -> "You"
                    "assistant" -> "Assistant"
                    else -> msg.role.replaceFirstChar { it.uppercase() }
                }
                append("**").append(who).append(":**\n\n").append(msg.content.trim()).append("\n\n")
            }
        }
        return session.title to body.trimEnd() + "\n"
    }

    /**
     * Persists the user's message, streams the assistant reply from [llamaEngine], and persists
     * the final assistant message (with perf stats) once generation completes. If generation is
     * stopped or fails part-way, whatever was produced so far is still saved to the session
     * (marked "cancelled"/"error") instead of vanishing.
     */
    fun sendMessage(
        session: ChatSessionEntity,
        userText: String,
        params: GenerationParams = GenerationParams(),
    ): Flow<GenerationEvent> = flow {
        val isFirstUserMessage = chatDao.countUserMessages(session.id) == 0
        chatDao.insertMessage(
            ChatMessageEntity(sessionId = session.id, role = "user", content = userText)
        )
        chatDao.touchSession(session.id, System.currentTimeMillis())
        if (isFirstUserMessage && session.title == DEFAULT_SESSION_TITLE) {
            chatDao.renameSession(session.id, autoTitleFor(userText), System.currentTimeMillis())
        }
        llamaEngine.currentModelPath()?.let { path ->
            if (path != session.modelFilePath) chatDao.updateModel(session.id, path)
        }

        val history = chatDao.getMessages(session.id)
        val turns = buildList {
            if (session.systemPrompt.isNotBlank()) add(ChatTurn("system", session.systemPrompt))
            history.forEach { add(ChatTurn(it.role, it.content)) }
        }

        val assembled = StringBuilder()
        var saved = false
        var failed = false
        try {
            llamaEngine.generate(turns, params).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        assembled.append(event.piece)
                        emit(event)
                    }
                    is GenerationEvent.Done -> {
                        chatDao.insertMessage(
                            ChatMessageEntity(
                                sessionId = session.id,
                                role = "assistant",
                                content = assembled.toString(),
                                promptTokens = event.stats.promptTokens,
                                genTokens = event.stats.genTokens,
                                genTps = event.stats.genTps,
                                prefillTps = event.stats.promptTps,
                                stopReason = event.stats.stopReason,
                            )
                        )
                        saved = true
                        chatDao.touchSession(session.id, System.currentTimeMillis())
                        emit(event)
                    }
                    is GenerationEvent.Failed -> {
                        failed = true
                        emit(event)
                    }
                }
            }
        } finally {
            if (!saved && assembled.isNotBlank()) {
                withContext(NonCancellable) {
                    chatDao.insertMessage(
                        ChatMessageEntity(
                            sessionId = session.id,
                            role = "assistant",
                            content = assembled.toString(),
                            stopReason = if (failed) "error" else "cancelled",
                        )
                    )
                    chatDao.touchSession(session.id, System.currentTimeMillis())
                }
            }
        }
    }

    companion object {
        fun autoTitleFor(text: String): String {
            val firstLine = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val collapsed = firstLine.replace(Regex("\\s+"), " ")
            if (collapsed.isEmpty()) return DEFAULT_SESSION_TITLE
            if (collapsed.length <= AUTO_TITLE_MAX_CHARS) return collapsed
            val cut = collapsed.take(AUTO_TITLE_MAX_CHARS)
            val atWord = cut.substringBeforeLast(' ', cut).takeIf { it.length >= AUTO_TITLE_MAX_CHARS / 2 } ?: cut
            return atWord.trimEnd(',', '.', ';', ':', '-', ' ') + "…"
        }
    }
}
