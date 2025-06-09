package com.example.azantest3

import SettingsDataStore
import android.app.Application
import android.content.Context
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.util.Log
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.Date
import java.util.Locale

// Assuming these are top-level or correctly imported:
// import com.example.azantest3.AppDatabase
// import com.example.azantest3.PrayerRepository
// import com.example.azantest3.SettingsDataStore
// import com.example.azantest3.AzanScheduler
// import com.example.azantest3.PrayerTime // Your Room Entity

class DailyAzanReschedulerWorker(
    private val appContext: Context, // Renamed for clarity, it's the applicationContext
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DailyAzanWorker"
        // Ensure these are defined or accessible, similar to PrayerViewModel
        val RELEVANT_PRAYER_NAMES_FOR_AZAN = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
        // If PRAYER_NAMES_ARABIC is used by AzanScheduler, make it available or pass appropriate names
        // val PRAYER_NAMES_ARABIC = listOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: Worker STARTED. Time to re-evaluate Azan schedules.")
        var db: AppDatabase? = null // Declare db instance

        try {
            // 1. Initialize Database (similar to PrayerViewModel)
            // Note: appContext is already the application context from CoroutineWorker
            db = Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                "prayers.db"
            ).build() // Consider .fallbackToDestructiveMigration() if schema changes are frequent during dev

            // 2. Initialize Repository (similar to PrayerViewModel)
            val prayerRepository = PrayerRepository(db.prayerTimeDao())

            // 3. Initialize SettingsDataStore (similar to PrayerViewModel)
            val settingsDataStore = SettingsDataStore(appContext)

            // 4. Initialize AzanScheduler
            val azanScheduler = AzanScheduler(appContext)

            Log.d(TAG, "doWork: Dependencies initialized.")

            // 5. Fetch settings from DataStore
            val isAzanEnabled = settingsDataStore.azanEnabledFlow.first()
            val isOffsetEnabled = settingsDataStore.addHourOffsetFlow.first() // Assuming you might use this
            Log.d(TAG, "doWork: Settings loaded - AzanEnabled: $isAzanEnabled, OffsetEnabled: $isOffsetEnabled")

            if (!isAzanEnabled) {
                Log.i(TAG, "doWork: Azan is NOT enabled. Cancelling all scheduled Azans.")
                azanScheduler.cancelAllScheduledAzans() // Ensure this method exists and is robust
                return Result.success()
            }

            val nowCalendar = Calendar.getInstance()
            val currentDayOfMonth: Int = nowCalendar.get(Calendar.DAY_OF_MONTH)

// *** Assumption: Your PrayerTime.month_name is stored as English abbreviation like "Jan", "Feb", "Jun" ***
// If it's stored as full English name like "January", "June", use "MMMM" here.
            val monthFormatterForQuery = SimpleDateFormat("MMM", Locale.ENGLISH) // Use "MMM" for "Jun", "Jul" etc.
            val currentMonthNameForQuery: String = monthFormatterForQuery.format(nowCalendar.time)
            Log.d(TAG, "doWork: Fetching prayers for Month: '$currentMonthNameForQuery', Day: $currentDayOfMonth")


// Fetch prayers using the consistent month name format you expect in your DB/repository
            val allPrayerTimeEntities: List<PrayerTime> = prayerRepository.getTodayAndTomorrowPrayers(currentMonthNameForQuery, currentDayOfMonth)

            Log.d(TAG, "doWork: Fetched ${allPrayerTimeEntities.size} prayer entities from DB.")

            if (allPrayerTimeEntities.isEmpty()) {
                Log.i(TAG, "doWork: No prayer times found in the database for $currentMonthNameForQuery, day $currentDayOfMonth. Cancelling all Azans.")
                azanScheduler.cancelAllScheduledAzans()
                db?.close() // Ensure DB is closed if returning early
                return Result.success()
            }
// --- Core Rescheduling Logic ---
            azanScheduler.cancelAllScheduledAzans() // Clear any old alarms first

            Log.d(TAG, "doWork: Azan enabled. Proceeding to reschedule for the current day.")
            Log.d(TAG, "doWork: Total prayer entities available after fetch: ${allPrayerTimeEntities.size}")
            if (allPrayerTimeEntities.isNotEmpty()) {
                Log.d(TAG, "doWork: First entity in list - Month: ${allPrayerTimeEntities.first().month_name}, Day: ${allPrayerTimeEntities.first().day}")
                Log.d(TAG, "doWork: Last entity in list - Month: ${allPrayerTimeEntities.last().month_name}, Day: ${allPrayerTimeEntities.last().day}")
            }

// Find the PrayerTime entity for today from the fetched list.
// The currentMonthNameForQuery and currentDayOfMonth are already defined and consistent.
            Log.d(TAG, "doWork: Searching for today's prayer entity for Month: '$currentMonthNameForQuery', Day: $currentDayOfMonth within the fetched list.")

            val todayPrayerTimesEntity = allPrayerTimeEntities.find { pt ->
                // Ensure the comparison is robust. The month_name from DB should match currentMonthNameForQuery.
                val monthMatches = pt.month_name.equals(currentMonthNameForQuery, ignoreCase = true)
                val dayMatches = pt.day == currentDayOfMonth

                // Optional: Log if a specific entity is being checked (can be verbose)
                // Log.v(TAG, "Checking entity: DBMonth='${pt.month_name}', DBDay=${pt.day} against TargetMonth='$currentMonthNameForQuery', TargetDay=$currentDayOfMonth. MonthMatch=$monthMatches, DayMatch=$dayMatches")

                monthMatches && dayMatches
            }


            if (todayPrayerTimesEntity == null) {
                Log.w(TAG, "doWork: No prayer times entity found for today ( $currentDayOfMonth). Cannot schedule.")
                // It's possible the JSON doesn't have an entry for every single day, or month name mismatch
                return Result.success() // Or failure, depending on desired behavior
            }

            Log.d(TAG, "doWork: Found prayer times for today: $todayPrayerTimesEntity")

            val prayerDatesToSchedule = mutableListOf<Date>()
            val prayerNamesToSchedule = mutableListOf<String>() // For passing to AzanScheduler if it needs names
            val baseCalendarForToday = nowCalendar // This is already set to the worker's current day
            val timeFormatter = SimpleDateFormat("HH:mm", Locale.US) // Assuming times are "HH:mm" in DB

            for (prayerName in RELEVANT_PRAYER_NAMES_FOR_AZAN) {
                val prayerTimeString = when (prayerName) {
                    "Fajr" -> todayPrayerTimesEntity.fajr
                    "Dhuhr" -> todayPrayerTimesEntity.dhuhr
                    "Asr" -> todayPrayerTimesEntity.asr
                    "Maghrib" -> todayPrayerTimesEntity.maghrib
                    "Isha" -> todayPrayerTimesEntity.isha
                    else -> null
                }

                if (prayerTimeString != null) {
                    try {
                        val prayerTimeCalendar = Calendar.getInstance()
                        // Start with the date part of the worker's current day
                        prayerTimeCalendar.set(Calendar.YEAR, baseCalendarForToday.get(Calendar.YEAR))
                        prayerTimeCalendar.set(Calendar.MONTH, baseCalendarForToday.get(Calendar.MONTH))
                        prayerTimeCalendar.set(Calendar.DAY_OF_MONTH, baseCalendarForToday.get(Calendar.DAY_OF_MONTH))

                        // Parse the HH:mm string from the database
                        val parsedTime = timeFormatter.parse(prayerTimeString)
                        val parsedCalendar = Calendar.getInstance()
                        parsedCalendar.time = parsedTime

                        // Set hours and minutes on the prayerTimeCalendar
                        prayerTimeCalendar.set(Calendar.HOUR_OF_DAY, parsedCalendar.get(Calendar.HOUR_OF_DAY))
                        prayerTimeCalendar.set(Calendar.MINUTE, parsedCalendar.get(Calendar.MINUTE))
                        prayerTimeCalendar.set(Calendar.SECOND, 0)
                        prayerTimeCalendar.set(Calendar.MILLISECOND, 0)

                        if (isOffsetEnabled) { // Apply offset if enabled
                            prayerTimeCalendar.add(Calendar.HOUR_OF_DAY, 1) // Example: add 1 hour
                            Log.d(TAG, "doWork: Applied 1-hour offset for $prayerName")
                        }

                        val prayerDate = prayerTimeCalendar.time
                        val now = Date() // Current time when this code runs

                        // Only schedule if the prayer time is in the future
                        if (prayerDate.after(now)) {
                            prayerDatesToSchedule.add(prayerDate)
                            // Use the English name for logging, or Arabic name if AzanScheduler expects it
                            prayerNamesToSchedule.add(prayerName) // Or lookup PRAYER_NAMES_ARABIC[index]
                            Log.d(TAG, "doWork: Prepared to schedule $prayerName at $prayerDate (Offset applied: $isOffsetEnabled)")
                        } else {
                            Log.d(TAG, "doWork: $prayerName at $prayerDate is in the past (Offset applied: $isOffsetEnabled). Skipping.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "doWork: Error parsing or processing time for $prayerName ($prayerTimeString)", e)
                    }
                }
            }

            if (prayerDatesToSchedule.isNotEmpty()) {
                Log.i(TAG, "doWork: Scheduling ${prayerDatesToSchedule.size} Azans for today.")
                // Assuming AzanScheduler.scheduleDailyAzans takes List<Date> and List<String>
                azanScheduler.scheduleDailyAzans(prayerDatesToSchedule, prayerNamesToSchedule)
            } else {
                Log.i(TAG, "doWork: No Azans to schedule for today (either all past or errors).")
            }

            Log.i(TAG, "doWork: Worker FINISHED SUCCESSFULLY.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "doWork: CRITICAL ERROR in worker execution", e)
            return Result.failure()
        } finally {
            db?.close() // Ensure DB is closed
            Log.d(TAG, "doWork: Database closed in finally block.")
        }
    }
}