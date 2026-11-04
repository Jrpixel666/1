package com.llamaaistudio.app.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Mirrors a project tree into the shared Downloads collection via MediaStore so Termux (after
 * `termux-setup-storage`) can reach it. Direct java.io.File writes to shared storage are
 * blocked by scoped storage, and MediaStore needs no runtime permission for app-owned files.
 */
object DownloadsExporter {

    /** Copies every file under [root] to `Download/<relativeBase>/…`; returns the visible path. */
    fun exportTree(context: Context, root: File, relativeBase: String, skipDirs: Set<String> = emptySet()): String {
        val base = "Download/${relativeBase.trim('/')}/"
        val files = root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name !in skipDirs }
            .filter { it.isFile }
        for (file in files) {
            val relPath = file.relativeTo(root).path
            val dirPart = relPath.substringBeforeLast('/', "")
            exportFile(context, file, if (dirPart.isEmpty()) base else "$base$dirPart/")
        }
        return base.trimEnd('/')
    }

    private fun exportFile(context: Context, file: File, relativePath: String) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI // minSdk 31
        // Re-exporting used to insert a fresh row every time; MediaStore then renamed the new
        // copy ("index (1).html") and Termux kept serving/building the stale original.
        // Overwrite our own earlier export of the same path instead.
        val existing = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(relativePath, file.name),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

        val uri = existing ?: context.contentResolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            },
        ) ?: return
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }
}
