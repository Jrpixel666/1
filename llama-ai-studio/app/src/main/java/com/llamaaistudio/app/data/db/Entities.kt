package com.llamaaistudio.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val systemPrompt: String = "",
    val modelFilePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Pinned sessions sort above everything else in the Sessions list. Added in DB v3. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
)

/** A session plus the at-a-glance stats the Sessions list shows (one query, no N+1). */
data class ChatSessionSummary(
    @Embedded val session: ChatSessionEntity,
    val messageCount: Int,
    val lastMessage: String?,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "system" | "user" | "assistant"
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val promptTokens: Int? = null,
    val genTokens: Int? = null,
    val genTps: Double? = null,
    val prefillTps: Double? = null,
    val stopReason: String? = null,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentFolderId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        )
    ],
    indices = [Index("folderId")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val title: String,
    val content: String = "",
    val aiSummary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "local_models")
data class LocalModelEntity(
    @PrimaryKey val filePath: String,
    val displayName: String,
    val hfModelId: String? = null,
    val hfFilename: String? = null,
    val sizeBytes: Long,
    val quantization: String? = null,
    val paramCount: Long? = null,
    val sha256: String? = null,
    val sha256Verified: Boolean = false,
    val downloadedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
)

@Entity(tableName = "local_image_models")
data class LocalImageModelEntity(
    @PrimaryKey val filePath: String,
    val displayName: String,
    val hfModelId: String? = null,
    val hfFilename: String? = null,
    val sizeBytes: Long,
    val quantization: String? = null,
    val sha256: String? = null,
    val sha256Verified: Boolean = false,
    val downloadedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
)

@Entity(tableName = "generated_images")
data class GeneratedImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long,
    val modelFilePath: String? = null,
    val genMs: Double,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "dev_projects")
data class DevProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val rootPath: String,
    val template: String, // "empty-compose" | "empty-views" | "library"
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis(),
)
