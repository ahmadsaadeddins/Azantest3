package com.example.azantest3

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

class AzanPlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private val azanQueue = ConcurrentLinkedQueue<String>()
    private var isPlaying = false
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null


    companion object {
        const val PRAYER_NAME_EXTRA_SERVICE = "PRAYER_NAME_EXTRA_SERVICE"
        const val ACTION_PLAY_AZAN_SERVICE = "com.example.azantest3.ACTION_PLAY_AZAN_SERVICE"
        const val ACTION_STOP_AZAN_SERVICE = "com.example.azantest3.ACTION_STOP_AZAN_SERVICE"

        private const val NOTIFICATION_ID = 786
        private const val CHANNEL_ID = "AzanPlaybackChannel"
        private const val CHANNEL_NAME = "Azan Playback"
    }




    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        serviceWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AzanApp::ServiceWakeLockTag")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prayerName = intent?.getStringExtra(PRAYER_NAME_EXTRA_SERVICE) ?: "Azan"
        val action = intent?.action

        Log.d("AzanPlaybackService", "onStartCommand: $action | Prayer: $prayerName")

        if (action == ACTION_PLAY_AZAN_SERVICE) {
            // ✅ ALWAYS call startForeground right away to avoid crash
            startForeground(NOTIFICATION_ID, createNotification(prayerName))

            if (isPlaying) {
                azanQueue.offer(prayerName)
                Log.d("AzanPlaybackService", "Already playing. Queued $prayerName. Queue size: ${azanQueue.size}")
            } else {
                playAzan(prayerName)
            }

        } else if (action == ACTION_STOP_AZAN_SERVICE) {
            Log.d("AzanPlaybackService", "Stop action received.")
            azanQueue.clear()
            stopSelfAndCleanup()
        } else {
            Log.w("AzanPlaybackService", "Unknown action received: $action")
            stopSelfAndCleanup()
        }

        return START_NOT_STICKY
    }

    private fun playAzan(prayerName: String) {
        Log.d("AzanPlaybackService", "Starting playback for $prayerName")
        isPlaying = true

        if (serviceWakeLock?.isHeld != true) {
            serviceWakeLock?.acquire(TimeUnit.MINUTES.toMillis(7))
            Log.d("AzanPlaybackService", "WakeLock acquired.")
        }

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, R.raw.azan)

            mediaPlayer?.setOnCompletionListener {
                Log.d("AzanPlaybackService", "Azan finished for $prayerName")
                isPlaying = false
                mediaPlayer?.release()
                mediaPlayer = null

                val next = azanQueue.poll()
                if (next != null) {
                    Log.d("AzanPlaybackService", "Dequeued next prayer: $next")
                    startForeground(NOTIFICATION_ID, createNotification(next))
                    playAzan(next)
                } else {
                    stopSelfAndCleanup()
                }
            }

            mediaPlayer?.setOnErrorListener { _, what, extra ->
                Log.e("AzanPlaybackService", "MediaPlayer error: what=$what extra=$extra")
                isPlaying = false
                mediaPlayer?.release()
                mediaPlayer = null
                stopSelfAndCleanup()
                true
            }
            val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d("AzanPlaybackService", "Audio focus lost — stopping playback.")
                        mediaPlayer?.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d("AzanPlaybackService", "Audio focus lost (duck) — reducing volume.")
                        mediaPlayer?.setVolume(0.2f, 0.2f)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d("AzanPlaybackService", "Audio focus regained — resuming or restoring volume.")
                        mediaPlayer?.setVolume(1f, 1f)
                        mediaPlayer?.start()
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()

                val result = audioManager.requestAudioFocus(focusRequest!!)
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.w("AzanPlaybackService", "Failed to gain audio focus. Skipping playback.")
                    stopSelfAndCleanup()
                    return
                }
            } else {
                val result = audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.w("AzanPlaybackService", "Failed to gain audio focus (pre-O).")
                    stopSelfAndCleanup()
                    return
                }
            }

            mediaPlayer?.start()
            Log.d("AzanPlaybackService", "Playback started for $prayerName")

        } catch (e: Exception) {
            Log.e("AzanPlaybackService", "Error during Azan playback", e)
            isPlaying = false
            stopSelfAndCleanup()
        }
    }

    private fun stopSelfAndCleanup() {
        Log.d("AzanPlaybackService", "Stopping service and cleaning up.")

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}

        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false

        if (serviceWakeLock?.isHeld == true) {
            serviceWakeLock?.release()
            Log.d("AzanPlaybackService", "WakeLock released.")
        }

        stopForeground(true)
        stopSelf()

        focusRequest?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(it)
            }
        } ?: audioManager.abandonAudioFocus(null)
    }

    override fun onDestroy() {
        Log.d("AzanPlaybackService", "onDestroy called.")
        stopSelfAndCleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d("AzanPlaybackService", "Notification channel created.")
        }
    }

    private fun createNotification(prayerName: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags)

        val stopIntent = Intent(this, AzanPlaybackService::class.java).apply {
            action = ACTION_STOP_AZAN_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("حان وقت صلاة $prayerName")
            .setContentText("الأذان يرفع الآن")
            .setSmallIcon(R.drawable.ic_stop_azan)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop_azan, "إيقاف", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
