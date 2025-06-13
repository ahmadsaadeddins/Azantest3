package com.example.azantest3// com.example.azantest3.SettingsDataStore.kt
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.util.Log // For logging
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first



// This creates the DataStore instance; the name "settings" is the preference file name.
// Place this at the top level of the file.

class SettingsDataStore(private val context: Context) {


    companion object {
        // This is the key we'll use to store the boolean value
        val ADD_HOUR_OFFSET_KEY = booleanPreferencesKey("add_hour_offset_preference")
        val AZAN_ENABLED_KEY = booleanPreferencesKey("azan_enabled_preference")
        val PRAYER_CACHE_KEY = stringPreferencesKey("cached_prayer_times")
        val PRAYER_CACHE_DATE_KEY = stringPreferencesKey("cached_prayer_times_date")
    }

    // This Flow will emit the latest value of our boolean preference.
    // It defaults to 'false' if the key doesn't exist (e.g., first app run).
    val addHourOffsetFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            val value = preferences[ADD_HOUR_OFFSET_KEY] ?: false
            Log.d("com.example.azantest3.SettingsDataStore", "Reading ADD_HOUR_OFFSET_KEY: $value")
            value
        }

    // This suspend function saves the boolean preference value.
    suspend fun saveAddHourOffset(addOffset: Boolean) {
        Log.d("com.example.azantest3.SettingsDataStore", "Saving ADD_HOUR_OFFSET_KEY: $addOffset")
        context.dataStore.edit { settings ->
            settings[ADD_HOUR_OFFSET_KEY] = addOffset
        }
    }

    suspend fun cachePrayerTimes(json: String, date: String) {
        Log.d("com.example.azantest3.SettingsDataStore", "Caching prayers for $date")
        context.dataStore.edit { prefs ->
            prefs[PRAYER_CACHE_KEY] = json
            prefs[PRAYER_CACHE_DATE_KEY] = date
        }
    }

    suspend fun getCachedPrayerTimes(): Pair<String?, String?> {
        val prefs = context.dataStore.data.first()
        val json = prefs[PRAYER_CACHE_KEY]
        val date = prefs[PRAYER_CACHE_DATE_KEY]
        return Pair(json, date)
    }

    // New Flow and Save function for Azan enabled state
    val azanEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[AZAN_ENABLED_KEY] ?: true // Default to false
        }
    suspend fun saveAzanEnabled(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[AZAN_ENABLED_KEY] = enabled
        }
    }
}