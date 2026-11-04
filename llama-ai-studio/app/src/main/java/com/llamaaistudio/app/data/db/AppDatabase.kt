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
        LocalModelEntity::class,
        LocalImageModelEntity::class,
        GeneratedImageEntity::class,
        DevProjectEntity::class,
        WebProjectEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun modelDao(): LocalModelDao
    abstract fun imageModelDao(): LocalImageModelDao
    abstract fun generatedImageDao(): GeneratedImageDao
    abstract fun devProjectDao(): DevProjectDao
    abstract fun webProjectDao(): WebProjectDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "llama_ai_studio.db")
                // Real migrations so upgrades keep existing chats, models and projects.
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                // Anything older than v2 predates any public build; still wipe rather than crash.
                .fallbackToDestructiveMigrationFrom(1)
                .build()

        /** v3: chat sessions gain a `pinned` flag (Sessions workspace). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4: Notes workspace replaced by Web Studio — drop notes/folders, add web_projects. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `notes`")
                db.execSQL("DROP TABLE IF EXISTS `folders`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `web_projects` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`rootPath` TEXT NOT NULL, `template` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `lastOpenedAt` INTEGER NOT NULL)"
                )
            }
        }
    }
}
