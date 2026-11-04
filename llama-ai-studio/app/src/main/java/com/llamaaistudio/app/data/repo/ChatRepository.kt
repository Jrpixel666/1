package com.llamaaistudio.app.data.repo

import com.llamaaistudio.app.data.db.ChatDao
import com.llamaaistudio.app.data.db.ChatMessageEntity
import com.llamaaistudio.app.data.db.ChatSessionEntity
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.engine.LlamaEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful, concise AI assistant running fully on-device."

class ChatRepository(
    private val chatDao: ChatDao,
    private val llamaEngine: LlamaEngine,
) {
    val sessions: Flow<List<ChatSessionEntity>> = chatDao.observeSessions()

    fun messages(sessionId: Long): Flow<List<ChatMessageEntity>> =
        chatDao.observeMessages(sessionId)

    suspend fun getSession(sessionId: Long): ChatSessionEntity? = chatDao.getSession(sessionId)

    suspend fun createSession(
        title: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        modelFilePath: String? = null,
    ): Long = chatDao.insertSession(
        ChatSessionEntity(title = title, systemPrompt = systemPrompt, modelFilePath = modelFilePath)
    )

    suspend fun renameSession(session: ChatSessionEntity, newTitle: String) {
        chatDao.updateSession(session.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSession(sessionId: Long) = chatDao.deleteSession(sessionId)

    /**
     * Persists the user's message, streams the assistant reply from [llamaEngine], and persists
     * the final assistant message (with perf stats) once generation completes.
     */
    fun sendMessage(
        session: ChatSessionEntity,
        userText: String,
        params: GenerationParams = GenerationParams(),
    ): Flow<GenerationEvent> = flow {
        chatDao.insertMessage(
            ChatMessageEntity(sessionId = session.id, role = "user", content = userText)
        )
        chatDao.touchSession(session.id, System.currentTimeMillis())

        val history = chatDao.getMessages(session.id)
        val turns = buildList {
            if (session.systemPrompt.isNotBlank()) add(ChatTurn("system", session.systemPrompt))
            history.forEach { add(ChatTurn(it.role, it.content)) }
        }

        val assembled = StringBuilder()
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
                    chatDao.touchSession(session.id, System.currentTimeMillis())
                    emit(event)
                }
                is GenerationEvent.Failed -> emit(event)
            }
        }
    }
}
