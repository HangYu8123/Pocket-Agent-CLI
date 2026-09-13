package com.pocketagent

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Background "hey Pat" detector. Keeps the microphone open (foreground service of type
 * microphone) and runs the offline Whisper Tiny model on each spoken phrase. On a match it
 * brings up the home screen in voice-command mode.
 *
 * The service pauses whenever one of the app's own activities is in front: the app is
 * already open, and the activities need the microphone themselves.
 */
class WakeWordService : Service() {

    private var listener: Listener? = null
    private var paused = false
    private var lastHit = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).wakeWordEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        // Debug builds: `am startservice -a com.pocketagent.WAKE_DEBUG …` acts as if the phrase was heard.
        if (BuildConfig.DEBUG && intent?.action == ACTION_DEBUG_WAKE) { onWake(); return START_STICKY }
        val n = notification("Listening for “${Prefs(this).wakePhrase}”")
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, n)
        paused = App.inForeground
        if (!paused) startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (listener?.isRunning == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update("Microphone permission missing; wake word off"); return
        }
        val prefs = Prefs(this)
        val phrase = prefs.wakePhrase
        val lang = if (phrase.any { it.code > 127 }) prefs.voiceLanguage else "en"
        val l = Listener(this, MODEL, lang, prompt = "${phrase.replaceFirstChar { it.uppercase() }}.", endSilenceMs = 600)
        l.onText = { heard ->
            if (VoiceCommands.matchesWake(heard, phrase)) onWake()
        }
        Log.i(TAG, "listening for \"$phrase\"")
        l.onError = { msg -> update(msg) }
        if (l.start()) listener = l
    }

    private fun stopListening() {
        listener?.release(); listener = null
    }

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) stopListening() else startListening()
        update(if (p) "Paused while Pocket-CLI is open" else "Listening for “${Prefs(this).wakePhrase}”")
    }

    private fun onWake() {
        val now = System.currentTimeMillis()
        if (now - lastHit < 3000) return
        lastHit = now
        Log.i(TAG, "wake word heard")
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        stopListening() // the activity takes the microphone now
        val open = wakeIntent(this)
        var started = false
        if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) {
            try { startActivity(open); started = true } catch (e: Exception) { Log.w(TAG, "startActivity failed", e) }
        }
        // Android 10+ blocks activity starts from the background unless the app may draw over
        // other apps; a full-screen notification is the sanctioned alternative.
        val pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, WAKE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Pocket-CLI heard you")
            .setContentText("Tap to give a voice command")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        try { NotificationManagerCompat.from(this).notify(WAKE_NOTIFICATION_ID, n) } catch (_: SecurityException) {}
        if (started) {
            // If the activity did come up, the heads-up is redundant; drop it shortly after.
            android.os.Handler(mainLooper).postDelayed({
                if (App.inForeground) NotificationManagerCompat.from(this).cancel(WAKE_NOTIFICATION_ID)
            }, 2500)
        }
        // Resume listening if nothing came to the foreground (e.g. the notification was ignored).
        android.os.Handler(mainLooper).postDelayed({ if (!App.inForeground && !paused) startListening() }, 8000)
    }

    private fun update(text: String) {
        try { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(text)) } catch (_: SecurityException) {}
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Wake word on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Turn off", stop)
            .build()
    }

    override fun onDestroy() {
        stopListening()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWord"
        const val CHANNEL_ID = "wakeword"
        const val WAKE_CHANNEL = "wakeword_hit"
        const val NOTIFICATION_ID = 2
        const val WAKE_NOTIFICATION_ID = 3
        const val ACTION_STOP = "com.pocketagent.WAKE_STOP"
        const val ACTION_DEBUG_WAKE = "com.pocketagent.WAKE_DEBUG"
        val MODEL = WhisperEngine.Model.TINY
        @Volatile var instance: WakeWordService? = null

        val isRunning get() = instance != null

        fun wakeIntent(c: Context) = Intent(c, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_VOICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun hasMic(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Starts the detector; returns false when Android refused (background start on Android 12+). */
        fun start(c: Context): Boolean {
            if (!hasMic(c) || !WhisperEngine(c).isDownloaded(MODEL)) return false
            return try {
                ContextCompat.startForegroundService(c, Intent(c, WakeWordService::class.java)); true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) Log.w(TAG, "cannot start from background", e)
                else Log.w(TAG, "start failed", e)
                false
            }
        }

        fun stop(c: Context) { c.stopService(Intent(c, WakeWordService::class.java)) }

        /** Called by [App] when the app's own activities go in front of / behind everything else. */
        fun onAppForeground(fg: Boolean) { instance?.setPaused(fg) }

        fun ensureChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(WAKE_CHANNEL, "Wake word heard", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

/** After a reboot, bring the wake-word detector back (or offer to, where Android forbids a direct start). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs(c).wakeWordEnabled) return
        if (WakeWordService.start(c)) return
        // Android 15 forbids microphone services from boot receivers: ask with a tap instead.
        WakeWordService.ensureChannels(c)
        val pi = PendingIntent.getActivity(c, 4, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_WAKE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, WakeWordService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Resume “${Prefs(c).wakePhrase}” listening")
            .setContentText("Tap to turn the wake word back on after the restart")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(c).notify(WakeWordService.NOTIFICATION_ID, n) } catch (_: SecurityException) {}
    }
}
