package com.llamaaistudio.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val SESSION_SUMMARY_SELECT =
    "SELECT s.*, " +
        "(SELECT COUNT(*) FROM chat_messages c WHERE c.sessionId = s.id) AS messageCount, " +
        "(SELECT l.content FROM chat_messages l WHERE l.sessionId = s.id " +
        "ORDER BY l.createdAt DESC, l.id DESC LIMIT 1) AS lastMessage " +
        "FROM chat_sessions s"

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSession(id: Long): ChatSessionEntity?

    @Insert
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(sessionId: Long): List<ChatMessageEntity>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("UPDATE chat_sessions SET updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun touchSession(sessionId: Long, timestamp: Long)

    @Query(SESSION_SUMMARY_SELECT + " ORDER BY s.pinned DESC, s.updatedAt DESC")
    fun observeSessionSummaries(): Flow<List<ChatSessionSummary>>

    @Query(
        SESSION_SUMMARY_SELECT +
            " WHERE s.title LIKE '%' || :query || '%' OR EXISTS (" +
            "SELECT 1 FROM chat_messages q WHERE q.sessionId = s.id AND q.content LIKE '%' || :query || '%')" +
            " ORDER BY s.pinned DESC, s.updatedAt DESC"
    )
    fun searchSessionSummaries(query: String): Flow<List<ChatSessionSummary>>

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :timestamp WHERE id = :id")
    suspend fun renameSession(id: Long, title: String, timestamp: Long)

    @Query("UPDATE chat_sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE chat_sessions SET systemPrompt = :systemPrompt, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateSystemPrompt(id: Long, systemPrompt: String, timestamp: Long)

    @Query("UPDATE chat_sessions SET modelFilePath = :modelFilePath WHERE id = :id")
    suspend fun updateModel(id: Long, modelFilePath: String?)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearMessages(sessionId: Long)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId AND role = 'user'")
    suspend fun countUserMessages(sessionId: Long): Int

    @Insert
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Transaction
    suspend fun duplicateSession(sourceId: Long, newTitle: String): Long? {
        val source = getSession(sourceId) ?: return null
        val now = System.currentTimeMillis()
        val newId = insertSession(source.copy(id = 0, title = newTitle, pinned = false, createdAt = now, updatedAt = now))
        insertMessages(getMessages(sourceId).map { it.copy(id = 0, sessionId = newId) })
        return newId
    }
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    // `folderId = NULL` is never true in SQL; `IS` matches NULL too, so the "unfiled" case works.
    @Query("SELECT * FROM notes WHERE folderId IS :folderId ORDER BY updatedAt DESC")
    fun observeByFolder(folderId: Long?): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC"
    )
    fun search(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeNote(id: Long): Flow<NoteEntity?>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    // Targeted column updates: writing back a whole (possibly stale) NoteEntity snapshot let
    // a finishing AI summary overwrite edits typed while it ran, and vice versa.
    @Query("UPDATE notes SET title = :title, content = :content, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateContent(id: Long, title: String, content: String, timestamp: Long)

    @Query("UPDATE notes SET aiSummary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String?)

    @Query("UPDATE notes SET folderId = :folderId, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateFolder(id: Long, folderId: Long?, timestamp: Long)

    @Delete
    suspend fun delete(note: NoteEntity)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)
}

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY lastUsedAt DESC, downloadedAt DESC")
    fun observeAll(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_models WHERE filePath = :path")
    suspend fun get(path: String): LocalModelEntity?

    @Query("SELECT * FROM local_models WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun getMostRecentlyUsed(): LocalModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalModelEntity)

    @Query("UPDATE local_models SET lastUsedAt = :timestamp WHERE filePath = :path")
    suspend fun touchLastUsed(path: String, timestamp: Long)

    @Query("DELETE FROM local_models WHERE filePath = :path")
    suspend fun delete(path: String)
}

@Dao
interface LocalImageModelDao {
    @Query("SELECT * FROM local_image_models ORDER BY lastUsedAt DESC, downloadedAt DESC")
    fun observeAll(): Flow<List<LocalImageModelEntity>>

    @Query("SELECT * FROM local_image_models WHERE filePath = :path")
    suspend fun get(path: String): LocalImageModelEntity?

    @Query("SELECT * FROM local_image_models WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun getMostRecentlyUsed(): LocalImageModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalImageModelEntity)

    @Query("UPDATE local_image_models SET lastUsedAt = :timestamp WHERE filePath = :path")
    suspend fun touchLastUsed(path: String, timestamp: Long)

    @Query("DELETE FROM local_image_models WHERE filePath = :path")
    suspend fun delete(path: String)
}

@Dao
interface GeneratedImageDao {
    @Query("SELECT * FROM generated_images ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GeneratedImageEntity>>

    @Insert
    suspend fun insert(image: GeneratedImageEntity): Long

    @Delete
    suspend fun delete(image: GeneratedImageEntity)
}

@Dao
interface DevProjectDao {
    @Query("SELECT * FROM dev_projects ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<DevProjectEntity>>

    @Insert
    suspend fun insert(project: DevProjectEntity): Long

    @Update
    suspend fun update(project: DevProjectEntity)

    @Delete
    suspend fun delete(project: DevProjectEntity)
}
