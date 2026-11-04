package com.llamaaistudio.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        FolderEntity::class,
        NoteEntity::class,
        LocalModelEntity::class,
        LocalImageModelEntity::class,
        GeneratedImageEntity::class,
        DevProjectEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun modelDao(): LocalModelDao
    abstract fun imageModelDao(): LocalImageModelDao
    abstract fun generatedImageDao(): GeneratedImageDao
    abstract fun devProjectDao(): DevProjectDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "llama_ai_studio.db")
                // Pre-release app, no installed base to preserve yet; revisit with a real
                // migration once this ships.
                .fallbackToDestructiveMigration()
                .build()
    }
}
