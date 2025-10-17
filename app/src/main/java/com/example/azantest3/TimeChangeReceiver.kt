package com.example.azantest3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Improved receiver for handling system time and timezone changes.
 * Ensures Azan schedules are updated when the system time changes,
 * timezone changes, or device is rebooted.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimeChangeReceiver"
        private const val WORK_NAME_TIME_CHANGE = "azan_reschedule_time_change"
        private const val WORK_DELAY_SECONDS = 5L // Small delay to ensure system is stable
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: "Unknown Action"
        val currentTime = System.currentTimeMillis()
        val currentTimeZone = TimeZone.getDefault().id
        
        Log.i(TAG, "🔄 Time/Timezone change detected: $action")
        Log.d(TAG, "Current time: ${java.util.Date(currentTime)}")
        Log.d(TAG, "Current timezone: $currentTimeZone")

        when (action) {
            Intent.ACTION_TIME_SET -> {
                Log.i(TAG, "⏰ System time was manually changed")
                handleTimeChange(context, "TIME_SET")
            }
            
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val newTimeZone = intent.getStringExtra("time-zone") ?: TimeZone.getDefault().id
                Log.i(TAG, "🌍 Timezone changed to: $newTimeZone")
                handleTimeChange(context, "TIMEZONE_CHANGED")
            }
            
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "🔄 Device boot completed")
                handleTimeChange(context, "BOOT_COMPLETED")
            }
            
            Intent.ACTION_DATE_CHANGED -> {
                Log.i(TAG, "📅 Date changed")
                handleTimeChange(context, "DATE_CHANGED")
            }
            
            else -> {
                Log.w(TAG, "⚠️ Unhandled action: $action")
            }
        }
    }

    /**
     * Handles time/timezone changes by rescheduling all Azans
     */
    private fun handleTimeChange(context: Context, changeType: String) {
        Log.d(TAG, "Handling time change: $changeType")
        
        // Use coroutine for async operations
        val scope = CoroutineScope(Dispatchers.Main)
        
        scope.launch {
            try {
                // Check if Azan is enabled before doing any work
                val isAzanEnabled = AzanSettings.isAzanEnabledBlocking(context)
                
                if (!isAzanEnabled) {
                    Log.d(TAG, "Azan is disabled, no need to reschedule")
                    return@launch
                }
                
                // Cancel any currently scheduled Azans
                val azanScheduler = AzanScheduler(context)
                azanScheduler.cancelAllScheduledAzans()
                
                Log.i(TAG, "Cancelled existing Azans due to $changeType")
                
                // Schedule a worker to reschedule Azans after a small delay
                // This ensures the system time has stabilized
                scheduleReschedulingWorker(context, changeType)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error handling time change ($changeType)", e)
            }
        }
    }

    /**
     * Schedules a worker to reschedule Azans after system stabilization
     */
    private fun scheduleReschedulingWorker(context: Context, changeType: String) {
        try {
            val workRequest = OneTimeWorkRequestBuilder<DailyAzanReschedulerWorker>()
                .setInitialDelay(WORK_DELAY_SECONDS, TimeUnit.SECONDS)
                .addTag("time_change_reschedule")
                .addTag(changeType.lowercase())
                .build()

            // Use REPLACE to ensure only one rescheduling work is active
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_TIME_CHANGE,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            Log.i(TAG, "✅ Scheduled Azan rescheduling worker for $changeType (delay: ${WORK_DELAY_SECONDS}s)")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule rescheduling worker for $changeType", e)
        }
    }

    /**
     * Validates the current system state for debugging purposes
     */
    private fun logSystemState() {
        try {
            val currentTime = System.currentTimeMillis()
            val timeZone = TimeZone.getDefault()
            
            Log.d(TAG, "=== System State ===")
            Log.d(TAG, "Current time: ${java.util.Date(currentTime)}")
            Log.d(TAG, "Timezone ID: ${timeZone.id}")
            Log.d(TAG, "Timezone display name: ${timeZone.displayName}")
            Log.d(TAG, "UTC offset: ${timeZone.rawOffset / (1000 * 60 * 60)} hours")
            Log.d(TAG, "DST offset: ${timeZone.dstSavings / (1000 * 60 * 60)} hours")
            Log.d(TAG, "In DST: ${timeZone.inDaylightTime(java.util.Date())}")
            Log.d(TAG, "===================")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log system state", e)
        }
    }
}