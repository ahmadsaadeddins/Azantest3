package com.example.azantest3 // Adjust package name

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import com.example.azantest3.datastore.PRAYER_NAMES_ARABIC // For display names
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.icu.util.Calendar
import java.util.Date
import java.util.Locale

// This function is good as is, for getting the repository instance
private fun getPrayerRepository(context: Context): PrayerRepository {
    val settingsDataStore = SettingsDataStore(context.applicationContext)

    val dao = AppDatabase.getDatabase(context.applicationContext).prayerTimeDao()
    return PrayerRepository(dao,settingsDataStore)
}

class PrayerTimeWidgetProvider : AppWidgetProvider() {

    private val job = SupervisorJob()
    // Use Dispatchers.IO for database/network operations, then switch to Main for UI updates
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    companion object {
        const val ACTION_AUTO_UPDATE = "com.example.azantest3.widget.ACTION_AUTO_UPDATE"
        private const val WIDGET_UPDATE_INTERVAL_MS: Long = 60 * 1000 // 1 minute
        private const val TAG = "PrayerWidgetProvider" // For logging
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for appWidgetIds: ${appWidgetIds.joinToString()}")
        appWidgetIds.forEach { appWidgetId ->
            // Get repository instance for each update
            val prayerRepository = getPrayerRepository(context)
            // Get com.example.azantest3.SettingsDataStore instance
            updateAppWidget(context, appWidgetManager, appWidgetId, prayerRepository)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        prayerRepository: PrayerRepository, // Receive the repository
    ) {
        Log.d(TAG, "updateAppWidget called for appWidgetId: $appWidgetId")
        val views = RemoteViews(context.packageName, R.layout.prayer_widget_layout)

        coroutineScope.launch { // This coroutine is on Dispatchers.IO
            try {
                val now = Calendar.getInstance()

                // Fetch next prayer using the actual repository method
                // The getNextUpcomingPrayerForWidget method now directly takes com.example.azantest3.SettingsDataStore
                val nextPrayerInfo: PrayerRepository.NextPrayerInfo? =
                    prayerRepository.getNextUpcomingPrayerForWidget(now)

                val nextPrayerNameDisplay1: String
                val nextPrayerTimeDate: Date?

                if (nextPrayerInfo != null) {
                    // Map the English name from PRAYER_NAMES (used in NextPrayerInfo.name)
                    // to the Arabic name if needed for display.
                    // This assumes PRAYER_NAMES in your repo logic and PRAYER_NAMES_ARABIC here have corresponding order.
                    val prayerIndex = com.example.azantest3.datastore.PRAYER_NAMES.indexOf(nextPrayerInfo.name)
                    nextPrayerNameDisplay1 = if (prayerIndex != -1) {
                        PRAYER_NAMES_ARABIC.getOrElse(prayerIndex) { nextPrayerInfo.name }
                    } else {
                        nextPrayerInfo.name // Fallback to the name from repo if not found in PRAYER_NAMES
                    }
                    nextPrayerTimeDate = nextPrayerInfo.time
                    Log.d(TAG, "Fetched next prayer: ${nextPrayerInfo.name} (Entity: ${nextPrayerInfo.prayerTimeEntity.fajr}..), Time: $nextPrayerTimeDate")
                } else {
                    nextPrayerNameDisplay1 = PRAYER_NAMES_ARABIC.getOrElse(0) { "الصلاة" } // Default "Prayer" in Arabic
                    nextPrayerTimeDate = null
                    Log.d(TAG, "No next prayer info found.")
                }

                val remainingTimeDisplay = if (nextPrayerTimeDate != null) {
                    calculateRemainingTime(nextPrayerTimeDate)
                } else {
                    "--:--:--" // Or "N/A"
                }

                Log.d(TAG, "Displaying: Name: $nextPrayerNameDisplay1, Remaining: $remainingTimeDisplay")

                // Switch to Main dispatcher for UI updates
                withContext(Dispatchers.Main) {
                    views.setTextViewText(R.id.tv_next_prayer_name, nextPrayerNameDisplay1)
                    views.setTextViewText(R.id.tv_remaining_time, remainingTimeDisplay)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                    Log.d(TAG, "Widget UI updated for appWidgetId: $appWidgetId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget for appWidgetId: $appWidgetId", e)
                // Optionally, update widget to show an error state
                withContext(Dispatchers.Main) {
                    views.setTextViewText(R.id.tv_next_prayer_name, "خطأ") // "Error" in Arabic
                    views.setTextViewText(R.id.tv_remaining_time, "--:--")
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }
    }

    // This function calculates remaining time, can stay as is or be moved to a utility
    private fun calculateRemainingTime(nextPrayerDate: Date): String {
        val currentTime = System.currentTimeMillis() // More accurate than new Date().time for intervals
        val diff = nextPrayerDate.time - currentTime

        if (diff <= 0) {
            return "حان الآن!" // "Time now!" in Arabic
        }

        val totalSeconds = diff / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        // val seconds = totalSeconds % 60 // Usually not shown

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d متبقي", hours, minutes) // "HH:mm left"
        } else {
            String.format(Locale.getDefault(), "%d دقيقة متبقية", minutes) // "mm minutes left"
        }
    }


    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action: ${intent.action}")
        super.onReceive(context, intent) // Important to call super
        if (intent.action == ACTION_AUTO_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisAppWidget = ComponentName(context.packageName, javaClass.name)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
            if (appWidgetIds.isNotEmpty()) {
                Log.d(TAG, "ACTION_AUTO_UPDATE received, triggering onUpdate for all widgets.")
                onUpdate(context, appWidgetManager, appWidgetIds) // Trigger update for all instances
                    scheduleNextUpdate(context) // Reschedule the next alarm
            } else {
                Log.d(TAG, "ACTION_AUTO_UPDATE received, but no widget instances found.")
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "onEnabled: First widget instance placed, scheduling updates.")
        scheduleNextUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "onDisabled: Last widget instance removed, cancelling updates and job.")
        cancelAlarm(context)
        job.cancel() // Cancel coroutines
    }

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PrayerTimeWidgetProvider::class.java).apply {
            action = ACTION_AUTO_UPDATE
        }
        // FLAG_IMMUTABLE is required for S+ if the PendingIntent isn't modified by the receiver.
        // FLAG_UPDATE_CURRENT ensures that if an alarm is already set, it's updated with this new intent.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags) // requestCode 0 is fine here
    }

    private fun scheduleNextUpdate(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = getPendingIntent(context)

        // For Android 12+, exact alarms need special permission (USE_EXACT_ALARM) or `canScheduleExactAlarms()` check.
        // For a widget that updates frequently for display, setRepeating might be subject to OS optimizations (Doze).
        // Consider WorkManager for more robust background work if updates become unreliable,
        // though for a 1-minute interval, AlarmManager is typical for widgets.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle( // More resilient for exact timing if permission granted
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WIDGET_UPDATE_INTERVAL_MS,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact alarm for API S+")
            } else {
                // Fallback or request permission. For a widget, often just use inexact.
                // However, for frequent updates, setRepeating is common.
                // The original setRepeating is generally fine for this use case if exactness isn't critical to the second.
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WIDGET_UPDATE_INTERVAL_MS,
                    WIDGET_UPDATE_INTERVAL_MS,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled repeating (inexact allowed) alarm for API S+ as exact not permitted.")
            }
        } else {
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WIDGET_UPDATE_INTERVAL_MS,
                WIDGET_UPDATE_INTERVAL_MS,
                pendingIntent
            )
            Log.d(TAG, "Scheduled repeating alarm for pre-API S.")
        }
        Log.d(TAG, "Next update scheduled in ${WIDGET_UPDATE_INTERVAL_MS / 1000} seconds.")
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = getPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Updates alarm cancelled.")
    }
}