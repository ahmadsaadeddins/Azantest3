package com.example.azantest3

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsDataStore(private val context: Context) {

    companion object {
        // Existing keys
        val ADD_HOUR_OFFSET_KEY = booleanPreferencesKey("add_hour_offset_preference")
        val AZAN_ENABLED_KEY = booleanPreferencesKey("azan_enabled_preference")
        val PRAYER_CACHE_KEY = stringPreferencesKey("cached_prayer_times")
        val PRAYER_CACHE_DATE_KEY = stringPreferencesKey("cached_prayer_times_date")

        // New Iqama keys
        val IQAMA_FAJR_KEY = intPreferencesKey("iqama_fajr")
        val SUNRISE_OFFSET_KEY = intPreferencesKey("sunrise_offset")
        val IQAMA_DHUHR_KEY = intPreferencesKey("iqama_dhuhr")
        val IQAMA_ASR_KEY = intPreferencesKey("iqama_asr")
        val IQAMA_MAGHRIB_KEY = intPreferencesKey("iqama_maghrib")
        val IQAMA_ISHA_KEY = intPreferencesKey("iqama_isha")
    }

    // Existing Flows
    val addHourOffsetFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            val value = preferences[ADD_HOUR_OFFSET_KEY] ?: false
            Log.d("SettingsDataStore", "Reading ADD_HOUR_OFFSET_KEY: $value")
            value
        }

    val azanEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[AZAN_ENABLED_KEY] ?: true
        }

    // New Iqama Settings Flow
    val iqamaSettingsFlow: Flow<IqamaSettings> = context.dataStore.data
        .map { preferences ->
            IqamaSettings(
                fajrIqama = preferences[IQAMA_FAJR_KEY] ?: 15,
                sunriseOffset = preferences[SUNRISE_OFFSET_KEY] ?: 15,
                dhuhrIqama = preferences[IQAMA_DHUHR_KEY] ?: 15,
                asrIqama = preferences[IQAMA_ASR_KEY] ?: 15,
                maghribIqama = preferences[IQAMA_MAGHRIB_KEY] ?: 15,
                ishaIqama = preferences[IQAMA_ISHA_KEY] ?: 15
            )
        }

    // Existing suspend functions
    suspend fun saveAddHourOffset(addOffset: Boolean) {
        Log.d("SettingsDataStore", "Saving ADD_HOUR_OFFSET_KEY: $addOffset")
        context.dataStore.edit { settings ->
            settings[ADD_HOUR_OFFSET_KEY] = addOffset
        }
    }

    suspend fun cachePrayerTimes(json: String, date: String) {
        Log.d("SettingsDataStore", "Caching prayers for $date")
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

    suspend fun saveAzanEnabled(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[AZAN_ENABLED_KEY] = enabled
        }
    }

    // New function to update iqama settings
    suspend fun updateIqamaSettings(prayerName: String, minutes: Int) {
        context.dataStore.edit { preferences ->
            val key = when (prayerName) {
                "fajr" -> IQAMA_FAJR_KEY
                "sunrise" -> SUNRISE_OFFSET_KEY
                "dhuhr" -> IQAMA_DHUHR_KEY
                "asr" -> IQAMA_ASR_KEY
                "maghrib" -> IQAMA_MAGHRIB_KEY
                "isha" -> IQAMA_ISHA_KEY
                else -> return@edit
            }
            preferences[key] = minutes
            Log.d("SettingsDataStore", "Updated iqama setting for $prayerName: $minutes minutes")
        }
    }

    // Optional: Function to get all iqama settings at once
//    suspend fun getAllIqamaSettings(): IqamaSettings {
//        return context.dataStore.data.first().let { preferences ->
//            IqamaSettings(
//                fajrIqama = preferences[IQAMA_FAJR_KEY] ?: 20,
//                sunriseOffset = preferences[SUNRISE_OFFSET_KEY] ?: 20,
//                dhuhrIqama = preferences[IQAMA_DHUHR_KEY] ?: 15,
//                asrIqama = preferences[IQAMA_ASR_KEY] ?: 15,
//                maghribIqama = preferences[IQAMA_MAGHRIB_KEY] ?: 5,
//                ishaIqama = preferences[IQAMA_ISHA_KEY] ?: 15
//            )
//        }
//    }
}

// Data class for Iqama Settings
data class IqamaSettings(
    val fajrIqama: Int = 20,
    val sunriseOffset: Int = 20,
    val dhuhrIqama: Int = 15,
    val asrIqama: Int = 15,
    val maghribIqama: Int = 5,
    val ishaIqama: Int = 15
)