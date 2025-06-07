// SettingsDataStore.kt
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.util.Log // For logging


// This creates the DataStore instance; the name "settings" is the preference file name.
// Place this at the top level of the file.
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        // This is the key we'll use to store the boolean value
        val ADD_HOUR_OFFSET_KEY = booleanPreferencesKey("add_hour_offset_preference")
        val AZAN_ENABLED_KEY = booleanPreferencesKey("azan_enabled_preference")
    }

    // This Flow will emit the latest value of our boolean preference.
    // It defaults to 'false' if the key doesn't exist (e.g., first app run).
    val addHourOffsetFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            val value = preferences[ADD_HOUR_OFFSET_KEY] ?: false
            Log.d("SettingsDataStore", "Reading ADD_HOUR_OFFSET_KEY: $value")
            value
        }

    // This suspend function saves the boolean preference value.
    suspend fun saveAddHourOffset(addOffset: Boolean) {
        Log.d("SettingsDataStore", "Saving ADD_HOUR_OFFSET_KEY: $addOffset")
        context.dataStore.edit { settings ->
            settings[ADD_HOUR_OFFSET_KEY] = addOffset
        }
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