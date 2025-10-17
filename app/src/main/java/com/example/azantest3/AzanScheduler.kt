package com.example.azantest3

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.azantest3.datastore.PRAYER_NAMES
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

class AzanScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val sunrisePrayerName = "الشروق"
    private val schedulingMutex = Mutex()
    private val requestCodeCounter = AtomicInteger(0)
    
    // Thread-safe date formatter
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    companion object {
        private const val TAG = "AzanScheduler"
        private const val BASE_REQUEST_CODE = 10000
        private const val MAX_REQUEST_CODE_RANGE = 50000
    }

    /**
     * Schedules Azan for a specific prayer with improved error handling and duplicate prevention
     */
    private suspend fun scheduleAzanForPrayer(
        prayerTime: Date, 
        prayerName: String, 
        prayerIndex: Int
    ) = schedulingMutex.withLock {
        
        // Validate input parameters
        if (prayerName.isBlank()) {
            Log.e(TAG, "Prayer name cannot be blank")
            return@withLock
        }

        val now = Date()
        if (prayerTime.before(now)) {
            Log.d(TAG, "Skipping past prayer: $prayerName at ${timeFormatter.format(prayerTime)}")
            return@withLock
        }

        if (prayerName.equals(sunrisePrayerName, ignoreCase = true)) {
            Log.i(TAG, "Skipping Azan scheduling for $prayerName as it's Sunrise.")
            return@withLock
        }

        // Check if this exact prayer is already scheduled
        val existingRequestCode = AzanAlarmStore.getRequestCode(context, prayerName)
        if (existingRequestCode != null) {
            Log.d(TAG, "Cancelling existing alarm for $prayerName before rescheduling")
            cancelSpecificAzan(prayerName, existingRequestCode)
        }

        val intent = Intent(context, AzanAlarmReceiver::class.java).apply {
            action = AzanAlarmReceiver.ACTION_PLAY_AZAN
            putExtra(AzanAlarmReceiver.PRAYER_NAME_EXTRA, prayerName)
            // Add unique identifier to prevent intent conflicts
            putExtra("SCHEDULE_TIME", System.currentTimeMillis())
        }

        val requestCode = generateUniqueRequestCode(prayerTime, prayerName, prayerIndex)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val scheduleSuccess = scheduleAlarmBasedOnApiLevel(prayerTime, pendingIntent, prayerName)
            
            if (scheduleSuccess) {
                // Save the alarm info for future cancellation
                AzanAlarmStore.saveAzan(context, prayerName, prayerTime.time, requestCode)
                Log.i(TAG, "✅ Successfully scheduled Azan for $prayerName at ${timeFormatter.format(prayerTime)} (RequestCode: $requestCode)")
            }

        } catch (se: SecurityException) {
            Log.e(TAG, "❌ SecurityException: Cannot schedule exact alarm for $prayerName", se)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling alarm for $prayerName", e)
        }
    }

    /**
     * Handles alarm scheduling based on Android API level and permissions
     */
    private fun scheduleAlarmBasedOnApiLevel(
        prayerTime: Date, 
        pendingIntent: PendingIntent, 
        prayerName: String
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        prayerTime.time,
                        pendingIntent
                    )
                    Log.d(TAG, "Set exact alarm for $prayerName (API 31+)")
                    true
                } else {
                    Log.w(TAG, "Exact alarm permission not granted for $prayerName. Using inexact alarm.")
                    alarmManager.setWindow(
                        AlarmManager.RTC_WAKEUP,
                        prayerTime.time,
                        60_000L, // 1 minute window
                        pendingIntent
                    )
                    true
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    prayerTime.time,
                    pendingIntent
                )
                Log.d(TAG, "Set exact alarm for $prayerName (API < 31)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm for $prayerName", e)
            false
        }
    }

    /**
     * Schedules daily Azans with improved validation and error handling
     */
    suspend fun scheduleDailyAzans(prayerTimesToday: List<Date>, prayerNames: List<String>) {
        if (prayerTimesToday.isEmpty() || prayerNames.isEmpty()) {
            Log.w(TAG, "Cannot schedule Azans: empty prayer times or names")
            return
        }

        if (prayerTimesToday.size != prayerNames.size) {
            Log.e(TAG, "❌ Mismatch between prayer times (${prayerTimesToday.size}) and names (${prayerNames.size})")
            return
        }

        Log.d(TAG, "🔄 Scheduling daily Azans for ${prayerTimesToday.size} prayers")
        
        var scheduledCount = 0
        prayerTimesToday.forEachIndexed { index, time ->
            try {
                scheduleAzanForPrayer(time, prayerNames[index], index)
                scheduledCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule Azan for ${prayerNames[index]}", e)
            }
        }
        
        Log.i(TAG, "✅ Successfully scheduled $scheduledCount out of ${prayerTimesToday.size} Azans")
    }

    /**
     * Cancels a specific Azan alarm
     */
    private fun cancelSpecificAzan(prayerName: String, requestCode: Int) {
        val intent = Intent(context, AzanAlarmReceiver::class.java).apply {
            action = AzanAlarmReceiver.ACTION_PLAY_AZAN
            putExtra(AzanAlarmReceiver.PRAYER_NAME_EXTRA, prayerName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "✅ Cancelled Azan for $prayerName (RequestCode: $requestCode)")
        } else {
            Log.w(TAG, "⚠️ No active alarm found for $prayerName (RequestCode: $requestCode)")
        }
    }

    /**
     * Cancels all scheduled Azans for specific prayer times
     */
    suspend fun cancelAllAzans(prayerTimesToday: List<Date>) = schedulingMutex.withLock {
        Log.d(TAG, "🧹 Cancelling Azans for ${prayerTimesToday.size} prayers")
        
        prayerTimesToday.forEachIndexed { index, time ->
            if (index < PRAYER_NAMES.size) {
                val prayerName = PRAYER_NAMES[index]
                val requestCode = AzanAlarmStore.getRequestCode(context, prayerName)
                if (requestCode != null) {
                    cancelSpecificAzan(prayerName, requestCode)
                    AzanAlarmStore.clearAzan(context, prayerName)
                }
            }
        }
    }

    /**
     * Comprehensive cancellation of all scheduled Azans
     */
    suspend fun cancelAllScheduledAzans() = schedulingMutex.withLock {
        Log.d(TAG, "🧹 Starting comprehensive cancellation of ALL scheduled Azans...")

        val storedPrayerNames = AzanAlarmStore.getAllStoredPrayerNames(context)
        var cancelledCount = 0

        if (storedPrayerNames.isEmpty()) {
            Log.d(TAG, "No stored Azans found to cancel")
        } else {
            storedPrayerNames.forEach { prayerName ->
                val requestCode = AzanAlarmStore.getRequestCode(context, prayerName)
                if (requestCode != null) {
                    try {
                        cancelSpecificAzan(prayerName, requestCode)
                        AzanAlarmStore.clearAzan(context, prayerName)
                        cancelledCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cancel Azan for $prayerName", e)
                    }
                }
            }
        }

        // Stop any currently playing Azan
        stopCurrentlyPlayingAzan()

        Log.i(TAG, "✅ Cancelled $cancelledCount Azans out of ${storedPrayerNames.size} stored")
    }

    /**
     * Stops any currently playing Azan service
     */
    private fun stopCurrentlyPlayingAzan() {
        val stopIntent = Intent(context, AzanPlaybackService::class.java).apply {
            action = AzanPlaybackService.ACTION_STOP_AZAN_SERVICE
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(stopIntent)
            } else {
                context.startService(stopIntent)
            }
            Log.d(TAG, "🔕 Sent stop signal to AzanPlaybackService")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop AzanPlaybackService", e)
        }
    }

    /**
     * Generates a truly unique request code using multiple factors
     */
    @SuppressLint("DefaultLocale")
    private fun generateUniqueRequestCode(date: Date, prayerName: String, prayerIndex: Int): Int {
        val calendar = Calendar.getInstance().apply { time = date }
        
        // Create a composite string for hashing
        val composite = StringBuilder().apply {
            append(calendar.get(Calendar.YEAR))
            append(String.format("%02d", calendar.get(Calendar.MONTH)))
            append(String.format("%02d", calendar.get(Calendar.DAY_OF_MONTH)))
            append(String.format("%02d", calendar.get(Calendar.HOUR_OF_DAY)))
            append(String.format("%02d", calendar.get(Calendar.MINUTE)))
            append(prayerName.hashCode())
            append(prayerIndex)
            append(requestCodeCounter.incrementAndGet())
        }.toString()

        // Generate a hash-based request code
        val hash = try {
            val md = MessageDigest.getInstance("MD5")
            val hashBytes = md.digest(composite.toByteArray())
            // Convert first 4 bytes to int
            ((hashBytes[0].toInt() and 0xFF) shl 24) or
            ((hashBytes[1].toInt() and 0xFF) shl 16) or
            ((hashBytes[2].toInt() and 0xFF) shl 8) or
            (hashBytes[3].toInt() and 0xFF)
        } catch (e: Exception) {
            composite.hashCode()
        }

        // Ensure positive and within reasonable range
        val requestCode = BASE_REQUEST_CODE + (Math.abs(hash) % MAX_REQUEST_CODE_RANGE)
        
        Log.d(TAG, "Generated unique request code: $requestCode for $prayerName")
        return requestCode
    }

    /**
     * Validates if the scheduler is in a healthy state
     */
    fun validateSchedulerHealth(): Boolean {
        return try {
            alarmManager != null && context != null
        } catch (e: Exception) {
            Log.e(TAG, "Scheduler health check failed", e)
            false
        }
    }
}