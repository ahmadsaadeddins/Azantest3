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

            // 6. Fetch all prayer times from the repository (similar to how ViewModel might load them initially)
            // In PrayerViewModel, it's `repository.getAllPrayers()`. Assuming this is a suspend function.
            val allPrayerTimeEntities: List<PrayerTime> = prayerRepository.getAllPrayers()
            Log.d(TAG, "doWork: Fetched ${allPrayerTimeEntities.size} prayer entities from DB.")


            if (allPrayerTimeEntities.isEmpty()) {
                Log.i(TAG, "doWork: No prayer times found in the database. Cancelling all Azans.")
                azanScheduler.cancelAllScheduledAzans()
                return Result.success()
            }

            Log.d(TAG, "doWork: Azan enabled. Proceeding to reschedule for the current day.")

            // --- Core Rescheduling Logic ---
            azanScheduler.cancelAllScheduledAzans() // Clear any old alarms first

            // Determine "today" for the worker (the day it's running for)
            val workerCurrentDayCalendar = Calendar.getInstance() // This is the "current day" for the worker

            // Find the PrayerTime entity for the worker's current day
            // This logic needs to correctly map the workerCurrentDayCalendar to your PrayerTime entity's date components
//            val currentMonthName = SimpleDateFormat("MMMM", Locale.ENGLISH).format(workerCurrentDayCalendar.time)
//            val currentDayOfMonth = workerCurrentDayCalendar.get(Calendar.DAY_OF_MONTH)
//            val todayPrayerTimesEntity = allPrayerTimeEntities.find { pt ->
//                pt.month_name.equals(currentMonthName, ignoreCase = true) && pt.day == currentDayOfMonth
//            }
            // Inside DailyAzanReschedulerWorker.kt, before the .find block:

            Log.d(TAG, "doWork: Total prayer entities fetched: ${allPrayerTimeEntities.size}")
            if (allPrayerTimeEntities.isNotEmpty()) {
                Log.d(TAG, "doWork: First entity month: ${allPrayerTimeEntities.first().month_name}, day: ${allPrayerTimeEntities.first().day}")
                Log.d(TAG, "doWork: Last entity month: ${allPrayerTimeEntities.last().month_name}, day: ${allPrayerTimeEntities.last().day}")
            }

            val juneEntries = allPrayerTimeEntities.filter { it.month_name.equals("June", ignoreCase = true) }
            if (juneEntries.isEmpty()) {
                Log.w(TAG, "doWork: No entities found for month 'June' in the database.")
            } else {
                Log.d(TAG, "doWork: Found ${juneEntries.size} entities for 'June'. Days found: ${juneEntries.map { it.day }.joinToString()}")
            }

            // ... then the find block ...
            val currentMonthName = SimpleDateFormat("MMM", Locale.ENGLISH).format(workerCurrentDayCalendar.time)
            val currentDayOfMonth = workerCurrentDayCalendar.get(Calendar.DAY_OF_MONTH)
            Log.d(TAG, "doWork: Trying to find match for Month: '$currentMonthName', Day: $currentDayOfMonth")

            val todayPrayerTimesEntity = allPrayerTimeEntities.find { pt ->
                val monthMatches = pt.month_name.equals(currentMonthName, ignoreCase = true)
                val dayMatches = pt.day == currentDayOfMonth
                if (!monthMatches && currentMonthName == "June") { // Specific log for June mismatch
                    Log.d(TAG, "Debug find: Entity month '${pt.month_name}' (day ${pt.day}) does not match target month '$currentMonthName'")
                }
                monthMatches && dayMatches
            }


            if (todayPrayerTimesEntity == null) {
                Log.w(TAG, "doWork: No prayer times entity found for today ($currentMonthName, $currentDayOfMonth). Cannot schedule.")
                // It's possible the JSON doesn't have an entry for every single day, or month name mismatch
                return Result.success() // Or failure, depending on desired behavior
            }

            Log.d(TAG, "doWork: Found prayer times for today: $todayPrayerTimesEntity")

            val prayerDatesToSchedule = mutableListOf<Date>()
            val prayerNamesToSchedule = mutableListOf<String>() // For passing to AzanScheduler if it needs names

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
                        prayerTimeCalendar.set(Calendar.YEAR, workerCurrentDayCalendar.get(Calendar.YEAR))
                        prayerTimeCalendar.set(Calendar.MONTH, workerCurrentDayCalendar.get(Calendar.MONTH))
                        prayerTimeCalendar.set(Calendar.DAY_OF_MONTH, workerCurrentDayCalendar.get(Calendar.DAY_OF_MONTH))

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