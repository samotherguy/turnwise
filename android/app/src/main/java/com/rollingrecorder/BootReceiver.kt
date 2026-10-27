package com.rollingrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or an app update Android has stopped the service. Android 14+ does not allow a
 * microphone service to be started from the background, so if the user had recording switched
 * on we post a notification; one tap on it reopens the app, which resumes recording.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Settings(context).enabled) return
        Notifications.postNeedsAttention(context, "Tap to resume listening.")
    }
}
