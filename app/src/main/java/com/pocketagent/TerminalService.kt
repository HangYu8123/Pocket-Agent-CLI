package com.pocketagent

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Foreground service that owns the terminal sessions so they keep running while the
 * activity is in the background (or destroyed).
 */
class TerminalService : Service(), TerminalSessionClient {

    inner class LocalBinder : Binder() { val service get() = this@TerminalService }
    private val binder = LocalBinder()

    val sessions = LinkedHashMap<String, TerminalSession>()
    /** Guest working directory each session was started in (mode key → path inside Ubuntu). */
    private val sessionCwd = HashMap<String, String>()
    fun cwdOf(mode: Mode): String? = sessionCwd[mode.key]
    fun isRunning(mode: Mode) = sessions[mode.key]?.isRunning == true
    /** The activity currently showing a session; receives forwarded callbacks. */
    @Volatile var uiClient: TerminalSessionClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            stopAll()
            return START_NOT_STICKY
        }
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    fun runningModes(): Set<String> = sessions.filterValues { it.isRunning }.keys

    fun getOrCreate(mode: Mode): TerminalSession {
        sessions[mode.key]?.let { if (it.isRunning) return it }
        return create(mode)
    }

    fun create(mode: Mode): TerminalSession {
        Proot.prepareBridge(this)
        val l = Proot.build(this, mode)
        // libtermux passes this array verbatim to execvp(), so argv[0] must be included.
        val argv = arrayOf("proot", *l.args)
        val s = TerminalSession(l.exe, l.cwd, argv, l.env, 5000, this)
        s.mSessionName = mode.title
        sessions[mode.key] = s
        sessionCwd[mode.key] = l.guestCwd
        acquireWakeLock()
        updateNotification()
        return s
    }

    fun kill(mode: Mode) {
        sessions.remove(mode.key)?.finishIfRunning()
        updateNotification()
        if (sessions.values.none { it.isRunning }) releaseWakeLock()
    }

    fun stopAll() {
        for (s in sessions.values) s.finishIfRunning()
        sessions.clear()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketCLI:sessions").apply { acquire() }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val running = sessions.values.count { it.isRunning }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val names = sessions.values.filter { it.isRunning }.joinToString(", ") { it.mSessionName ?: "session" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(if (running == 0) "No sessions" else "$running running: $names")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun updateNotification() {
        try { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) } catch (_: SecurityException) {}
    }

    // ---- TerminalSessionClient: forward to the UI, keep bookkeeping here ----
    override fun onTextChanged(s: TerminalSession) { uiClient?.onTextChanged(s) }
    override fun onTitleChanged(s: TerminalSession) { uiClient?.onTitleChanged(s) }
    override fun onSessionFinished(s: TerminalSession) {
        updateNotification()
        if (sessions.values.none { it.isRunning }) releaseWakeLock()
        uiClient?.onSessionFinished(s)
    }
    override fun onCopyTextToClipboard(s: TerminalSession, text: String) { uiClient?.onCopyTextToClipboard(s, text) }
    override fun onPasteTextFromClipboard(s: TerminalSession) { uiClient?.onPasteTextFromClipboard(s) }
    override fun onBell(s: TerminalSession) { uiClient?.onBell(s) }
    override fun onColorsChanged(s: TerminalSession) { uiClient?.onColorsChanged(s) }
    override fun onTerminalCursorStateChange(state: Boolean) { uiClient?.onTerminalCursorStateChange(state) }
    override fun getTerminalCursorStyle(): Int? = uiClient?.terminalCursorStyle
    override fun logError(tag: String, msg: String) { Log.e(tag, msg) }
    override fun logWarn(tag: String, msg: String) { Log.w(tag, msg) }
    override fun logInfo(tag: String, msg: String) { Log.i(tag, msg) }
    override fun logDebug(tag: String, msg: String) { Log.d(tag, msg) }
    override fun logVerbose(tag: String, msg: String) { Log.v(tag, msg) }
    override fun logStackTraceWithMessage(tag: String, msg: String, e: Exception) { Log.e(tag, msg, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }

    override fun onDestroy() {
        for (s in sessions.values) s.finishIfRunning()
        sessions.clear()
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "sessions"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP_ALL = "com.pocketagent.STOP_ALL"
        @Volatile var instance: TerminalService? = null
    }
}
