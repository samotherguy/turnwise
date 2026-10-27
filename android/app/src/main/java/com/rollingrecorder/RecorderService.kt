package com.rollingrecorder

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine

private const val TAG = "RecorderService"

/**
 * Foreground service that keeps the microphone engine alive. It only runs while the user has
 * switched recording on, and asks Android to restart it (START_STICKY) if the system kills it.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.rollingrecorder.START"
        const val ACTION_STOP = "com.rollingrecorder.STOP"
        const val ACTION_SEND_LAST = "com.rollingrecorder.SEND_LAST"
        private const val SEND_LAST_MS = 15 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    private var scope: CoroutineScope? = null
    private var startedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = Settings(this)

        if (intent?.action == ACTION_SEND_LAST) {
            sendLast(settings)
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            settings.enabled = false
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Android restarted us after killing the process, but the user had stopped recording.
        if (intent == null && !settings.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        settings.enabled = true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Notifications.postNeedsAttention(this, "Microphone permission is missing. Tap to grant it.")
            // startForegroundService() obliges us to call startForeground() soon.
            runCatching {
                ServiceCompat.startForeground(
                    this, Notifications.ID_RECORDING,
                    Notifications.recording(this, Status.RETRYING, settings.windowHours, 0L, 0L),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_RECORDING,
                Notifications.recording(this, Status.STOPPED, settings.windowHours, 0L, 0L),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (t: Throwable) {
            // Android 12+ refuses to start a microphone service from the background.
            // Recording stays switched on; opening the app (or tapping this) resumes it.
            Log.w(TAG, "could not enter foreground", t)
            Notifications.postNeedsAttention(this, "Android blocked recording from the background. Tap to resume.")
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.clearAttention(this)

        startEngineIfNeeded(settings)
        return START_STICKY
    }

    /** Export the last 15 minutes and upload them, reporting through a notification. */
    private fun sendLast(settings: Settings) {
        Notifications.postSend(this, "Sending the last 15 min…", "Cutting and uploading to Turnwise", inProgress = true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val r = ClipExporter.export(this@RecorderService, ChunkStore(this@RecorderService), now - SEND_LAST_MS, now)
                val up = Turnwise.upload(this@RecorderService, settings.turnwiseUrl, r.uri, r.name, now - SEND_LAST_MS)
                settings.setNoteId(r.id, up.id)
                Notifications.postSend(this@RecorderService, "Sent to Turnwise ✓", "${r.name} · tap to open", up.id)
            } catch (t: Throwable) {
                Log.w(TAG, "send last failed", t)
                Notifications.postSend(this@RecorderService, "Could not send", t.message ?: "Unknown error")
            }
        }
    }

    private fun startEngineIfNeeded(settings: Settings) {
        if (RecorderState.engine != null) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RollingRecorder:engine").apply {
            setReferenceCounted(false)
            acquire()
        }

        val engine = RecordingEngine(applicationContext, ChunkStore(this), settings)
        RecorderState.engine = engine
        startedAt = System.currentTimeMillis()
        RecorderState.status.value = Status.STARTING
        engine.start()

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            // Refresh on every status change and every closed chunk, so the buffered time stays honest.
            combine(RecorderState.status, RecorderState.chunkTick) { status, _ -> status }.collect { status ->
                RecorderWidget.update(this@RecorderService)
                val buffered = withContext(Dispatchers.IO) { ChunkStore(this@RecorderService).list().sumOf { it.durationMs } }
                try {
                    NotificationManagerCompat.from(this@RecorderService).notify(
                        Notifications.ID_RECORDING,
                        Notifications.recording(this@RecorderService, status, Settings(this@RecorderService).windowHours, startedAt, buffered)
                    )
                } catch (_: SecurityException) {
                }
            }
        }
    }

    private fun shutdown() {
        scope?.cancel()
        scope = null
        RecorderState.engine?.stopAndWait()
        RecorderState.engine = null
        RecorderState.status.value = Status.STOPPED
        RecorderWidget.update(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
