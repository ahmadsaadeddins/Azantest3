package com.example.azantest3


import SettingsDataStore
import android.app.Application
import android.content.Context
import android.icu.util.Calendar
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import kotlin.jvm.java
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit

@Entity(tableName = "prayer_times", primaryKeys = ["month_name", "day"])
data class PrayerTime(
    val month_name: String,
    val day: Int,
    val fajr: String,
    val sunrise: String,
    val dhuhr: String,
    val asr: String,
    val maghrib: String,
    val isha: String
)

@Dao
interface PrayerTimeDao {
    @Query("SELECT * FROM prayer_times")
    suspend fun getAll(): List<PrayerTime>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(prayers: List<PrayerTime>)

    @Query("SELECT * FROM prayer_times WHERE month_name = :month AND day = :day LIMIT 1")
    suspend fun getByDate(month: String, day: Int): PrayerTime?
}

@Database(entities = [PrayerTime::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun prayerTimeDao(): PrayerTimeDao
}

class PrayerRepository(private val dao: PrayerTimeDao) {
    suspend fun getAllPrayers(): List<PrayerTime> = dao.getAll()
    suspend fun getPrayerForDate(month: String, day: Int): PrayerTime? = dao.getByDate(month, day)

    suspend fun seedDatabase(application: Application) {
        Log.d("PrayerRepository", "Seeding database...")
        val inputStream = application.assets.open("mozn.json")
        val reader = InputStreamReader(inputStream)
        val type = object : TypeToken<List<PrayerTime>>() {}.type
        val prayers: List<PrayerTime> = Gson().fromJson(reader, type)
        dao.insertAll(prayers)
    }
}

class PrayerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "prayers.db"
    ).build()


    private val repository = PrayerRepository(db.prayerTimeDao())

    private val _prayers = mutableStateOf<List<PrayerTime>>(emptyList())
    val prayers: State<List<PrayerTime>> = _prayers

    private val azanScheduler = AzanScheduler(application.applicationContext) // Initialize Scheduler
    private val settingsDataStore = SettingsDataStore(application.applicationContext)
    // --- State for enabling/disabling Azan ---
    // You'll need to persist this setting similar to addHourOffsetSetting
    companion object { // Define key in companion object for SettingsDataStore
        val AZAN_ENABLED_KEY = booleanPreferencesKey("azan_enabled_preference")
    }

    // In SettingsDataStore.kt, add:
    // val azanEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[AZAN_ENABLED_KEY] ?: false }
    // suspend fun saveAzanEnabled(enabled: Boolean) { context.dataStore.edit { it[AZAN_ENABLED_KEY] = enabled } }

    val azanEnabledSetting: StateFlow<Boolean> = settingsDataStore.azanEnabledFlow // Assuming you add this to SettingsDataStore
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = true // Default to false
        )

    fun setAzanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveAzanEnabled(enabled) // Assuming you add this to SettingsDataStore
//            if (enabled) {
//                scheduleRelevantAzans()
//            } else {
//                // Get current day's prayer *dates* to cancel them correctly
//                val (today, todayPrayersAsDates, _) = todayAndTomorrow(prayerTimesToDateList(prayers.value, PRAYER_NAMES, addHourOffsetSetting.value))
//                todayPrayersAsDates?.let { azanScheduler.cancelAllAzans(it) }
//            }
        }
    }
    // --- End Azan Enable/Disable State ---

    // --- Start: DataStore Integration ---

    // StateFlow to hold the current offset setting, read from DataStore
    val addHourOffsetSetting: StateFlow<Boolean> = settingsDataStore.addHourOffsetFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L), // How long to keep it active
            initialValue = false // Default before DataStore emits its stored value
        )

    // Function to update the setting in DataStore (called from your Composable)
    fun setAddHourOffset(addOffset: Boolean) {
        Log.d("PrayerViewModel", "setAddHourOffset called with: $addOffset")
        viewModelScope.launch {
            settingsDataStore.saveAddHourOffset(addOffset)
        }
    }
    // --- End: DataStore Integration ---

    init {
        Log.d("PrayerViewModel", "Initializing PrayerViewModel...")
        viewModelScope.launch {
            // Check if seeding is necessary (e.g., only if DB is empty)
            // This is just an example; your seeding logic might be different.
            val currentPrayers = repository.getAllPrayers()
            if (currentPrayers.isEmpty()) {
                Log.d("PrayerViewModel", "Database is empty, seeding...")
                repository.seedDatabase(application)
            }
            // Load prayers after potential seeding
            _prayers.value = repository.getAllPrayers()
            updateAzanSchedules()
            Log.d("PrayerViewModel", "Prayers loaded, count: ${_prayers.value.size}")
            Log.d("PrayerViewModel", "Daily Azan Rescheduler worker enqueued.")
        }
        viewModelScope.launch {
            azanEnabledSetting.collect { enabled ->
                Log.d("PrayerViewModel_Collect", "azanEnabledSetting changed to: $enabled. Updating schedules.")
                updateAzanSchedules() // Call whenever this setting changes or is first loaded
            }
        }

        // Optional: Log the initial value collected from DataStore for addHourOffsetSetting
        viewModelScope.launch {
            addHourOffsetSetting.collect { offsetValue ->
                Log.d("PrayerViewModel", "Collected addHourOffsetSetting from DataStore: $offsetValue")
                updateAzanSchedules() // Call whenever this setting changes or is first loaded
            }
        }
    }
    private fun scheduleRelevantAzans(
        currentPrayerEntities: List<PrayerTime> = prayers.value, // Default to current ViewModel state
        currentOffsetEnabled: Boolean = addHourOffsetSetting.value
    ) {
        if (!azanEnabledSetting.value) {
            Log.d("PrayerViewModel", "scheduleRelevantAzans: Aborting, Azan is not enabled (checked via azanEnabledSetting.value).")
            return
        }
        if (currentPrayerEntities.isEmpty()) {
            Log.d("PrayerViewModel", "scheduleRelevantAzans: Not scheduling Azans: prayers empty.")
            return
        }
        Log.d("PrayerViewModel", "scheduleRelevantAzans: Proceeding. Offset: $currentOffsetEnabled. Entities: ${currentPrayerEntities.size}")


        // 1. Convert current PrayerTime entities to List<List<Date>>
        val allPrayersAsDates = prayerTimesToDateList(currentPrayerEntities, PRAYER_NAMES, currentOffsetEnabled)

        // 2. Get today's and tomorrow's Fajr prayer Date objects
        val (_, todayPrayers, tomorrowPrayers) = todayAndTomorrow(allPrayersAsDates)

        val prayerTimesToSchedule = mutableListOf<Date>()
        val prayerNamesToSchedule = mutableListOf<String>()

        todayPrayers?.forEachIndexed { index, prayerDate ->
            if (prayerDate.after(Date())) { // Only schedule future prayers for today
                prayerTimesToSchedule.add(prayerDate)
                prayerNamesToSchedule.add(PRAYER_NAMES_ARABIC[index]) // Or PRAYER_NAMES
            }
        }

        // Optionally, add tomorrow's Fajr
//        tomorrowPrayers?.getOrNull(0)?.let { fajrTomorrow ->
//            if (fajrTomorrow.after(Date())) {
//                prayerTimesToSchedule.add(fajrTomorrow)
//                prayerNamesToSchedule.add("${PRAYER_NAMES_ARABIC[0]} (الغد)")
//            }
//        }

        if (prayerTimesToSchedule.isNotEmpty()) {
            // Cancel any existing alarms before scheduling new ones to prevent duplicates
            // This needs a robust way to get ALL potentially scheduled alarms, or ensure request codes are consistent
            // For simplicity here, we assume we only care about today's and tomorrow's Fajr as scheduled above
            // A more robust cancelAll might be needed if scheduling logic is more complex.
            // Let's assume scheduleDailyAzans handles replacing based on PendingIntent flags for now.

            Log.d("PrayerViewModel", "Scheduling Azans for: ${prayerNamesToSchedule.joinToString()}")
            azanScheduler.scheduleDailyAzans(prayerTimesToSchedule, prayerNamesToSchedule)
        } else {
            Log.d("PrayerViewModel", "No future prayer times found to schedule for Azan today.")
        }
    }

    // Call this if settings change that affect prayer times (like addHourOffset)
    // or when Azan is enabled/disabled
    // updateAzanSchedules already correctly reads current .value of settings
    fun updateAzanSchedules() {
        Log.d("PrayerViewModel", "updateAzanSchedules called. AzanEnabled: ${azanEnabledSetting.value}, Offset: ${addHourOffsetSetting.value}, Prayers: ${prayers.value.size}")
        if (azanEnabledSetting.value && prayers.value.isNotEmpty()) { // Ensure prayers is also checked
            scheduleRelevantAzans() // Uses .value internally
        } else {
            // Cancellation logic
            if (prayers.value.isNotEmpty()){ // Only try to get dates if prayers exist
                val (today, todayPrayersAsDates, _) = todayAndTomorrow(prayerTimesToDateList(prayers.value, PRAYER_NAMES, addHourOffsetSetting.value))
                todayPrayersAsDates?.let {
                    Log.d("PrayerViewModel", "Cancelling Azans in updateAzanSchedules.")
                    azanScheduler.cancelAllAzans(it)
                }
            } else {
                Log.d("PrayerViewModel", "No prayers to base cancellation on, or Azan is disabled.")
                // Consider if you need a more general azanScheduler.cancelAllOfThem() if no dates are available
            }
        }
    }


}

