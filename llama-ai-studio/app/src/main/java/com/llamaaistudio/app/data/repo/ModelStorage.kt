package com.llamaaistudio.app.data.repo

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Where downloaded and imported models are saved.
 *
 * - [Location.APP]: the app's private storage (default; removed with the app).
 * - [Location.PHONE]: `/sdcard/LlamaAIStudio/models` — shared storage, visible in the Files app
 *   and kept if the app is uninstalled. Needs "All files access", because llama.cpp and LiteRT
 *   open models by file path.
 * - [Location.SD_CARD]: a removable SD card, if one is inserted (its app folder; no permission).
 */
class ModelStorage(private val context: Context) {
    enum class Location(val label: String) {
        APP("App storage"),
        PHONE("Phone storage (/sdcard)"),
        SD_CARD("SD card"),
    }

    private val prefs = context.getSharedPreferences("model_storage", Context.MODE_PRIVATE)
    private val _location = MutableStateFlow(
        runCatching { Location.valueOf(prefs.getString("location", null) ?: "APP") }.getOrDefault(Location.APP)
    )
    val location: StateFlow<Location> = _location

    fun set(location: Location) {
        _location.value = location
        prefs.edit().putString("location", location.name).apply()
    }

    /** "All files access" granted — needed for [Location.PHONE]. */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** The removable SD card's app folder, or null when no card is inserted. */
    fun sdCardRoot(): File? = context.getExternalFilesDirs(null).drop(1)
        .firstOrNull { it != null && runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }

    /** The folder for [loc], or null if it isn't usable right now (no card / no permission). */
    fun dirFor(loc: Location): File? = when (loc) {
        Location.APP -> File(context.filesDir, "models")
        Location.PHONE -> if (hasAllFilesAccess()) File(Environment.getExternalStorageDirectory(), "LlamaAIStudio/models") else null
        Location.SD_CARD -> sdCardRoot()?.let { File(it, "models") }
    }?.apply { mkdirs() }

    /** Where new models go: the chosen location, or app storage if it's unavailable. */
    fun currentDir(): File = dirFor(_location.value) ?: dirFor(Location.APP)!!

    fun freeBytes(loc: Location): Long? = dirFor(loc)?.usableSpace
}
