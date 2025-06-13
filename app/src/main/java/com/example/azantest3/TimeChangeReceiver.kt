package com.example.azantest3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit


class TimeChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimeChangeReceiver"
        // Ensure this UNIQUE_WORK_NAME matches what you use to enqueue the DailyAzanReschedulerWorker
        const val DAILY_AZAN_RESCHEDULER_WORK_NAME = "DailyAzanRescheduleWork"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            Log.i(TAG, "⏰ Time or timezone changed. Triggering reschedule.")

            // Invalidate any in-memory prayer cache
            PrayerRepository.clearCacheFor(context)

            val oneTimeRequest = OneTimeWorkRequestBuilder<DailyAzanReschedulerWorker>()
                .addTag("ImmediateRescheduleDueToTimeChange")
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeRequest)
        }
    }

}