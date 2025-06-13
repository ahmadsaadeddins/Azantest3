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
        const val PRAYER_NAME_EXTRA = "PRAYER_NAME_EXTRA"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prayerName = intent.getStringExtra(PRAYER_NAME_EXTRA) ?: "Prayer"
        Log.d("AzanAlarmReceiver", "Alarm received for $prayerName")

        // ✅ Use helper to check if Azan is enabled
        if (!AzanSettings.isAzanEnabledBlocking(context)) {
            Log.d("AzanAlarmReceiver", "Azan is disabled globally. Skipping $prayerName")
            return
        }

        // 🔋 Acquire WakeLock to ensure reliable playback
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AzanApp::ReceiverWakeLockTag")
        wakeLock.acquire(TimeUnit.SECONDS.toMillis(10))

        val serviceIntent = Intent(context, AzanPlaybackService::class.java).apply {
            action = AzanPlaybackService.ACTION_PLAY_AZAN_SERVICE
            putExtra(AzanPlaybackService.PRAYER_NAME_EXTRA_SERVICE, prayerName)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("AzanAlarmReceiver", "Failed to start Azan service for $prayerName", e)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            Log.d("AzanAlarmReceiver", "Receiver WakeLock released.")
        }
    }
}
