package com.llamaaistudio.app.data.repo

import com.llamaaistudio.app.data.db.FolderDao
import com.llamaaistudio.app.data.db.FolderEntity
import com.llamaaistudio.app.data.db.NoteDao
import com.llamaaistudio.app.data.db.NoteEntity
import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.GenerationEvent
import com.llamaaistudio.app.engine.GenerationParams
import com.llamaaistudio.app.engine.LlamaEngine
import kotlinx.coroutines.flow.Flow

class NotesRepository(
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
) {
    val folders: Flow<List<FolderEntity>> = folderDao.observeAll()

    fun notes(folderId: Long?): Flow<List<NoteEntity>> = noteDao.observeByFolder(folderId)
    fun allNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()
    fun observeNote(id: Long): Flow<NoteEntity?> = noteDao.observeNote(id)
    fun search(query: String): Flow<List<NoteEntity>> = noteDao.search(query)

    suspend fun createNote(title: String, folderId: Long? = null): Long =
        noteDao.insert(NoteEntity(title = title, folderId = folderId))

    suspend fun createFolder(name: String, parentFolderId: Long? = null): Long =
        folderDao.insert(FolderEntity(name = name, parentFolderId = parentFolderId))

    suspend fun deleteFolder(folder: FolderEntity) = folderDao.delete(folder)

    /** Debounced autosave target: callers should coalesce keystrokes before calling this. */
    suspend fun saveContent(note: NoteEntity, newTitle: String, newContent: String) {
        noteDao.updateContent(note.id, newTitle, newContent, System.currentTimeMillis())
    }

    suspend fun moveNote(note: NoteEntity, folderId: Long?) {
        noteDao.updateFolder(note.id, folderId, System.currentTimeMillis())
    }

    suspend fun deleteNote(note: NoteEntity) = noteDao.delete(note)

    suspend fun saveChatMessageAsNote(content: String, folderId: Long? = null, title: String = "From chat"): Long =
        noteDao.insert(NoteEntity(title = title, content = content, folderId = folderId))

    /** Runs the local model over the note content and stores a short summary on it. */
    suspend fun summarizeWithAi(note: NoteEntity, llamaEngine: LlamaEngine): Result<String> {
        if (!llamaEngine.isLoaded) {
            return Result.failure(IllegalStateException("No model loaded — load one in the Chat workspace first"))
        }
        if (note.content.isBlank()) {
            return Result.failure(IllegalStateException("This note is empty — nothing to summarise"))
        }
        val turns = listOf(
            ChatTurn("system", "Summarise the following note in 2-4 sentences. Be concise and factual."),
            ChatTurn("user", note.content),
        )
        val sb = StringBuilder()
        var failure: String? = null
        llamaEngine.generate(turns, GenerationParams(maxTokens = 220, temperature = 0.3f)).collect { event ->
            when (event) {
                is GenerationEvent.Token -> sb.append(event.piece)
                is GenerationEvent.Failed -> failure = event.reason
                is GenerationEvent.Done -> {}
            }
        }
        if (failure != null) return Result.failure(IllegalStateException(failure))
        val summary = sb.toString().trim()
        if (summary.isEmpty()) return Result.failure(IllegalStateException("The model returned an empty summary"))
        noteDao.updateSummary(note.id, summary)
        return Result.success(summary)
    }
}
