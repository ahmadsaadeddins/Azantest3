package com.example.azantest3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Improved Azan alarm receiver with duplicate prevention, better error handling,
 * and proper synchronization to prevent multiple simultaneous Azan playbacks.
 */
class AzanAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PLAY_AZAN = "com.example.azantest3.ACTION_PLAY_AZAN"
        const val PRAYER_NAME_EXTRA = "PRAYER_NAME_EXTRA"
        private const val TAG = "AzanAlarmReceiver"
        
        // Prevent duplicate alarms for the same prayer within a time window
        private val recentAlarms = ConcurrentHashMap<String, Long>()
        private const val DUPLICATE_PREVENTION_WINDOW_MS = 30_000L // 30 seconds
        
        // Mutex for thread-safe operations
        private val receiverMutex = Mutex()
        
        // Track currently playing Azans to prevent overlapping
        private val playingAzans = ConcurrentHashMap<String, Long>()
        
        /**
         * Cleanup method to remove old entries from tracking maps
         */
        fun cleanupOldEntries() {
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - (24 * 60 * 60 * 1000L) // 24 hours
            
            // Clean up old recent alarms
            val recentAlarmsToRemove = recentAlarms.entries
                .filter { it.value < cutoffTime }
                .map { it.key }
            
            recentAlarmsToRemove.forEach { prayerName ->
                recentAlarms.remove(prayerName)
            }
            
            // Clean up old playing azans (should be much shorter anyway)
            val playingAzansToRemove = playingAzans.entries
                .filter { (currentTime - it.value) > 600_000L } // 10 minutes
                .map { it.key }
            
            playingAzansToRemove.forEach { prayerName ->
                playingAzans.remove(prayerName)
            }
            
            if (recentAlarmsToRemove.isNotEmpty() || playingAzansToRemove.isNotEmpty()) {
                Log.d(TAG, "Cleaned up ${recentAlarmsToRemove.size} recent alarms and ${playingAzansToRemove.size} playing azans")
            }
        }
        
        /**
         * Gets current tracking statistics for debugging
         */
        fun getTrackingStats(): Map<String, Any> {
            return mapOf(
                "recentAlarmsCount" to recentAlarms.size,
                "playingAzansCount" to playingAzans.size,
                "recentAlarms" to recentAlarms.keys.toList(),
                "playingAzans" to playingAzans.keys.toList()
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prayerName = intent.getStringExtra(PRAYER_NAME_EXTRA)?.trim() ?: "Unknown Prayer"
        val currentTime = System.currentTimeMillis()
        val scheduleTime = intent.getLongExtra("SCHEDULE_TIME", 0L)
        
        Log.i(TAG, "🔔 Azan alarm received for: $prayerName at ${java.util.Date()}")
        Log.d(TAG, "Intent details - Action: ${intent.action}, ScheduleTime: ${if(scheduleTime > 0) java.util.Date(scheduleTime) else "Not set"}")

        // Use coroutine scope for async operations within the receiver
        val scope = CoroutineScope(Dispatchers.Main)
        
        scope.launch {
            try {
                handleAzanAlarm(context, prayerName, currentTime, scheduleTime)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Critical error handling Azan alarm for $prayerName", e)
            }
        }
    }

    /**
     * Handles the Azan alarm with comprehensive validation and duplicate prevention
     */
    private suspend fun handleAzanAlarm(
        context: Context, 
        prayerName: String, 
        currentTime: Long,
        scheduleTime: Long
    ) = receiverMutex.withLock {
        
        // Validate prayer name
        if (prayerName.isBlank() || prayerName == "Unknown Prayer") {
            Log.e(TAG, "❌ Invalid prayer name received: '$prayerName'")
            return@withLock
        }

        // Check if Azan is globally enabled
        val isAzanEnabled = try {
            AzanSettings.isAzanEnabledBlocking(context)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to check Azan settings for $prayerName", e)
            false
        }

        if (!isAzanEnabled) {
            Log.d(TAG, "🔕 Azan is globally disabled. Skipping $prayerName")
            return@withLock
        }

        // Check for duplicate alarms within the prevention window
        if (isDuplicateAlarm(prayerName, currentTime)) {
            Log.w(TAG, "⚠️ Duplicate alarm detected for $prayerName within ${DUPLICATE_PREVENTION_WINDOW_MS}ms. Ignoring.")
            return@withLock
        }

        // Check if this prayer is already playing
        if (isAzanCurrentlyPlaying(prayerName, currentTime)) {
            Log.w(TAG, "⚠️ Azan for $prayerName is already playing. Ignoring duplicate.")
            return@withLock
        }

        // Validate timing (optional - helps debug scheduling issues)
        if (scheduleTime > 0) {
            val timeDifference = Math.abs(currentTime - scheduleTime)
            if (timeDifference > 300_000L) { // 5 minutes
                Log.w(TAG, "⚠️ Significant time difference for $prayerName: ${timeDifference / 1000}s")
            }
        }

        // Mark this alarm as recent to prevent duplicates
        recentAlarms[prayerName] = currentTime
        playingAzans[prayerName] = currentTime

        // Acquire wake lock for reliable service startup
        val wakeLockResult = acquireWakeLock(context, prayerName)
        
        try {
            // Start the Azan playback service
            val serviceStarted = startAzanService(context, prayerName, currentTime)
            
            if (serviceStarted) {
                Log.i(TAG, "✅ Successfully initiated Azan playback for $prayerName")
                
                // Clean up the alarm from storage since it has been triggered
                cleanupTriggeredAlarm(context, prayerName)
            } else {
                Log.e(TAG, "❌ Failed to start Azan service for $prayerName")
                // Remove from playing list since service didn't start
                playingAzans.remove(prayerName)
            }
            
        } finally {
            // Always release wake lock
            wakeLockResult?.let { (wakeLock, acquired) ->
                if (acquired && wakeLock.isHeld) {
                    try {
                        wakeLock.release()
                        Log.d(TAG, "Wake lock released for $prayerName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error releasing wake lock for $prayerName", e)
                    }
                }
            }
        }
    }

    /**
     * Checks if this is a duplicate alarm within the prevention window
     */
    private fun isDuplicateAlarm(prayerName: String, currentTime: Long): Boolean {
        val lastAlarmTime = recentAlarms[prayerName]
        
        return if (lastAlarmTime != null) {
            val timeSinceLastAlarm = currentTime - lastAlarmTime
            timeSinceLastAlarm < DUPLICATE_PREVENTION_WINDOW_MS
        } else {
            false
        }
    }

    /**
     * Checks if an Azan for this prayer is currently playing
     */
    private fun isAzanCurrentlyPlaying(prayerName: String, currentTime: Long): Boolean {
        val playingTime = playingAzans[prayerName]
        
        return if (playingTime != null) {
            val timeSinceStarted = currentTime - playingTime
            // Assume Azan plays for maximum 10 minutes
            timeSinceStarted < 600_000L
        } else {
            false
        }
    }

    /**
     * Acquires wake lock for reliable service operation
     */
    private fun acquireWakeLock(context: Context, prayerName: String): Pair<PowerManager.WakeLock, Boolean>? {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, 
                "AzanApp::ReceiverWakeLock::$prayerName"
            )
            
            wakeLock.acquire(TimeUnit.SECONDS.toMillis(30)) // 30 seconds timeout
            Log.d(TAG, "Wake lock acquired for $prayerName")
            
            Pair(wakeLock, true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock for $prayerName", e)
            null
        }
    }

    /**
     * Starts the Azan playback service with proper error handling
     */
    private fun startAzanService(context: Context, prayerName: String, currentTime: Long): Boolean {
        return try {
            val serviceIntent = Intent(context, AzanPlaybackService::class.java).apply {
                action = AzanPlaybackService.ACTION_PLAY_AZAN_SERVICE
                putExtra(AzanPlaybackService.PRAYER_NAME_EXTRA_SERVICE, prayerName)
                putExtra("TRIGGERED_TIME", currentTime)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }

            val serviceResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            if (serviceResult != null) {
                Log.d(TAG, "Azan service started successfully for $prayerName")
                true
            } else {
                Log.e(TAG, "Service start returned null for $prayerName")
                false
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting Azan service for $prayerName", e)
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Illegal state exception starting Azan service for $prayerName", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting Azan service for $prayerName", e)
            false
        }
    }

    /**
     * Cleans up the triggered alarm from storage
     */
    private suspend fun cleanupTriggeredAlarm(context: Context, prayerName: String) {
        try {
            // Remove from alarm storage since it has been triggered
            AzanAlarmStore.clearAzan(context, prayerName)
            Log.d(TAG, "Cleaned up triggered alarm storage for $prayerName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup alarm storage for $prayerName", e)
        }
    }
}