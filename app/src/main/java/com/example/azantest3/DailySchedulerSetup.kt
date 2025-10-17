package com.example.azantest3

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Improved setup for daily Azan scheduling with better work management,
 * duplicate prevention, and error handling.
 */
class DailySchedulerSetup {

    companion object {
        private const val TAG = "DailySchedulerSetup"
        private const val DAILY_WORK_NAME = "daily_azan_reschedule_work"
        private const val IMMEDIATE_WORK_NAME = "immediate_azan_reschedule_work"
        
        // Work tags for better management
        private const val TAG_DAILY = "daily_azan"
        private const val TAG_IMMEDIATE = "immediate_azan"
        private const val TAG_RESCHEDULE = "reschedule"
    }

    /**
     * Sets up the daily Azan rescheduler with improved work management
     */
    suspend fun setupDailyRescheduler(context: Context, forceSetup: Boolean = false) {
        Log.i(TAG, "🔧 Setting up daily Azan rescheduler (forceSetup: $forceSetup)")
        
        try {
            val workManager = WorkManager.getInstance(context)
            
            // Check if work is already scheduled and running properly
            if (!forceSetup && isDailyWorkActive(workManager)) {
                Log.d(TAG, "Daily work is already active and healthy, skipping setup")
                return
            }

            // Cancel any existing work before setting up new one
            cancelExistingWork(workManager)

            // Create constraints for the work
            val constraints = createWorkConstraints()

            // Create the periodic work request
            val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyAzanReschedulerWorker>(
                24, TimeUnit.HOURS, // Repeat every 24 hours
                15, TimeUnit.MINUTES  // Flex interval (can run within 15 min of schedule)
            )
                .setConstraints(constraints)
                .addTag(TAG_DAILY)
                .addTag(TAG_RESCHEDULE)
                .build()

            // Enqueue the periodic work with KEEP policy to avoid duplicates
            workManager.enqueueUniquePeriodicWork(
                DAILY_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
                dailyWorkRequest
            )

            Log.i(TAG, "✅ Daily Azan rescheduler work enqueued successfully")
            Log.d(TAG, "Work ID: ${dailyWorkRequest.id}")

            // Also schedule an immediate work for today if needed
            scheduleImmediateWork(context, workManager)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup daily rescheduler", e)
            throw e
        }
    }

    /**
     * Schedules immediate work to handle current day's prayers
     */
    private suspend fun scheduleImmediateWork(context: Context, workManager: WorkManager) {
        try {
            // Check if Azan is enabled before scheduling immediate work
            val settingsDataStore = SettingsDataStore(context)
            val isAzanEnabled = settingsDataStore.azanEnabledFlow.first()
            
            if (!isAzanEnabled) {
                Log.d(TAG, "Azan is disabled, skipping immediate work")
                return
            }

            val immediateWorkRequest = OneTimeWorkRequestBuilder<DailyAzanReschedulerWorker>()
                .setInitialDelay(2, TimeUnit.SECONDS) // Small delay to ensure setup is complete
                .addTag(TAG_IMMEDIATE)
                .addTag(TAG_RESCHEDULE)
                .build()

            // Use REPLACE to ensure only one immediate work runs
            workManager.enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                immediateWorkRequest
            )

            Log.d(TAG, "⚡ Immediate Azan work scheduled for current prayers")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule immediate work, continuing anyway", e)
        }
    }

    /**
     * Creates work constraints for reliable execution
     */
    private fun createWorkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Doesn't need network
            .setRequiresBatteryNotLow(false) // Should work even on low battery
            .setRequiresCharging(false) // Should work when not charging
            .setRequiresDeviceIdle(false) // Should work when device is active
            .setRequiresStorageNotLow(false) // Should work with low storage
            .build()
    }

    /**
     * Checks if daily work is already active and healthy
     */
    private suspend fun isDailyWorkActive(workManager: WorkManager): Boolean {
        return try {
            val workInfos = workManager.getWorkInfosForUniqueWork(DAILY_WORK_NAME).get()
            
            val hasActiveWork = workInfos.any { workInfo ->
                when (workInfo.state) {
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.ENQUEUED -> {
                        Log.d(TAG, "Found active daily work: ${workInfo.id} (${workInfo.state})")
                        true
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        Log.d(TAG, "Found succeeded daily work: ${workInfo.id}, will keep running")
                        true
                    }
                    else -> {
                        Log.d(TAG, "Found inactive daily work: ${workInfo.id} (${workInfo.state})")
                        false
                    }
                }
            }
            
            Log.d(TAG, "Daily work active check: $hasActiveWork (${workInfos.size} work items found)")
            hasActiveWork
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check daily work status", e)
            false
        }
    }

    /**
     * Cancels existing work before setting up new work
     */
    private suspend fun cancelExistingWork(workManager: WorkManager) {
        try {
            // Cancel by unique work names
            workManager.cancelUniqueWork(DAILY_WORK_NAME)
            workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
            
            // Also cancel by tags for cleanup
            workManager.cancelAllWorkByTag(TAG_DAILY)
            workManager.cancelAllWorkByTag(TAG_IMMEDIATE)
            
            Log.d(TAG, "Cancelled existing work before setup")
            
            // Small delay to ensure cancellation is processed
            kotlinx.coroutines.delay(100)
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel existing work, continuing anyway", e)
        }
    }

    /**
     * Gets the next execution time for daily work (for debugging)
     */
    suspend fun getNextExecutionTime(context: Context): String {
        return try {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork(DAILY_WORK_NAME).get()
            
            if (workInfos.isNotEmpty()) {
                val workInfo = workInfos.first()
                val nextRunTime = workInfo.nextScheduleTimeMillis
                
                if (nextRunTime > 0) {
                    "Next execution: ${java.util.Date(nextRunTime)}"
                } else {
                    "Next execution: Not scheduled or immediate"
                }
            } else {
                "No daily work found"
            }
            
        } catch (e: Exception) {
            "Error getting execution time: ${e.message}"
        }
    }

    /**
     * Gets work status for debugging
     */
    suspend fun getWorkStatus(context: Context): Map<String, Any> {
        return try {
            val workManager = WorkManager.getInstance(context)
            
            val dailyWorkInfos = workManager.getWorkInfosForUniqueWork(DAILY_WORK_NAME).get()
            val immediateWorkInfos = workManager.getWorkInfosForUniqueWork(IMMEDIATE_WORK_NAME).get()
            
            mapOf(
                "dailyWorkCount" to dailyWorkInfos.size,
                "immediateWorkCount" to immediateWorkInfos.size,
                "dailyWorkStates" to dailyWorkInfos.map { "${it.id}: ${it.state}" },
                "immediateWorkStates" to immediateWorkInfos.map { "${it.id}: ${it.state}" },
                "nextExecutionTime" to getNextExecutionTime(context)
            )
            
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * Forces a manual reschedule of all Azans (for debugging/testing)
     */
    suspend fun forceManualReschedule(context: Context) {
        Log.i(TAG, "🔄 Forcing manual Azan reschedule")
        
        try {
            val workManager = WorkManager.getInstance(context)
            
            val manualWorkRequest = OneTimeWorkRequestBuilder<DailyAzanReschedulerWorker>()
                .addTag("manual_reschedule")
                .addTag(TAG_RESCHEDULE)
                .build()

            workManager.enqueue(manualWorkRequest)
            
            Log.i(TAG, "✅ Manual reschedule work enqueued: ${manualWorkRequest.id}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to force manual reschedule", e)
            throw e
        }
    }

    /**
     * Cleans up all Azan-related work (for troubleshooting)
     */
    suspend fun cleanupAllWork(context: Context) {
        Log.w(TAG, "🧹 Cleaning up ALL Azan-related work")
        
        try {
            val workManager = WorkManager.getInstance(context)
            
            // Cancel all work by tags
            workManager.cancelAllWorkByTag(TAG_DAILY)
            workManager.cancelAllWorkByTag(TAG_IMMEDIATE)
            workManager.cancelAllWorkByTag(TAG_RESCHEDULE)
            workManager.cancelAllWorkByTag("manual_reschedule")
            
            // Cancel by unique work names
            workManager.cancelUniqueWork(DAILY_WORK_NAME)
            workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
            
            Log.i(TAG, "✅ All Azan work cleanup completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to cleanup work", e)
            throw e
        }
    }
}