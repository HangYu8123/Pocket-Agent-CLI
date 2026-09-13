package com.pocketagent

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle

class App : Application() {
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        WakeWordService.ensureChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { if (started++ == 0) { inForeground = true; WakeWordService.onAppForeground(true) } }
            override fun onActivityStopped(a: Activity) { if (--started == 0) { inForeground = false; WakeWordService.onAppForeground(false) } }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                TerminalService.CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    companion object {
        /** True while one of this app's activities is visible (the wake-word detector pauses then). */
        @Volatile var inForeground = false
    }
}
