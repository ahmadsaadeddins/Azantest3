package com.example.azantest3

import android.content.Context
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.util.Log
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.Locale

/**
 * Improved worker for rescheduling daily Azans with better time parsing,
 * timezone handling, and synchronization to prevent duplicates and wrong timings.
 */
class DailyAzanReschedulerWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DailyAzanWorker"
        val RELEVANT_PRAYER_NAMES_FOR_AZAN = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
        
        // Worker synchronization to prevent multiple instances
        private val workerMutex = Mutex()
        
        // Time parsing with proper locale and timezone handling
        private val TIME_FORMAT_24H = "HH:mm"
        private val DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss Z"
    }

    override suspend fun doWork(): Result = workerMutex.withLock {
        
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "🔄 DailyAzanReschedulerWorker STARTED at ${Date()}")
        Log.d(TAG, "Worker ID: ${id}, Run attempt: ${runAttemptCount}")
        
        var db: AppDatabase? = null
        
        try {
            // Initialize components with proper error handling
            db = Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                "prayers.db"
            ).build()

            val settingsDataStore = SettingsDataStore(appContext)
            val prayerRepository = PrayerRepository(db.prayerTimeDao(), settingsDataStore)
            val azanScheduler = AzanScheduler(appContext)

            Log.d(TAG, "Dependencies initialized successfully")

            // Load settings with validation
            val settings = loadAndValidateSettings(settingsDataStore)
            
            if (!settings.isAzanEnabled) {
                Log.i(TAG, "🔕 Azan is disabled. Cancelling all scheduled Azans and exiting.")
                azanScheduler.cancelAllScheduledAzans()
                return@withLock Result.success()
            }

            // Get current date/time with proper timezone handling
            val currentDateTime = getCurrentDateTime()
            Log.d(TAG, "Current date/time: ${formatDateTime(currentDateTime)}")

            // Fetch prayer times with validation
            val prayerEntities = prayerRepository.getTodayAndTomorrowPrayersCached()
            
            if (prayerEntities.isEmpty()) {
                Log.w(TAG, "⚠️ No prayer times found in database. Cancelling all Azans.")
                azanScheduler.cancelAllScheduledAzans()
                return@withLock Result.success()
            }

            Log.d(TAG, "Fetched ${prayerEntities.size} prayer entities from database")

            // Find today's prayer times
            val todayPrayerEntity = findTodayPrayerEntity(prayerEntities, currentDateTime)
            
            if (todayPrayerEntity == null) {
                Log.w(TAG, "⚠️ No prayer times found for today. Cannot schedule Azans.")
                azanScheduler.cancelAllScheduledAzans()
                return@withLock Result.success()
            }

            // Cancel existing alarms before scheduling new ones
            Log.d(TAG, "🧹 Cancelling existing scheduled Azans...")
            azanScheduler.cancelAllScheduledAzans()

            // Parse and schedule prayer times
            val schedulingResult = parseAndSchedulePrayerTimes(
                todayPrayerEntity, 
                currentDateTime, 
                settings, 
                azanScheduler
            )

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "✅ DailyAzanReschedulerWorker COMPLETED successfully in ${duration}ms")
            Log.i(TAG, "Scheduled ${schedulingResult.scheduledCount} out of ${schedulingResult.totalPrayers} prayers")

            return@withLock Result.success()

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "❌ CRITICAL ERROR in DailyAzanReschedulerWorker after ${duration}ms", e)
            return@withLock Result.failure()
            
        } finally {
            try {
                db?.close()
                Log.d(TAG, "Database connection closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing database", e)
            }
        }
    }

    /**
     * Data class for worker settings
     */
    private data class WorkerSettings(
        val isAzanEnabled: Boolean,
        val isOffsetEnabled: Boolean
    )

    /**
     * Data class for scheduling results
     */
    private data class SchedulingResult(
        val scheduledCount: Int,
        val totalPrayers: Int
    )

    /**
     * Loads and validates settings from DataStore
     */
    private suspend fun loadAndValidateSettings(settingsDataStore: SettingsDataStore): WorkerSettings {
        val isAzanEnabled = settingsDataStore.azanEnabledFlow.first()
        val isOffsetEnabled = settingsDataStore.addHourOffsetFlow.first()
        
        Log.d(TAG, "Settings loaded - AzanEnabled: $isAzanEnabled, OffsetEnabled: $isOffsetEnabled")
        
        return WorkerSettings(isAzanEnabled, isOffsetEnabled)
    }

    /**
     * Gets current date/time with proper timezone handling
     */
    private fun getCurrentDateTime(): Calendar {
        return Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
        }
    }

    /**
     * Formats date/time for logging
     */
    private fun formatDateTime(calendar: Calendar): String {
        val formatter = SimpleDateFormat(DATE_TIME_FORMAT, Locale.getDefault()).apply {
            timeZone = calendar.timeZone
        }
        return formatter.format(calendar.time)
    }

    /**
     * Finds today's prayer entity from the list
     */
    private fun findTodayPrayerEntity(
        prayerEntities: List<PrayerTime>, 
        currentDateTime: Calendar
    ): PrayerTime? {
        
        val currentDayOfMonth = currentDateTime.get(Calendar.DAY_OF_MONTH)
        val monthFormatter = SimpleDateFormat("MMM", Locale.ENGLISH).apply {
            timeZone = currentDateTime.timeZone
        }
        val currentMonthNameForQuery = monthFormatter.format(currentDateTime.time)
        
        Log.d(TAG, "Looking for prayers: Month='$currentMonthNameForQuery', Day=$currentDayOfMonth")

        val todayPrayerEntity = prayerEntities.find { prayerTime ->
            prayerTime.month_name.equals(currentMonthNameForQuery, ignoreCase = true) && 
            prayerTime.day == currentDayOfMonth
        }
        
        if (todayPrayerEntity != null) {
            Log.d(TAG, "Found today's prayer entity: ${todayPrayerEntity.month_name} ${todayPrayerEntity.day}")
        } else {
            Log.w(TAG, "No prayer entity found for today ($currentMonthNameForQuery $currentDayOfMonth)")
            prayerEntities.forEach { entity ->
                Log.d(TAG, "Available: ${entity.month_name} ${entity.day}")
            }
        }
        
        return todayPrayerEntity
    }

    /**
     * Parses prayer times and schedules Azans with improved error handling
     */
    private suspend fun parseAndSchedulePrayerTimes(
        prayerEntity: PrayerTime,
        currentDateTime: Calendar,
        settings: WorkerSettings,
        azanScheduler: AzanScheduler
    ): SchedulingResult {
        
        val prayerDatesToSchedule = mutableListOf<Date>()
        val prayerNamesToSchedule = mutableListOf<String>()
        
        // Use system locale for time parsing but fallback to English if needed
        val timeFormatter = SimpleDateFormat(TIME_FORMAT_24H, Locale.getDefault()).apply {
            timeZone = currentDateTime.timeZone
        }
        
        val fallbackTimeFormatter = SimpleDateFormat(TIME_FORMAT_24H, Locale.ENGLISH).apply {
            timeZone = currentDateTime.timeZone
        }

        var parsedCount = 0
        var scheduledCount = 0

        for (prayerName in RELEVANT_PRAYER_NAMES_FOR_AZAN) {
            try {
                val prayerTimeString = getPrayerTimeString(prayerEntity, prayerName)
                
                if (prayerTimeString.isNullOrBlank()) {
                    Log.w(TAG, "Empty prayer time for $prayerName")
                    continue
                }

                val prayerDate = parsePrayerTime(
                    prayerTimeString, 
                    currentDateTime, 
                    timeFormatter, 
                    fallbackTimeFormatter,
                    settings.isOffsetEnabled
                )

                if (prayerDate != null) {
                    parsedCount++
                    
                    val now = Date()
                    if (prayerDate.after(now)) {
                        prayerDatesToSchedule.add(prayerDate)
                        prayerNamesToSchedule.add(prayerName)
                        scheduledCount++
                        
                        Log.d(TAG, "✅ Prepared to schedule $prayerName at ${formatDateTime(Calendar.getInstance().apply { time = prayerDate })}")
                    } else {
                        Log.d(TAG, "⏰ $prayerName at ${formatDateTime(Calendar.getInstance().apply { time = prayerDate })} is in the past. Skipping.")
                    }
                } else {
                    Log.e(TAG, "❌ Failed to parse time for $prayerName: '$prayerTimeString'")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing $prayerName", e)
            }
        }

        Log.i(TAG, "Prayer parsing results: $parsedCount parsed, $scheduledCount future prayers")

        // Schedule all prepared Azans
        if (prayerDatesToSchedule.isNotEmpty()) {
            Log.i(TAG, "🔔 Scheduling ${prayerDatesToSchedule.size} Azans for today")
            azanScheduler.scheduleDailyAzans(prayerDatesToSchedule, prayerNamesToSchedule)
        } else {
            Log.i(TAG, "😴 No future prayers to schedule for today")
        }

        return SchedulingResult(scheduledCount, RELEVANT_PRAYER_NAMES_FOR_AZAN.size)
    }

    /**
     * Gets prayer time string from entity
     */
    private fun getPrayerTimeString(prayerEntity: PrayerTime, prayerName: String): String? {
        return when (prayerName) {
            "Fajr" -> prayerEntity.fajr
            "Dhuhr" -> prayerEntity.dhuhr
            "Asr" -> prayerEntity.asr
            "Maghrib" -> prayerEntity.maghrib
            "Isha" -> prayerEntity.isha
            else -> null
        }
    }

    /**
     * Parses prayer time string with multiple fallback strategies
     */
    private fun parsePrayerTime(
        prayerTimeString: String,
        baseDateTime: Calendar,
        primaryFormatter: SimpleDateFormat,
        fallbackFormatter: SimpleDateFormat,
        applyOffset: Boolean
    ): Date? {
        
        val cleanTimeString = prayerTimeString.trim()
        
        // Try primary formatter first
        var parsedTime = tryParseTime(cleanTimeString, primaryFormatter)
        
        // Try fallback formatter if primary fails
        if (parsedTime == null) {
            parsedTime = tryParseTime(cleanTimeString, fallbackFormatter)
        }
        
        if (parsedTime == null) {
            Log.e(TAG, "All parsing attempts failed for: '$cleanTimeString'")
            return null
        }
        
        // Create calendar for the prayer time on the current date
        val prayerTimeCalendar = Calendar.getInstance().apply {
            timeZone = baseDateTime.timeZone
            // Set to current date
            set(Calendar.YEAR, baseDateTime.get(Calendar.YEAR))
            set(Calendar.MONTH, baseDateTime.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, baseDateTime.get(Calendar.DAY_OF_MONTH))
            
            // Set time from parsed time
            val parsedCalendar = Calendar.getInstance().apply {
                time = parsedTime
                timeZone = this@apply.timeZone
            }
            
            set(Calendar.HOUR_OF_DAY, parsedCalendar.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, parsedCalendar.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // Apply offset if enabled
        if (applyOffset) {
            prayerTimeCalendar.add(Calendar.HOUR_OF_DAY, 1)
            Log.d(TAG, "Applied 1-hour offset to $cleanTimeString")
        }
        
        return prayerTimeCalendar.time
    }

    /**
     * Attempts to parse time with a specific formatter
     */
    private fun tryParseTime(timeString: String, formatter: SimpleDateFormat): Date? {
        return try {
            formatter.parse(timeString)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse '$timeString' with formatter ${formatter.toPattern()}: ${e.message}")
            null
        }
    }
}