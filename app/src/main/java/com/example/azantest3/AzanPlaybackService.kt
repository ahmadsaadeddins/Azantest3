// AzanPlaybackService.kt
package com.example.azantest3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
//import androidx.privacysandbox.tools.core.generator.build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AzanPlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null // For MediaPlayer's own wakelock needs

    companion object {
        const val PRAYER_NAME_EXTRA_SERVICE = "PRAYER_NAME_EXTRA_SERVICE"
        const val ACTION_PLAY_AZAN_SERVICE = "com.example.azantest3.ACTION_PLAY_AZAN_SERVICE"
        const val ACTION_STOP_AZAN_SERVICE = "com.example.azantest3.ACTION_STOP_AZAN_SERVICE" // Optional stop action

        private const val NOTIFICATION_ID = 786 // Must be unique and > 0
        private const val CHANNEL_ID = "AzanPlaybackChannel"
        private const val CHANNEL_NAME = "Azan Playback"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("AzanPlaybackService", "onCreate called.")
        createNotificationChannel()

        // Acquire a WakeLock for the MediaPlayer.
        // This ensures that the CPU keeps running under partial wake lock mode while playing audio.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        serviceWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AzanApp::ServiceWakeLockTag")
        Log.d("AzanPlaybackService", "Service WakeLock created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prayerName = intent?.getStringExtra(PRAYER_NAME_EXTRA_SERVICE) ?: "Azan"
        val action = intent?.action

        Log.d("AzanPlaybackService", "onStartCommand received. Action: $action, Prayer: $prayerName")

        when (action) {
            ACTION_PLAY_AZAN_SERVICE -> {
                Log.d("AzanPlaybackService", "Starting foreground service and Azan for $prayerName.")
                val notification = createNotification(prayerName)
                try {
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d("AzanPlaybackService", "startForeground called successfully for $prayerName.")
                    playAzan(prayerName)
                } catch (e: Exception) {
                    Log.e("AzanPlaybackService", "Error in startForeground or playAzan for $prayerName", e)
                    // If startForeground fails (e.g., missing POST_NOTIFICATIONS on API 33+ without permission)
                    // the service might be killed quickly.
                    stopSelfAndCleanup()
                }
            }
            ACTION_STOP_AZAN_SERVICE -> {
                Log.d("AzanPlaybackService", "Received stop action. Stopping Azan.")
                stopSelfAndCleanup()
            }
            else -> {
                Log.w("AzanPlaybackService", "Unknown action or no action received: $action. Stopping service.")
                stopSelfAndCleanup()
            }
        }
        // If the service is killed, START_NOT_STICKY means it won't be automatically restarted
        // unless there are pending intents. For Azan, this is usually fine.
        return START_NOT_STICKY
    }

    private fun createNotification(prayerName: String): Notification {
        // Intent to open your app when notification is clicked (optional)
        val notificationIntent = Intent(this, MainActivity::class.java) // Replace MainActivity with your main activity
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // Optional: Add a stop action to the notification
        val stopServiceIntent = Intent(this, AzanPlaybackService::class.java).apply {
            action = ACTION_STOP_AZAN_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopServiceIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("حان وقت صلاة $prayerName")
            .setContentText("الأذان يرفع الآن")
            .setSmallIcon(R.drawable.ic_stop_azan) // REPLACE with your notification icon
            .setContentIntent(pendingIntent) // Optional: what happens when user taps notification
            .addAction(R.drawable.ic_stop_azan, "إيقاف", stopPendingIntent) // Optional: Stop action
            .setPriority(NotificationCompat.PRIORITY_LOW) // Or DEFAULT. HIGH may make a sound for the notif itself.
            .setOngoing(true) // Makes the notification non-dismissible while service is foreground
            .build()
    }

    private fun playAzan(prayerName: String) {
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            Log.d("AzanPlaybackService", "MediaPlayer already playing. Stopping previous and restarting for $prayerName.")
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }

        Log.d("AzanPlaybackService", "Attempting to create MediaPlayer for R.raw.azan ($prayerName).")
        mediaPlayer = MediaPlayer.create(this, R.raw.azan) // REPLACE R.raw.azan with your actual file

        if (mediaPlayer == null) {
            Log.e("AzanPlaybackService", "MediaPlayer.create() FAILED for R.raw.azan ($prayerName). Check file path and logs.")
            stopSelfAndCleanup()
            return
        }
        Log.d("AzanPlaybackService", "MediaPlayer.create() SUCCEEDED for $prayerName.")

        // Acquire WakeLock before starting playback
        if (serviceWakeLock?.isHeld == false) {
            serviceWakeLock?.acquire(TimeUnit.MINUTES.toMillis(7)) // Hold for up to 7 minutes (longer than typical Azan)
            Log.d("AzanPlaybackService", "Service WakeLock acquired for playback.")
        }


        mediaPlayer?.setOnPreparedListener { mp ->
            val preparedTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            Log.d("AzanPlaybackService", "MediaPlayer PREPARED at $preparedTime for $prayerName. Starting playback.")
            try {
                mp.start()
                val startedTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                Log.d("AzanPlaybackService", "MediaPlayer playback STARTED at $startedTime for $prayerName.")
                // Update notification if needed (e.g., change text to "Playing...")
            } catch (e: IllegalStateException) {
                Log.e("AzanPlaybackService", "MediaPlayer start FAILED after prepare for $prayerName", e)
                stopSelfAndCleanup()
            }
        }

        mediaPlayer?.setOnCompletionListener {
            val completedTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            Log.d("AzanPlaybackService", "MediaPlayer playback COMPLETED at $completedTime for $prayerName.")
            stopSelfAndCleanup()
        }

        mediaPlayer?.setOnErrorListener { mp, what, extra ->
            val errorTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            Log.e("AzanPlaybackService", "MediaPlayer ERROR at $errorTime for $prayerName. What: $what, Extra: $extra")
            // Add detailed error logging as before if needed
            stopSelfAndCleanup()
            true // Error handled
        }

        // For local resources, MediaPlayer.create() often prepares synchronously.
        // If it returns non-null, it's likely ready or preparing.
        // The setOnPreparedListener is the robust way to handle it.
        // If create() was successful, the listener will be called.
        // If you still have issues, you could add:
        // if (mediaPlayer?.isPlaying == false && !mediaPlayer!!.isLooping && !mediaPlayer!!.isPreparing){ try { mediaPlayer?.prepareAsync() } catch (e:Exception) {} }
        // but typically MediaPlayer.create() handles this for local files.
    }


    private fun stopSelfAndCleanup() {
        Log.d("AzanPlaybackService", "stopSelfAndCleanup called.")
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                Log.d("AzanPlaybackService", "MediaPlayer stopped in cleanup.")
            }
            mediaPlayer?.release()
            mediaPlayer = null
            Log.d("AzanPlaybackService", "MediaPlayer released in cleanup.")
        } catch (e: Exception) {
            Log.e("AzanPlaybackService", "Exception during media player stop/release in cleanup", e)
        }

        if (serviceWakeLock?.isHeld == true) {
            serviceWakeLock?.release()
            Log.d("AzanPlaybackService", "Service WakeLock released in cleanup.")
        }

        Log.d("AzanPlaybackService", "Calling stopForeground(true) and stopSelf().")
        stopForeground(STOP_FOREGROUND_REMOVE) // true / STOP_FOREGROUND_REMOVE to remove notification
        stopSelf()
    }

    override fun onDestroy() {
        Log.d("AzanPlaybackService", "onDestroy called. Ensuring cleanup.")
        stopSelfAndCleanup() // Ensure all resources are released
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Use LOW or DEFAULT. HIGH may make a sound for the notification itself.
            ).apply {
                description = "Channel for Azan playback notifications"
                // Set other channel properties if needed (e.g., setShowBadge(false))
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d("AzanPlaybackService", "Notification channel '$CHANNEL_ID' created.")
        }
    }
}