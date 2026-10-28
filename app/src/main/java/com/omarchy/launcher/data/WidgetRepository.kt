package com.omarchy.launcher.data

import android.content.Context
import java.io.File

private const val ASSET_DIR = "widgets"
private const val DEFAULT_TEMPLATE = """widget {
  size: "6x2"
}

Column {
  padding: 16
  background: surface
  radius: 8
  spacing: 8

  Text {
    text: "New widget"
    size: 18
    color: accent
  }
}
"""

/**
 * Manages the on-device, user-editable `.owl` widget files - the thing OWL's README calls
 * "a small declarative language you can edit on the phone." Files live in app-private storage
 * (not assets, which are read-only at runtime) so the in-app editor can actually save to them.
 */
class WidgetRepository(private val context: Context) {

    private val widgetsDir: File by lazy {
        File(context.filesDir, ASSET_DIR).apply { mkdirs() }
    }

    /** Copies the bundled example widgets into app storage once, the first time the app runs. */
    fun ensureDefaultsInstalled() {
        if (widgetsDir.listFiles()?.isNotEmpty() == true) return
        val assetNames = context.assets.list(ASSET_DIR) ?: return
        for (name in assetNames) {
            if (!name.endsWith(".owl")) continue
            val content = context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
            File(widgetsDir, name).writeText(content)
        }
    }

    fun listWidgetFiles(): List<String> =
        widgetsDir.listFiles { file -> file.isFile && file.name.endsWith(".owl") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun read(fileName: String): String = File(widgetsDir, fileName).takeIf { it.exists() }?.readText() ?: ""

    fun write(fileName: String, content: String) {
        File(widgetsDir, sanitizeFileName(fileName)).writeText(content)
    }

    fun delete(fileName: String) {
        File(widgetsDir, fileName).delete()
    }

    fun createBlank(fileName: String): String {
        val safeName = sanitizeFileName(fileName)
        val file = File(widgetsDir, safeName)
        if (!file.exists()) file.writeText(DEFAULT_TEMPLATE)
        return safeName
    }

    private fun sanitizeFileName(name: String): String {
        val base = name.trim().ifEmpty { "widget" }.replace(Regex("""[^a-zA-Z0-9_\-]"""), "_")
        return if (base.endsWith(".owl")) base else "$base.owl"
    }
}
