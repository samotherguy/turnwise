package com.rollingrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_ALERT = "attention"
    const val ID_RECORDING = 1
    const val ID_ATTENTION = 2
    const val ID_SEND = 3

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Needs attention", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun serviceIntent(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context, code,
            Intent(context, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun recording(context: Context, status: Status, windowHours: Int, startedAt: Long, bufferedMs: Long): Notification {
        val title = when (status) {
            Status.RECORDING -> "Listening"
            Status.PAUSED -> "Paused · another app has the mic"
            Status.RETRYING -> "Waiting for the microphone"
            Status.STOPPED, Status.STARTING -> "Starting…"
        }
        val h = bufferedMs / 3_600_000; val m = bufferedMs / 60_000 % 60
        val buffered = if (h > 0) "${h}h ${m}m" else "${m}m"
        val text = "$buffered ready to send · keeps the last $windowHours h"
        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_wave)
            .setColor(0xFF1DB954.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(startedAt)
            .setShowWhen(startedAt > 0)
            .setUsesChronometer(status == Status.RECORDING && startedAt > 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Send last 15 min", serviceIntent(context, RecorderService.ACTION_SEND_LAST, 2))
            .addAction(0, "Stop", serviceIntent(context, RecorderService.ACTION_STOP, 1))
            .build()
    }

    /** Progress and result of a "send last N minutes" started from the notification. */
    fun postSend(context: Context, title: String, text: String, noteId: String? = null, inProgress: Boolean = false) {
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_wave)
            .setColor(0xFF1DB954.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(!inProgress)
            .setOngoing(inProgress)
        if (inProgress) b.setProgress(0, 0, true)
        if (noteId != null) b.setContentIntent(
            PendingIntent.getActivity(
                context, 3,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_NOTE, noteId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        try { NotificationManagerCompat.from(context).notify(ID_SEND, b.build()) } catch (_: SecurityException) {}
    }

    /** Tells the user recording is switched on but not running, and a tap puts it right. */
    fun postNeedsAttention(context: Context, text: String) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Recording is not running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_ATTENTION, n)
        } catch (_: SecurityException) {
            // Notification permission not granted; nothing more we can do.
        }
    }

    fun clearAttention(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_ATTENTION)
    }
}
