package com.rollingrecorder

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs the on-device pipeline for queued phone notes, one at a time, as a visible foreground job. Live
 * transcription is stopped first: the GPU holds one model.
 */
class TranscribeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_ID)?.let { if (it !in queue) queue.add(it) }
        if (intent?.action == ACTION_CANCEL) { job?.cancel(); queue.clear() }
        Notifications.ensureChannels(this)
        if (!Api.ready) { Api.settings = Settings(this); Api.context = applicationContext }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_TRANSCRIBE, notification("Transcribing on this phone", "Starting…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (t: Throwable) { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive != true) job = scope.launch {
            LiveAsr.stop()
            while (true) {
                val id = queue.poll() ?: break
                current = id
                val title = runCatching { LocalNotes.meta(this@TranscribeService, id).optString("title") }.getOrDefault("Recording")
                PhonePipeline.run(this@TranscribeService, id, { isActive }) { detail ->
                    NotificationManagerCompat.from(this@TranscribeService).notify(Notifications.ID_TRANSCRIBE, notification(title, detail, queue.size))
                    Bus.notesVersion++
                }
                val st = runCatching { LocalNotes.meta(this@TranscribeService, id).optJSONObject("status")?.optString("state") }.getOrNull()
                Notifications.postSend(this@TranscribeService, if (st == "done") "Note ready" else "Could not transcribe", title, id)
                Bus.notesVersion++
            }
            current = null
            LlamaServer.release()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, text: String, queued: Int): Notification {
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 8, Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, Notifications.CHANNEL_SEND)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title)
            .setContentText(text + if (queued > 0) " · $queued more queued" else "").setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, "Cancel", cancel).build()
    }

    override fun onDestroy() { job?.cancel(); LlamaServer.release(); super.onDestroy() }

    companion object {
        private const val EXTRA_ID = "id"
        private const val ACTION_CANCEL = "com.rollingrecorder.TRANSCRIBE_CANCEL"
        val queue = ConcurrentLinkedQueue<String>()
        @Volatile var current: String? = null

        fun enqueue(context: Context, id: String) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, TranscribeService::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
