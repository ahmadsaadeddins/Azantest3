package com.example.azantest3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager


class TimeChangeReceiver : BroadcastReceiver() {


    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            Log.i("TimeChangeReceiver", "⏰ Time or timezone changed. Triggering reschedule.")

            // Invalidate any in-memory prayer cache
            PrayerRepository.clearCacheFor()

            val oneTimeRequest = OneTimeWorkRequestBuilder<DailyAzanReschedulerWorker>()
                .addTag("ImmediateRescheduleDueToTimeChange")
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeRequest)
        }
    }

}