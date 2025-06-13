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
import java.util.concurrent.ConcurrentLinkedQueue

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
            action = AzanAlarmReceiver.ACTION_PLAY_AZAN
            putExtra(AzanAlarmReceiver.PRAYER_NAME_EXTRA, prayerName)
        }

        val requestCode = generateRequestCode(prayerTime, prayerIndex)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w("AzanScheduler", "Exact alarm permission not granted.")
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    prayerTime.time,
                    60_000L, // 1 minute window
                    pendingIntent
                )
            } else {
                Log.d("AzanScheduler", "Setting exact alarm for $prayerName at ${prayerTime.time}")
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    prayerTime.time,
                    pendingIntent
                )
            }

            // ✅ Save the requestCode and prayerTime for cancellation later
            AzanAlarmStore.saveAzan(context, prayerName, prayerTime.time, requestCode)

            Log.i("AzanScheduler", "Scheduled Azan for $prayerName ($prayerIndex) at $prayerTime")
        } catch (se: SecurityException) {
            Log.e("AzanScheduler", "SecurityException: Cannot schedule exact alarm.", se)
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
        val requestCode = generateRequestCodeForPrayer(PRAYER_NAMES[prayerIndex])
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
        Log.d("AzanScheduler", "🧹 Cancelling ALL scheduled Azans...")

        val storedPrayerNames = AzanAlarmStore.getAllStoredPrayerNames(context)
        if (storedPrayerNames.isEmpty()) {
            Log.d("AzanScheduler", "No Azans found in storage to cancel.")
        } else {
            storedPrayerNames.forEach { prayerName ->
                val requestCode = AzanAlarmStore.getRequestCode(context, prayerName)
                if (requestCode != null) {
                    val intent = Intent(context, AzanAlarmReceiver::class.java).apply {
                        action = AzanAlarmReceiver.ACTION_PLAY_AZAN
                        putExtra(AzanAlarmReceiver.PRAYER_NAME_EXTRA, prayerName)
                    }

                    val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_NO_CREATE
                    }

                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        pendingIntentFlags
                    )

                    if (pendingIntent != null) {
                        alarmManager.cancel(pendingIntent)
                        pendingIntent.cancel()
                        AzanAlarmStore.clearAzan(context, prayerName)
                        Log.i("AzanScheduler", "✅ Cancelled Azan for $prayerName (requestCode=$requestCode)")
                    } else {
                        Log.w("AzanScheduler", "⚠️ No PendingIntent found for $prayerName (requestCode=$requestCode)")
                    }
                } else {
                    Log.w("AzanScheduler", "⚠️ No stored requestCode for $prayerName")
                }
            }
        }

        // 🔕 Stop any running Azan playback
        val stopIntent = Intent(context, AzanPlaybackService::class.java).apply {
            action = AzanPlaybackService.ACTION_STOP_AZAN_SERVICE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(stopIntent)
            } else {
                context.startService(stopIntent)
            }
            Log.d("AzanScheduler", "🔕 Sent stop signal to AzanPlaybackService.")
        } catch (e: Exception) {
            Log.e("AzanScheduler", "❌ Failed to stop AzanPlaybackService", e)
        }

        Log.d("AzanScheduler", "✅ Finished cancelling all scheduled Azans.")
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
    private fun generateRequestCode1(prayerName: String, date: Date): Int {
        val cal = Calendar.getInstance()
        cal.time = date
        val dayCode = "${cal.get(Calendar.YEAR)}${String.format("%02d", cal.get(Calendar.MONTH))}${String.format("%02d", cal.get(Calendar.DAY_OF_MONTH))}"
        val hash = prayerName.hashCode().mod(1000)
        return (dayCode + hash).toIntOrNull() ?: (dayCode.hashCode() + hash)
    }
}