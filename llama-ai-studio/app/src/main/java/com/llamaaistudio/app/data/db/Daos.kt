package com.llamaaistudio.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    @Transaction
    suspend fun touchSession(sessionId: Long, timestamp: Long) {
        getSession(sessionId)?.let { updateSession(it.copy(updatedAt = timestamp)) }
    }
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY updatedAt DESC")
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
