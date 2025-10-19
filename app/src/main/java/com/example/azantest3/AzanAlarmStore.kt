package com.example.azantest3

import android.content.Context
import androidx.core.content.edit

object AzanAlarmStore {
    private const val PREF_NAME = "azan_alarm_prefs"

    fun saveAzan(context: Context, prayerName: String, prayerTime: Long, requestCode: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putLong("prayer_${prayerName}_time", prayerTime)
            putInt("prayer_${prayerName}_code", requestCode)
        }
    }

    fun getRequestCode(context: Context, prayerName: String): Int? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains("prayer_${prayerName}_code")) {
            prefs.getInt("prayer_${prayerName}_code", -1)
        } else null
    }

    fun clearAzan(context: Context, prayerName: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove("prayer_${prayerName}_code")
            remove("prayer_${prayerName}_time")
        }
    }

    fun getAllStoredPrayerNames(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.all.keys
            .filter { it.startsWith("prayer_") && it.endsWith("_code") }
            .map { it.removePrefix("prayer_").removeSuffix("_code") }
            .toSet()
    }
}
