package com.lorawan.app.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.lorawan.app.ui.AlertActivity

class AlertManager private constructor(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var mediaPlayer: MediaPlayer? = null
    private var isAlertActive = false
    private var isFlashOn = false
    private val flashHandler = Handler(Looper.getMainLooper())
    private var flashRunnable: Runnable? = null

    enum class AlertLevel {
        INFO,
        WARNING,
        CRITICAL
    }

    companion object {
        @Volatile
        private var INSTANCE: AlertManager? = null

        fun getInstance(context: Context): AlertManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AlertManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        const val CHANNEL_INFO = "channel_info"
        const val CHANNEL_WARNING = "channel_warning"
        const val CHANNEL_CRITICAL = "channel_critical"

        const val NOTIFICATION_ID_INFO = 1001
        const val NOTIFICATION_ID_WARNING = 1002
        const val NOTIFICATION_ID_CRITICAL = 1003

        val INFO_KEYWORDS = listOf("INFO", "1", "OK", "STATUS")
        val WARNING_KEYWORDS = listOf("WARN", "WARNING", "2", "ATTENTION")
        val CRITICAL_KEYWORDS = listOf("ALERT", "ALERTE", "SOS", "3", "URGENT", "CRITICAL", "EMERGENCY")
    }

    init {
        createNotificationChannels()
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun createNotificationChannels() {
        val infoChannel = NotificationChannel(
            CHANNEL_INFO,
            "Informations",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Messages d'information"
        }

        val warningChannel = NotificationChannel(
            CHANNEL_WARNING,
            "Avertissements",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Messages d'avertissement"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 250, 250)
        }

        val criticalChannel = NotificationChannel(
            CHANNEL_CRITICAL,
            "Alertes critiques",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertes urgentes"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setBypassDnd(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannels(listOf(infoChannel, warningChannel, criticalChannel))
    }

    fun detectAlertLevel(message: String): AlertLevel {
        val upperMessage = message.uppercase()
        return when {
            CRITICAL_KEYWORDS.any { upperMessage.contains(it) } -> AlertLevel.CRITICAL
            WARNING_KEYWORDS.any { upperMessage.contains(it) } -> AlertLevel.WARNING
            INFO_KEYWORDS.any { upperMessage.contains(it) } -> AlertLevel.INFO
            else -> AlertLevel.INFO
        }
    }

    fun triggerAlert(message: String, level: AlertLevel = detectAlertLevel(message)) {
        when (level) {
            AlertLevel.INFO -> showInfoNotification(message)
            AlertLevel.WARNING -> showWarningNotification(message)
            AlertLevel.CRITICAL -> showCriticalAlert(message)
        }
    }

    private fun showInfoNotification(message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Patapps LoRaWAN - Info")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_INFO, notification)
    }

    private fun showWarningNotification(message: String) {
        val vibrator = getVibrator()
        val pattern = longArrayOf(0, 250, 250, 250)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_WARNING)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Patapps LoRaWAN - Avertissement")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_WARNING, notification)
    }

    private fun showCriticalAlert(message: String) {
        isAlertActive = true

        val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("message", message)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CRITICAL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Patapps - ALERTE CRITIQUE")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(NOTIFICATION_ID_CRITICAL, notification)

        // Start continuous vibration
        startContinuousVibration()

        // Start ringtone
        startRingtone()

        // Start flash blinking
        startFlashBlink()

        // Start the activity
        context.startActivity(fullScreenIntent)
    }

    private fun startContinuousVibration() {
        val vibrator = getVibrator()
        val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun startRingtone() {
        try {
            stopRingtone() // Stop any existing ringtone first
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRingtone() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
    }

    private fun startFlashBlink() {
        try {
            val cameraId = cameraManager.cameraIdList[0]
            flashRunnable = object : Runnable {
                override fun run() {
                    if (!isAlertActive) return
                    try {
                        isFlashOn = !isFlashOn
                        cameraManager.setTorchMode(cameraId, isFlashOn)
                        flashHandler.postDelayed(this, 300) // Blink every 300ms
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }
            }
            flashHandler.post(flashRunnable!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopFlashBlink() {
        try {
            flashRunnable?.let { flashHandler.removeCallbacks(it) }
            flashRunnable = null
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, false)
            isFlashOn = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopAlert() {
        isAlertActive = false

        // Stop vibration
        getVibrator().cancel()

        // Stop ringtone
        stopRingtone()

        // Stop flash
        stopFlashBlink()

        // Cancel notification
        notificationManager.cancel(NOTIFICATION_ID_CRITICAL)
    }

    fun isAlertActive(): Boolean = isAlertActive
}
