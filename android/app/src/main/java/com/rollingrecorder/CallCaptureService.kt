package com.rollingrecorder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings as SysSettings
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing by itself. Android only lets an app keep a microphone stream open during a phone call
 * when the app has an enabled accessibility service, so this service is the key that unlocks
 * [RecordingEngine]'s call capture. Experimental: what the stream contains depends on the phone
 * (Samsung mixes both sides of a cellular call; VoIP calls carry only this side unless on speaker).
 */
class CallCaptureService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        fun enabled(context: Context): Boolean {
            val me = "${context.packageName}/${CallCaptureService::class.java.name}"
            val list = SysSettings.Secure.getString(context.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
        fun openSettings(context: Context) =
            context.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
