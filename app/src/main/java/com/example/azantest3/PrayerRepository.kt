package com.example.azantest3

import SettingsDataStore
import android.app.Application
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.util.Log
import com.example.azantest3.datastore.PRAYER_NAMES
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import java.io.InputStreamReader
import java.util.Date
import java.util.Locale



class PrayerRepository(private val dao: PrayerTimeDao) {
    suspend fun getAllPrayers(): List<PrayerTime> = dao.getAll()
    suspend fun getPrayerForDate(month: String, day: Int): PrayerTime? = dao.getByDate(month, day)
    suspend fun getTwoDaysPrayers(month: String, day1: Int, day2: Int): List<PrayerTime> = dao.getTwoDays(month, day1, day2)
    data class NextPrayerInfo(val name: String, val time: Date, val prayerTimeEntity: PrayerTime)

    suspend fun seedDatabase(application: Application) {
        Log.d("PrayerRepository", "Seeding database...")
        val inputStream = application.assets.open("mozn.json")
        val reader = InputStreamReader(inputStream)
        val type = object : TypeToken<List<PrayerTime>>() {}.type
        val prayers: List<PrayerTime> = Gson().fromJson(reader, type)
        dao.insertAll(prayers)
    }

    // You already have:
// @Query("SELECT * FROM prayer_times WHERE month_name = :month AND day = :day LIMIT 1")
// suspend fun getByDate(month: String, day: Int): PrayerTime?

    // In your Repository or ViewModel:
    suspend fun getTodayAndTomorrowPrayers(todayMonth: String, todayDayNum: Int): List<PrayerTime> {
        val results = mutableListOf<PrayerTime>()
        Log.d("RepoWidget", "todayMonth ==============================: $todayMonth")

        var monthIndex = mapMonthNameToIndexMultilingual(todayMonth.lowercase()) // e.g., "January" -> 0
        val todayPrayer = dao.getByDate(todayMonth, todayDayNum)
        todayPrayer?.let { results.add(it) }

        // Calculate tomorrow's date (this requires careful date math)
        val calendar = Calendar.getInstance()
        // You'd need to convert todayMonth (e.g., "January") to a Calendar month index (0 for Jan)
        // For demonstration, let's assume you have a way to set the calendar:
        // setCalendarToDate(calendar, todayMonth, todayDayNum) // Your helper function

        // Example: (Highly simplified - use a proper date library or thorough Calendar logic)
        var currentYear = Calendar.getInstance().get(Calendar.YEAR) // Assuming current year


        calendar.set(currentYear, monthIndex, todayDayNum)
        calendar.add(Calendar.DAY_OF_MONTH, 1) // Add one day

        val tomorrowDayNum = calendar.get(Calendar.DAY_OF_MONTH)
        val tomorrowMonthIndex = calendar.get(Calendar.MONTH)

        val tomorrowMonthName = mapIndexToMonthNameAbrrv(tomorrowMonthIndex) // e.g., 0 -> "January"

        val tomorrowPrayer = dao.getByDate(tomorrowMonthName, tomorrowDayNum)
        tomorrowPrayer?.let { results.add(it) }

        return results
    }

    suspend fun getNextUpcomingPrayerForWidget(
        currentTime: Calendar,
        settingsDataStore: SettingsDataStore // Pass this if addHourOffset is needed here
    ): NextPrayerInfo? {
        val currentMonthName = mapIndexToMonthNameAbrrv(currentTime.get(Calendar.MONTH))
        Log.d("RepoWidget", "currentMonthName ==============================: $currentMonthName")

        val currentDay = currentTime.get(Calendar.DAY_OF_MONTH)
        val currentYear = currentTime.get(Calendar.YEAR)
        val addHour = settingsDataStore.addHourOffsetFlow.first()
        Log.d("RepoWidget", "addHourOffset from DataStore: $addHour")
        // Consider addHourOffset from settingsDataStore if applicable to raw times from DB
        // For simplicity, let's assume raw times are what we need for now,
        // or that addHourOffset is handled when converting string times to Date objects.
        // val addHour = settingsDataStore.addHourOffsetFlow.first() // Example

        // --- Function to parse string times from PrayerTime entity into Date objects ---
        fun prayerEntityToNamedDateList(prayerEntity: PrayerTime, dayCalendar: Calendar,shouldAddHour: Boolean): List<Pair<String, Date>> {
            val prayerTimesMap = mapOf(
                PRAYER_NAMES[0] to prayerEntity.fajr,   // Fajr
                PRAYER_NAMES[1] to prayerEntity.sunrise, // Sunrise (might want to exclude from "next prayer")
                PRAYER_NAMES[2] to prayerEntity.dhuhr,  // Dhuhr
                PRAYER_NAMES[3] to prayerEntity.asr,    // Asr
                PRAYER_NAMES[4] to prayerEntity.maghrib,// Maghrib
                PRAYER_NAMES[5] to prayerEntity.isha    // Isha
            )

            val sdf = SimpleDateFormat("HH:mm", Locale.US)
            val result = mutableListOf<Pair<String, Date>>()

            prayerTimesMap.forEach { (name, timeStr) ->
                try {
                    val parsedTime = sdf.parse(timeStr)
                    if (parsedTime != null) {
                        val prayerCal = dayCalendar.clone() as Calendar // Use a clone for each prayer time
                        val tempCal = Calendar.getInstance().apply { time = parsedTime }
                        prayerCal.set(Calendar.HOUR_OF_DAY, tempCal.get(Calendar.HOUR_OF_DAY))
                        prayerCal.set(Calendar.MINUTE, tempCal.get(Calendar.MINUTE))
                        prayerCal.set(Calendar.SECOND, 0)
                        prayerCal.set(Calendar.MILLISECOND, 0)

                        if (addHour) prayerCal.add(Calendar.HOUR_OF_DAY, 1)

                        result.add(Pair(name, prayerCal.time))
                    }
                } catch (e: Exception) {
                    Log.e("RepoWidget", "Error parsing time $timeStr for $name: ${e.message}")
                }
            }
            return result.sortedBy { it.second.time } // Sort by time
        }

        // 1. Check Today
        val todayPrayerEntity = dao.getByDate(currentMonthName, currentDay)
        if (todayPrayerEntity != null) {
            val todayCalendar = Calendar.getInstance().apply {
                set(currentYear, currentTime.get(Calendar.MONTH), currentDay)
            }
            val namedTimesToday = prayerEntityToNamedDateList(todayPrayerEntity, todayCalendar, addHour)
            for ((name, time) in namedTimesToday) {
                if (time.after(currentTime.time)) {
                    return NextPrayerInfo(name, time, todayPrayerEntity)
                }
            }
        } else {
            Log.w("RepoWidget", "No prayer entity found for today: $currentMonthName, $currentDay")
            // Consider if seeding should be triggered or if this is an error state
        }

        // 2. If no upcoming prayer today, check Tomorrow's Fajr
        val tomorrowCalendar = Calendar.getInstance().apply { time = currentTime.time; add(Calendar.DAY_OF_MONTH, 1) }
        val tomorrowMonthName = mapIndexToMonthNameAbrrv(tomorrowCalendar.get(Calendar.MONTH))
        val tomorrowDay = tomorrowCalendar.get(Calendar.DAY_OF_MONTH)

        val tomorrowPrayerEntity = dao.getByDate(tomorrowMonthName, tomorrowDay)
        if (tomorrowPrayerEntity != null) {
            val actualTomorrowCalendar = Calendar.getInstance().apply {
                set(tomorrowCalendar.get(Calendar.YEAR), tomorrowCalendar.get(Calendar.MONTH), tomorrowDay)
            }
            val namedTimesTomorrow = prayerEntityToNamedDateList(tomorrowPrayerEntity, actualTomorrowCalendar, addHour)
            if (namedTimesTomorrow.isNotEmpty()) {
                // Assuming Fajr is the first prayer after sorting if not explicitly named PRAYER_NAMES[0]
                val fajrTomorrow = namedTimesTomorrow.firstOrNull { it.first == PRAYER_NAMES[0] }
                    ?: namedTimesTomorrow.firstOrNull() // Fallback to first if "Fajr" string isn't exact match

                fajrTomorrow?.let {
                    return NextPrayerInfo(it.first, it.second, tomorrowPrayerEntity)
                }
            }
        } else {
            Log.w("RepoWidget", "No prayer entity found for tomorrow: $tomorrowMonthName, $tomorrowDay")
        }

        return null // No upcoming prayer found
    }
    // Helper function examples (you need to implement these based on your month name format)

}

