package com.example.azantest3

import SettingsDataStore
import android.app.Application
import android.content.Context
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

class PrayerRepository(private val dao: PrayerTimeDao, private val settingsDataStore: SettingsDataStore) {

    data class NextPrayerInfo(val name: String, val time: Date, val prayerTimeEntity: PrayerTime)

    suspend fun getAllPrayers(): List<PrayerTime> = dao.getAll()

    companion object {
        private var lastCachedKey: String? = null
        private var lastCachedPrayers: List<PrayerTime>? = null

        fun clearCacheFor(context: Context) {
            Log.d("PrayerRepository", "⛔ Cache manually invalidated due to time change.")
            lastCachedKey = null
            lastCachedPrayers = null
        }
    }
    suspend fun seedDatabase(application: Application) {
        Log.d("PrayerRepository", "Seeding database...")
        val inputStream = application.assets.open("mozn.json")
        val reader = InputStreamReader(inputStream)
        val type = object : TypeToken<List<PrayerTime>>() {}.type
        val prayers: List<PrayerTime> = Gson().fromJson(reader, type)
        dao.insertAll(prayers)
    }

    suspend fun getTodayAndTomorrowPrayersCached(): List<PrayerTime> {
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val (json, cachedDate) = settingsDataStore.getCachedPrayerTimes()

        return if (json != null && cachedDate == todayDate) {
            Log.d("PrayerRepository", "Using cached prayer times for $cachedDate")
            Gson().fromJson(json, object : TypeToken<List<PrayerTime>>() {}.type)
        } else {
            val calendar = Calendar.getInstance()
            val todayMonth = mapIndexToMonthNameAbrrv(calendar.get(Calendar.MONTH))
            val todayDayNum = calendar.get(Calendar.DAY_OF_MONTH)
            val prayers = getTodayAndTomorrowPrayers(todayMonth, todayDayNum)
            val newJson = Gson().toJson(prayers)
            settingsDataStore.cachePrayerTimes(newJson, todayDate)
            Log.d("PrayerRepository", "Fetched and cached new prayer times for $todayDate")
            prayers
        }
    }

    suspend fun getTodayAndTomorrowPrayers(todayMonth: String, todayDayNum: Int): List<PrayerTime> {
        val results = mutableListOf<PrayerTime>()
        Log.d("PrayerRepository", "Fetching prayers for $todayMonth $todayDayNum")

        val monthIndex = mapMonthNameToIndexMultilingual(todayMonth.lowercase())
        val todayPrayer = dao.getByDate(todayMonth, todayDayNum)
        todayPrayer?.let { results.add(it) }

        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        calendar.set(currentYear, monthIndex, todayDayNum)
        calendar.add(Calendar.DAY_OF_MONTH, 1)

        val tomorrowDayNum = calendar.get(Calendar.DAY_OF_MONTH)
        val tomorrowMonthName = mapIndexToMonthNameAbrrv(calendar.get(Calendar.MONTH))

        val tomorrowPrayer = dao.getByDate(tomorrowMonthName, tomorrowDayNum)
        tomorrowPrayer?.let { results.add(it) }

        return results
    }

    suspend fun getNextUpcomingPrayerForWidget(currentTime: Calendar): NextPrayerInfo? {
        val currentMonthName = mapIndexToMonthNameAbrrv(currentTime.get(Calendar.MONTH))
        val currentDay = currentTime.get(Calendar.DAY_OF_MONTH)
        val currentYear = currentTime.get(Calendar.YEAR)
        val addHour = settingsDataStore.addHourOffsetFlow.first()

        fun prayerEntityToNamedDateList(prayerEntity: PrayerTime, dayCalendar: Calendar, shouldAddHour: Boolean): List<Pair<String, Date>> {
            val prayerTimesMap = mapOf(
                PRAYER_NAMES[0] to prayerEntity.fajr,
                PRAYER_NAMES[1] to prayerEntity.sunrise,
                PRAYER_NAMES[2] to prayerEntity.dhuhr,
                PRAYER_NAMES[3] to prayerEntity.asr,
                PRAYER_NAMES[4] to prayerEntity.maghrib,
                PRAYER_NAMES[5] to prayerEntity.isha
            )

            val sdf = SimpleDateFormat("HH:mm", Locale.US)
            val result = mutableListOf<Pair<String, Date>>()

            prayerTimesMap.forEach { (name, timeStr) ->
                try {
                    val parsedTime = sdf.parse(timeStr)
                    parsedTime?.let {
                        val prayerCal = dayCalendar.clone() as Calendar
                        val tempCal = Calendar.getInstance().apply { time = it }
                        prayerCal.set(Calendar.HOUR_OF_DAY, tempCal.get(Calendar.HOUR_OF_DAY))
                        prayerCal.set(Calendar.MINUTE, tempCal.get(Calendar.MINUTE))
                        prayerCal.set(Calendar.SECOND, 0)
                        prayerCal.set(Calendar.MILLISECOND, 0)
                        if (shouldAddHour) prayerCal.add(Calendar.HOUR_OF_DAY, 1)
                        result.add(Pair(name, prayerCal.time))
                    }
                } catch (e: Exception) {
                    Log.e("PrayerRepository", "Error parsing time $timeStr for $name: ${e.message}")
                }
            }
            return result.sortedBy { it.second.time }
        }

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
        }

        val tomorrowCalendar = Calendar.getInstance().apply {
            time = currentTime.time
            add(Calendar.DAY_OF_MONTH, 1)
        }
        val tomorrowMonthName = mapIndexToMonthNameAbrrv(tomorrowCalendar.get(Calendar.MONTH))
        val tomorrowDay = tomorrowCalendar.get(Calendar.DAY_OF_MONTH)
        val tomorrowPrayerEntity = dao.getByDate(tomorrowMonthName, tomorrowDay)

        if (tomorrowPrayerEntity != null) {
            val actualTomorrowCalendar = Calendar.getInstance().apply {
                set(tomorrowCalendar.get(Calendar.YEAR), tomorrowCalendar.get(Calendar.MONTH), tomorrowDay)
            }
            val namedTimesTomorrow = prayerEntityToNamedDateList(tomorrowPrayerEntity, actualTomorrowCalendar, addHour)
            val fajrTomorrow = namedTimesTomorrow.firstOrNull { it.first == PRAYER_NAMES[0] }
                ?: namedTimesTomorrow.firstOrNull()
            fajrTomorrow?.let {
                return NextPrayerInfo(it.first, it.second, tomorrowPrayerEntity)
            }
        }

        return null
    }
}
