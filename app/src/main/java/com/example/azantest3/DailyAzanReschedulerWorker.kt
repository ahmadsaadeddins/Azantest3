package com.example.azantest3

import SettingsDataStore
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

class DailyAzanReschedulerWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DailyAzanWorker"
        val RELEVANT_PRAYER_NAMES_FOR_AZAN = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: Worker STARTED. Time to re-evaluate Azan schedules.")
        val db = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "prayers.db"
        ).build()

        try {
            val settingsDataStore = SettingsDataStore(appContext)
            val prayerRepository = PrayerRepository(db.prayerTimeDao(), settingsDataStore)
            val azanScheduler = AzanScheduler(appContext)

            Log.d(TAG, "doWork: Dependencies initialized.")

            val isAzanEnabled = settingsDataStore.azanEnabledFlow.first()
            val isOffsetEnabled = settingsDataStore.addHourOffsetFlow.first()
            Log.d(TAG, "doWork: Settings loaded - AzanEnabled: $isAzanEnabled, OffsetEnabled: $isOffsetEnabled")

            if (!isAzanEnabled) {
                Log.i(TAG, "doWork: Azan is NOT enabled. Cancelling all scheduled Azans.")
                azanScheduler.cancelAllScheduledAzans()
                return Result.success()
            }

            val nowCalendar = Calendar.getInstance()
            val currentDayOfMonth = nowCalendar.get(Calendar.DAY_OF_MONTH)
            val monthFormatterForQuery = SimpleDateFormat("MMM", Locale.ENGLISH)
            val currentMonthNameForQuery = monthFormatterForQuery.format(nowCalendar.time)
            Log.d(TAG, "doWork: Fetching prayers for Month: '$currentMonthNameForQuery', Day: $currentDayOfMonth")

            val allPrayerTimeEntities = prayerRepository.getTodayAndTomorrowPrayersCached()

            Log.d(TAG, "doWork: Fetched ${allPrayerTimeEntities.size} prayer entities from DB.")

            if (allPrayerTimeEntities.isEmpty()) {
                Log.i(TAG, "doWork: No prayer times found in the database. Cancelling all Azans.")
                azanScheduler.cancelAllScheduledAzans()
                db.close()
                return Result.success()
            }

            azanScheduler.cancelAllScheduledAzans()
            Log.d(TAG, "doWork: Azan enabled. Proceeding to reschedule for the current day.")

            val todayPrayerTimesEntity = allPrayerTimeEntities.find { pt ->
                pt.month_name.equals(currentMonthNameForQuery, ignoreCase = true) && pt.day == currentDayOfMonth
            }

            if (todayPrayerTimesEntity == null) {
                Log.w(TAG, "doWork: No prayer times entity found for today ($currentDayOfMonth). Cannot schedule.")
                return Result.success()
            }

            val prayerDatesToSchedule = mutableListOf<Date>()
            val prayerNamesToSchedule = mutableListOf<String>()
            val baseCalendarForToday = nowCalendar
            val timeFormatter = SimpleDateFormat("HH:mm", Locale.US)

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
                        val prayerTimeCalendar = Calendar.getInstance().apply {
                            set(Calendar.YEAR, baseCalendarForToday.get(Calendar.YEAR))
                            set(Calendar.MONTH, baseCalendarForToday.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, baseCalendarForToday.get(Calendar.DAY_OF_MONTH))
                        }

                        val parsedTime = timeFormatter.parse(prayerTimeString)
                        val parsedCalendar = Calendar.getInstance().apply { time = parsedTime }

                        prayerTimeCalendar.set(Calendar.HOUR_OF_DAY, parsedCalendar.get(Calendar.HOUR_OF_DAY))
                        prayerTimeCalendar.set(Calendar.MINUTE, parsedCalendar.get(Calendar.MINUTE))
                        prayerTimeCalendar.set(Calendar.SECOND, 0)
                        prayerTimeCalendar.set(Calendar.MILLISECOND, 0)

                        if (isOffsetEnabled) {
                            prayerTimeCalendar.add(Calendar.HOUR_OF_DAY, 1)
                            Log.d(TAG, "doWork: Applied 1-hour offset for $prayerName")
                        }

                        val prayerDate = prayerTimeCalendar.time
                        val now = Date()

                        if (prayerDate.after(now)) {
                            prayerDatesToSchedule.add(prayerDate)
                            prayerNamesToSchedule.add(prayerName)
                            Log.d(TAG, "doWork: Prepared to schedule $prayerName at $prayerDate")
                        } else {
                            Log.d(TAG, "doWork: $prayerName at $prayerDate is in the past. Skipping.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "doWork: Error parsing or processing time for $prayerName ($prayerTimeString)", e)
                    }
                }
            }

            if (prayerDatesToSchedule.isNotEmpty()) {
                Log.i(TAG, "doWork: Scheduling ${prayerDatesToSchedule.size} Azans for today.")
                azanScheduler.scheduleDailyAzans(prayerDatesToSchedule, prayerNamesToSchedule)
            } else {
                Log.i(TAG, "doWork: No Azans to schedule for today.")
            }

            Log.i(TAG, "doWork: Worker FINISHED SUCCESSFULLY.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "doWork: CRITICAL ERROR in worker execution", e)
            return Result.failure()
        } finally {
            db.close()
            Log.d(TAG, "doWork: Database closed in finally block.")
        }
    }
}
