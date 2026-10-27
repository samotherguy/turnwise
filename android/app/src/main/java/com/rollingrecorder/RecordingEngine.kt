package com.rollingrecorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "RecordingEngine"
private const val CHUNK_MS = 30_000L
private const val READ_BYTES = SAMPLE_RATE / 5 * 2          // 200 ms of 16-bit mono
// Closing a chunk (encoder flush, muxer finalise, prune, fresh encoder) stalls reads for
// several hundred ms on a throttled CPU; the buffer must hold that or audio is dropped.
// ponytail: 5 s fixed; raise if logcat shows AudioRecord overruns on a slow device.
private const val CAPTURE_BUFFER_BYTES = SAMPLE_RATE * 2 * 5

/**
 * Owns the microphone. Reads PCM continuously, encodes it into 30-second chunks and prunes
 * chunks that fall outside the rolling window.
 *
 * While another app (a phone call, WhatsApp, Teams...) holds the microphone, Android silences
 * this app's stream instead of failing. The engine notices, stops writing, shows "paused" and
 * carries on by itself the moment the microphone is free again. If the microphone cannot be
 * opened at all, it retries with a growing delay.
 */
class RecordingEngine(
    private val context: Context,
    private val store: ChunkStore,
    private val settings: Settings,
) : Thread("rolling-recorder") {

    @Volatile private var running = true
    @Volatile private var silencedByAndroid = false
    private val flushRequests = ConcurrentLinkedQueue<CountDownLatch>()
    private var writer: ChunkWriter? = null

    /** Stops the thread and waits for the current chunk to be finalised. */
    fun stopAndWait() {
        running = false
        try { join(5_000) } catch (_: InterruptedException) {}
    }

    /**
     * Closes the chunk being written so it becomes readable. Recording does not stop:
     * the next audio goes into a fresh chunk straight away.
     */
    fun flush(timeoutMs: Long = 3_000): Boolean {
        if (!isAlive || !running) return false
        val latch = CountDownLatch(1)
        flushRequests.add(latch)
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun run() {
        store.cleanupPartials()
        store.prune(settings.windowMs, keepFromMs = settings.recordMark)
        var backoffMs = 1_000L
        while (running) {
            try {
                session()
                backoffMs = 1_000L
            } catch (t: Throwable) {
                Log.e(TAG, "recording session failed, will retry", t)
                closeChunk("session failed", abort = true)
                setStatus(Status.RETRYING)
                serveFlushRequests()
                sleepWhileRunning(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
        closeChunk("stopped")
        serveFlushRequests()
    }

    private fun session() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IOException("unsupported audio configuration ($minBuf)")

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, CAPTURE_BUFFER_BYTES)
        )
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }
                silencedByAndroid = mine?.isClientSilenced ?: false
            }
        }

        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("microphone could not be opened")
            }
            audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("microphone did not start")
            }
            silencedByAndroid = false

            val pcm = ByteArray(READ_BYTES)
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n < 0) throw IOException("AudioRecord.read returned $n")
                if (n > 0) {
                    // Only Android's own signal counts: a run of exact zeros is not evidence the mic
                    // was taken, this phone's noise gate emits digital silence in a quiet room.
                    if (silencedByAndroid) {
                        closeChunk("silenced by Android")
                        setStatus(Status.PAUSED)
                    } else {
                        setStatus(Status.RECORDING)
                        handleAudio(pcm, n)
                    }
                }
                serveFlushRequests()
            }
        } finally {
            try { audioManager.unregisterAudioRecordingCallback(callback) } catch (_: Throwable) {}
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
        }
    }

    private fun handleAudio(pcm: ByteArray, n: Int) {
        val w = writer ?: run {
            // The first byte of this read happened roughly (n / 2 / rate) seconds ago.
            val startMs = System.currentTimeMillis() - n / 2 * 1000L / SAMPLE_RATE
            ChunkWriter(store.dir, startMs).also { writer = it }
        }
        w.write(pcm, n)
        RecorderState.live.value = LiveChunk(w.startMs, w.levels())
        if (w.durationMs() >= CHUNK_MS) closeChunk("full")
    }

    private fun closeChunk(reason: String, abort: Boolean = false) {
        val w = writer ?: return
        writer = null
        if (w.durationMs() < CHUNK_MS) Log.w(TAG, "chunk closed early at ${w.durationMs()} ms: $reason")
        RecorderState.live.value = null
        try {
            if (abort) w.abort() else w.finish()
        } catch (t: Throwable) {
            Log.e(TAG, "closing chunk failed", t)
            w.abort()
        }
        store.prune(settings.windowMs, keepFromMs = settings.recordMark)
        RecorderState.chunkTick.value = RecorderState.chunkTick.value + 1
    }

    private fun serveFlushRequests() {
        if (flushRequests.isEmpty()) return
        closeChunk("flush")
        while (true) {
            val latch = flushRequests.poll() ?: break
            latch.countDown()
        }
    }

    private fun setStatus(s: Status) {
        if (RecorderState.status.value != s) RecorderState.status.value = s
    }

    private fun sleepWhileRunning(ms: Long) {
        var left = ms
        while (running && left > 0) {
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
            left -= 100
        }
    }

}
