package com.omarchy.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.widgetDataStore by preferencesDataStore(name = "widget_state")

/** Persists which widget files are active on the home screen, and in what order. */
class WidgetPreferences(private val context: Context) {

    private object Keys {
        val ACTIVE_WIDGETS = stringPreferencesKey("active_widgets_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val activeWidgetFiles: Flow<List<String>> = context.widgetDataStore.data.map { prefs: Preferences ->
        val raw: String? = prefs[Keys.ACTIVE_WIDGETS]
        if (raw == null) emptyList() else try {
            json.decodeFromString<List<String>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun setActiveWidgetFiles(fileNames: List<String>) {
        context.widgetDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_WIDGETS] = json.encodeToString(fileNames)
        }
    }
}
