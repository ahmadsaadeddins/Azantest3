package com.example.azantest3


import android.content.Context
import android.icu.util.Calendar
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

// In your Application class or PrayerViewModel init
fun scheduleDailyRescheduler(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED) // No network needed
        .build()

    val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyAzanReschedulerWorker>(
        1, TimeUnit.DAYS // Repeat approximately every 1 day
        // For testing, you can use a shorter interval like 15 minutes:
        // 15, TimeUnit.MINUTES (minimum interval for periodic work)
    )
        .setConstraints(constraints)
        .setInitialDelay(calculateInitialDelayToAfterMidnight(), TimeUnit.MILLISECONDS) // Optional: delay first run
//        .addTag("DailyAzanRescheduleTag")
        .build()
//    val testOneTimeWorkRequest = OneTimeWorkRequestBuilder<DailyAzanReschedulerWorker>()
//        .setConstraints(constraints)
//         .setInitialDelay(30, TimeUnit.SECONDS) // Optional: run after a short delay
//        .addTag("DailyAzanRescheduleTag_OneTime")
//        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "DailyAzanRescheduleWorkName",
        ExistingPeriodicWorkPolicy.UPDATE, // Or REPLACE if you want to update it
        dailyWorkRequest
    )
//    WorkManager.getInstance(context).enqueueUniqueWork( // Use enqueueUniqueWork for one-time
//        "DailyAzanRescheduleTag_OneTime", // Different unique name
//        ExistingWorkPolicy.REPLACE,
//        testOneTimeWorkRequest
//    )
    Log.d("AppSetup", "Daily Azan Rescheduler worker enqueued.${calculateInitialDelayToAfterMidnight()}")
}

fun calculateInitialDelayToAfterMidnight(): Long {
    val calendar = Calendar.getInstance()
    val now = calendar.timeInMillis
    calendar.add(Calendar.DAY_OF_YEAR, 1) // Move to tomorrow
    calendar.set(Calendar.HOUR_OF_DAY, 0)  // Midnight
    calendar.set(Calendar.MINUTE, 1)     // 00:01 AM
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val nextRunTime = calendar.timeInMillis
    return nextRunTime - now
}