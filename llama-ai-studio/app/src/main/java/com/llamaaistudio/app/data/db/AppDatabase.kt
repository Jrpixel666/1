package com.llamaaistudio.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3,
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
                // Real migration for the sessions upgrade so existing chats/notes survive it.
                .addMigrations(MIGRATION_2_3)
                // Anything older than v2 predates any public build; still wipe rather than crash.
                .fallbackToDestructiveMigrationFrom(1)
                .build()

        /** v3: chat sessions gain a `pinned` flag (Sessions workspace). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
