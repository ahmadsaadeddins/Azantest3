// AzanScheduler.kt
package com.example.azantest3 // Use your app's package name

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.azantest3.datastore.PRAYER_NAMES
import java.util.Calendar
import java.util.Date

class AzanScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val SUNRISE_PRAYER_NAME_ARABIC = "الشروق"

    fun scheduleAzanForPrayer(prayerTime: Date, prayerName: String, prayerIndex: Int) {
        if (prayerTime.before(Date())) {
            Log.d("AzanScheduler", "Skipping past prayer: $prayerName at $prayerTime")
            return // Don't schedule for past times
        }
        if (prayerName.equals(SUNRISE_PRAYER_NAME_ARABIC, ignoreCase = true)) {
            Log.i("AzanScheduler", "Skipping Azan scheduling for $prayerName as it's Sunrise.")
            return // Do not schedule Azan for Sunrise
        }
        val intent = Intent(context, AzanAlarmReceiver::class.java).apply {
            action = AzanAlarmReceiver.ACTION_PLAY_AZAN // Define a unique action
            putExtra(AzanAlarmReceiver.PRAYER_NAME_EXTRA, prayerName)
        }

        // Use prayerIndex to ensure unique PendingIntent request codes for each prayer
        // This is crucial if you want to cancel/update individual alarms.
        // A common way is to use the prayer time's timestamp or a combination of date and index.
        val requestCode = generateRequestCode(prayerTime, prayerIndex)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w("AzanScheduler", "Cannot schedule exact alarms. App needs SCHEDULE_EXACT_ALARM permission and user grant.")
                // Optionally, guide user to settings:
                // context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                // Or use a less exact alarm (not suitable for Azan)
                // Or notify the user that exact alarms are not permitted.
                Log.d("AzanScheduler", "Setting alarm (inexact if permission denied) for $prayerName at ${prayerTime.time}")
                // Fallback for devices that can't schedule exact alarms or permission denied (might be less precise)
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, prayerTime.time, 60000, pendingIntent)

            } else {
                Log.d("AzanScheduler", "Setting exact alarm for $prayerName at ${prayerTime.time}")
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    prayerTime.time,
                    pendingIntent
                )
            }
            Log.i("AzanScheduler", "Scheduled Azan for $prayerName ($prayerIndex) at $prayerTime")
        } catch (se: SecurityException) {
            Log.e("AzanScheduler", "SecurityException: Cannot schedule exact alarm. Check permissions.", se)
            // Handle the case where the permission might be revoked or not granted
        } catch (e: Exception) {
            Log.e("AzanScheduler", "Error scheduling alarm for $prayerName", e)
        }
    }

    fun scheduleDailyAzans(prayerTimesToday: List<Date>, prayerNames: List<String>) {
        if (prayerTimesToday.size != prayerNames.size) {
            Log.e("AzanScheduler", "Mismatch between prayer times and names count.")
            return
        }
        Log.d("AzanScheduler", "Scheduling daily Azans for ${prayerTimesToday.size} prayers.")
        prayerTimesToday.forEachIndexed { index, time ->
            scheduleAzanForPrayer(time, prayerNames[index], index)
        }
    }

    fun cancelAzanForPrayer(prayerTime: Date, prayerIndex: Int) {
        val intent = Intent(context, AzanAlarmReceiver::class.java).apply {
            action = AzanAlarmReceiver.ACTION_PLAY_AZAN
        }
        val requestCode = generateRequestCode(prayerTime, prayerIndex)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE // Important: FLAG_NO_CREATE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel() // Also cancel the PendingIntent itself
            Log.i("AzanScheduler", "Cancelled Azan for prayer index $prayerIndex at $prayerTime")
        } else {
            Log.w("AzanScheduler", "No alarm found to cancel for prayer index $prayerIndex at $prayerTime")
        }
    }


    fun cancelAllAzans(prayerTimesToday: List<Date>) {
        Log.d("AzanScheduler", "Cancelling all Azans.")
        prayerTimesToday.forEachIndexed { index, time ->
            cancelAzanForPrayer(time, index)
        }
    }

    /**
     * Attempts to cancel all potentially scheduled Azans.
     * This works by trying to cancel alarms for each known prayer name,
     * including a potential "Tomorrow's Fajr".
     */
    fun cancelAllScheduledAzans() {
        Log.d("AzanScheduler", "Attempting to cancel ALL scheduled Azans...")
        val prayerNamesToCancel = PRAYER_NAMES.toMutableList()
        // Add "Tomorrow's Fajr" as it's a special case often scheduled
        // The actual name passed to putExtra was "Fajr" but the intent might be distinct if not handled carefully
        // However, if generateRequestCodeForPrayer("Fajr") is consistent, it will cancel the correct Fajr.
        // If "Tomorrow's Fajr" uses a *different* prayerName string like "Fajr (Tomorrow)" in the intent,
        // you'd need to add that specific string here.
        // Assuming your scheduleRelevantAzans uses "Fajr" for tomorrow's Fajr but with a future date.

        // It's generally safer to rely on the prayer names used when scheduling.
        // If your PrayerViewModel schedules tomorrow's Fajr by passing "Fajr" as prayerName,
        // then iterating through PRAYER_NAMES is sufficient.

        prayerNamesToCancel.forEach { prayerName ->
            // Use the same logic as cancelAzan
            val requestCode = generateRequestCodeForPrayer(prayerName)
            val intent = Intent(context, AzanAlarmReceiver::class.java).apply {
                action = AzanAlarmReceiver.ACTION_PLAY_AZAN
                putExtra(AzanAlarmReceiver.PRAYER_NAME_EXTRA, prayerName) // Must match what was used to schedule
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }

            val existingPendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                pendingIntentFlags
            )

            if (existingPendingIntent != null) {
                alarmManager.cancel(existingPendingIntent)
                existingPendingIntent.cancel()
                Log.d("AzanScheduler", "CANCELLED (in All): $prayerName (Request Code: $requestCode)")
            } else {
                Log.d("AzanScheduler", "No active Azan to cancel for $prayerName during 'cancelAll'.")
            }
        }
        Log.d("AzanScheduler", "Finished attempt to cancel all scheduled Azans.")
    }



    /**
     * Generates a unique integer request code based on the prayer name.
     * This is crucial for PendingIntents to be distinct for different prayers.
     */
    private fun generateRequestCodeForPrayer(prayerName: String): Int {
        // Simple hash-based request code. Ensure it's reasonably unique for your prayer names.
        // Adding a prefix to avoid collision with other potential request codes in your app.
        val baseRequestCode = 7000
        return baseRequestCode + prayerName.hashCode().mod(1000) // Keep it within a reasonable range
    }

    // Generates a unique request code for each alarm.
    // This is important for being able to update or cancel specific alarms.
    // Using just prayerIndex might lead to collisions if you reschedule for the same prayer name
    // across different days without careful management. A timestamp-based or date-combined
    // code is more robust.
    private fun generateRequestCode(date: Date, prayerIndex: Int): Int {
        val cal = Calendar.getInstance()
        cal.time = date
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        // A simple combination. Ensure it's unique enough for your needs.
        // E.g., YYYYMMDDPI where PI is prayer index
        return "${year}${String.format("%02d", month)}${String.format("%02d", day)}${prayerIndex}".toIntOrNull() ?: date.hashCode() + prayerIndex
    }
}