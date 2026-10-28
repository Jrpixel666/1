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

private val Context.nativeWidgetDataStore by preferencesDataStore(name = "native_widget_state")

/** Persists which native (AppWidgetHost) widget IDs are active on the home screen, and their order. */
class NativeWidgetPreferences(private val context: Context) {

    private object Keys {
        val ACTIVE_IDS = stringPreferencesKey("active_native_widget_ids_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val activeWidgetIds: Flow<List<Int>> = context.nativeWidgetDataStore.data.map { prefs: Preferences ->
        val raw: String? = prefs[Keys.ACTIVE_IDS]
        if (raw == null) emptyList() else try {
            json.decodeFromString<List<Int>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun setActiveWidgetIds(ids: List<Int>) {
        context.nativeWidgetDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_IDS] = json.encodeToString(ids)
        }
    }
}
