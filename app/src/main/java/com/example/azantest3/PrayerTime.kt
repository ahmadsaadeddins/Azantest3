package com.example.azantest3


import android.app.Application
import android.content.Context
import android.icu.util.Calendar
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
import com.example.azantest3.datastore.PRAYER_NAMES
import com.example.azantest3.datastore.PRAYER_NAMES_ARABIC
import kotlinx.coroutines.launch
import kotlin.jvm.java
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Date


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

//    @Query("SELECT * FROM prayer_times WHERE month_name = :month AND day IN (:day1, :day2)")
//    suspend fun getTwoDays(month: String, day1: Int, day2: Int): List<PrayerTime>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(prayers: List<PrayerTime>)

    @Query("SELECT * FROM prayer_times WHERE month_name = :month AND day = :day LIMIT 1")
    suspend fun getByDate(month: String, day: Int): PrayerTime?
}

@Database(entities = [PrayerTime::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun prayerTimeDao(): PrayerTimeDao
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "prayers.db"
                )
                    // .addCallback(AppDatabaseCallback(scope)) // If you have a prepopulate callback
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}







fun mapMonthNameToIndexMultilingual(monthName: String): Int {
    Log.d("PrayerTime", "Received month name: '$monthName'")

    val normalizedMonthName = monthName.lowercase().trim()
    Log.d("PrayerTime", "normalizedMonthName: '$normalizedMonthName' ")

    return when (normalizedMonthName) {
        // English Full Names
        "january" -> Calendar.JANUARY
        "february" -> Calendar.FEBRUARY
        "march" -> Calendar.MARCH
        "april" -> Calendar.APRIL
        "may" -> Calendar.MAY
        "june" -> Calendar.JUNE
        "july" -> Calendar.JULY
        "august" -> Calendar.AUGUST
        "september" -> Calendar.SEPTEMBER
        "october" -> Calendar.OCTOBER
        "november" -> Calendar.NOVEMBER
        "december" -> Calendar.DECEMBER
        // English 3-letter Abbreviations
        "jan" -> Calendar.JANUARY
        "feb" -> Calendar.FEBRUARY
        "mar" -> Calendar.MARCH
        "apr" -> Calendar.APRIL
        // "may" is covered
        "jun" -> Calendar.JUNE
        "jul" -> Calendar.JULY
        "aug" -> Calendar.AUGUST
        "sep" -> Calendar.SEPTEMBER
        "oct" -> Calendar.OCTOBER
        "nov" -> Calendar.NOVEMBER
        "dec" -> Calendar.DECEMBER

        // Arabic Month Names (Add all as needed)
        // These are examples; verify the exact lowercase strings you expect
        "يناير" -> Calendar.JANUARY   // January
        "فبراير" -> Calendar.FEBRUARY  // February
        "مارس" -> Calendar.MARCH    // March
        "أبريل" -> Calendar.APRIL   // April
        "مايو" -> Calendar.MAY      // May
        "يونيو" -> Calendar.JUNE    // June  <<< THIS WOULD FIX THE CURRENT ERROR
        "يوليو" -> Calendar.JULY    // July
        "أغسطس" -> Calendar.AUGUST  // August
        "سبتمبر" -> Calendar.SEPTEMBER // September
        "أكتوبر" -> Calendar.OCTOBER  // October
        "نوفمبر" -> Calendar.NOVEMBER // November
        "ديسمبر" -> Calendar.DECEMBER  // December

        else -> throw IllegalArgumentException("Invalid month name: $monthName (Processed as: $normalizedMonthName)")
    }
}

fun mapIndexToMonthNameAbrrv(monthIndex: Int): String {
    // e.g., Calendar.JANUARY (0) -> "January" (or whatever format is in your DB)
    // This should match the case and format in your database exactly.
    val months = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", // Changed to abbreviations
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
    if (monthIndex in months.indices) {
        return months[monthIndex]
    }
    throw IllegalArgumentException("Invalid month index: $monthIndex")

}

class PrayerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "prayers.db"
    ).build()



    private val _prayers = mutableStateOf<List<PrayerTime>>(emptyList())
    val prayers: State<List<PrayerTime>> = _prayers

    private val azanScheduler = AzanScheduler(application.applicationContext) // Initialize Scheduler
    private val settingsDataStore = SettingsDataStore(application)
    private val prayerRepository = PrayerRepository(db.prayerTimeDao(), settingsDataStore)
    val iqamaSettings: StateFlow<IqamaSettings> = settingsDataStore.iqamaSettingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = IqamaSettings()
        )

    fun getIqamaOffsetForPrayer(prayerIndex: Int): Int {
        val settings = iqamaSettings.value
        return when (prayerIndex) {
            0 -> settings.fajrIqama
            1 -> settings.sunriseOffset
            2 -> settings.dhuhrIqama
            3 -> settings.asrIqama
            4 -> settings.maghribIqama
            5 -> settings.ishaIqama
            else -> 15
        }
    }
    fun updateIqamaSetting(prayerName: String, minutes: Int) {
        viewModelScope.launch {
            settingsDataStore.updateIqamaSettings(prayerName, minutes)
        }
    }
    // Add function to calculate Duha time
//    fun getDuhaTime(sunriseTime: Date): Date {
//        val settings = iqamaSettings.value
//        return Date(sunriseTime.time + (settings.sunriseOffset * 60 * 1000))
//    }
    // --- State for enabling/disabling Azan ---
    // You'll need to persist this setting similar to addHourOffsetSetting
//    companion object { // Define key in companion object for com.example.azantest3.SettingsDataStore
//        val AZAN_ENABLED_KEY = booleanPreferencesKey("azan_enabled_preference")
//    }

    // In com.example.azantest3.SettingsDataStore.kt, add:
    // val azanEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[AZAN_ENABLED_KEY] ?: false }
    // suspend fun saveAzanEnabled(enabled: Boolean) { context.dataStore.edit { it[AZAN_ENABLED_KEY] = enabled } }

    val azanEnabledSetting: StateFlow<Boolean> = settingsDataStore.azanEnabledFlow // Assuming you add this to com.example.azantest3.SettingsDataStore
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = true // Default to false
        )

    fun setAzanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveAzanEnabled(enabled) // Assuming you add this to com.example.azantest3.SettingsDataStore
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
            iqamaSettings.collect { settings ->
                Log.d("PrayerViewModel", "Iqama settings updated: $settings")
                updateAzanSchedules()
            }
        }

        viewModelScope.launch {
//            val calendar = Calendar.getInstance()
//            val monthFormatter = SimpleDateFormat("MMM", Locale.getDefault())
//            val currentMonth: String = monthFormatter.format(calendar.time)
//            val currentDayOfMonth: Int = calendar.get(Calendar.DAY_OF_MONTH)

            val currentPrayers = prayerRepository.getTodayAndTomorrowPrayersCached()
//            val currentPrayers = repository.getAllPrayers()

            if (currentPrayers.isEmpty()) {
                Log.d("PrayerViewModel", "Database is empty, seeding...")
                prayerRepository.seedDatabase(application)
            }
            // Load prayers after potential seeding
            _prayers.value = prayerRepository.getAllPrayers()
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
        val allPrayersAsDates = prayerTimesToDateList(currentPrayerEntities,
            PRAYER_NAMES, currentOffsetEnabled)

        // 2. Get today's and tomorrow's Fajr prayer Date objects
        val (_, todayPrayers) = todayAndTomorrow(allPrayersAsDates)

        val prayerTimesToSchedule = mutableListOf<Date>()
        val prayerNamesToSchedule = mutableListOf<String>()

        todayPrayers?.forEachIndexed { index, prayerDate ->
            if (prayerDate.after(Date())) { // Only schedule future prayers for today
                prayerTimesToSchedule.add(prayerDate)
                prayerNamesToSchedule.add(PRAYER_NAMES_ARABIC[index]) // Or PRAYER_NAMES
            }
            // Add iqama time if it's not sunrise
                val iqamaOffset = getIqamaOffsetForPrayer(index)
                val iqamaTime = Date(prayerDate.time + (iqamaOffset * 60 * 1000))
                if (iqamaTime.after(Date())) {
                    prayerTimesToSchedule.add(iqamaTime)
                    prayerNamesToSchedule.add("إقامة ${PRAYER_NAMES_ARABIC[index]}")
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
    private fun updateAzanSchedules() {
        Log.d("PrayerViewModel", "updateAzanSchedules called. AzanEnabled: ${azanEnabledSetting.value}, Offset: ${addHourOffsetSetting.value}, Prayers: ${prayers.value.size}")
        if (azanEnabledSetting.value && prayers.value.isNotEmpty()) { // Ensure prayers is also checked
            scheduleRelevantAzans() // Uses .value internally
        } else {
            // Cancellation logic
            if (prayers.value.isNotEmpty()){ // Only try to get dates if prayers exist
                val ( er, todayPrayersAsDates, _) = todayAndTomorrow(prayerTimesToDateList(prayers.value,
                    PRAYER_NAMES, addHourOffsetSetting.value))
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

// In PrayerRepository.kt

// Helper data class for the widget
