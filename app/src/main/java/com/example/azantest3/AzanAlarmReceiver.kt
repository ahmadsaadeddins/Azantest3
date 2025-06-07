// AzanAlarmReceiver.kt
package com.example.azantest3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.TimeUnit

class AzanAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PLAY_AZAN = "com.example.azantest3.ACTION_PLAY_AZAN"
        const val PRAYER_NAME_EXTRA = "PRAYER_NAME_EXTRA" // Used by AzanScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prayerName = intent.getStringExtra(PRAYER_NAME_EXTRA) ?: "Prayer"
        Log.d("AzanAlarmReceiver", "Alarm received for $prayerName. Attempting to start AzanPlaybackService.")

        // Acquire a short WakeLock to ensure the service starts reliably
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AzanApp::ReceiverWakeLockTag")
        // Acquire with a timeout. 10 seconds should be more than enough to start the service.
        wakeLock.acquire(TimeUnit.SECONDS.toMillis(10))

        val serviceIntent = Intent(context, AzanPlaybackService::class.java).apply {
            action = AzanPlaybackService.ACTION_PLAY_AZAN_SERVICE // Set an action
            putExtra(AzanPlaybackService.PRAYER_NAME_EXTRA_SERVICE, prayerName)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                Log.d("AzanAlarmReceiver", "Called startForegroundService for $prayerName.")
            } else {
                context.startService(serviceIntent)
                Log.d("AzanAlarmReceiver", "Called startService for $prayerName.")
            }
        } catch (e: Exception) {
            Log.e("AzanAlarmReceiver", "Error starting service for $prayerName", e)
            // Handle error, e.g., show a toast or log more details
        } finally {
            // Release the WakeLock once the service start has been initiated.
            // The service will manage its own lifecycle and wakelocks if needed for playback.
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d("AzanAlarmReceiver", "Receiver WakeLock released.")
            }
        }
        Log.d("AzanAlarmReceiver", "onReceive finished for $prayerName.")
    }
}