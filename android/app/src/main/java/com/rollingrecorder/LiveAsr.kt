package com.rollingrecorder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.sqrt

/**
 * Live transcription on the phone. While on, the bundled llama.cpp server holds Qwen3-ASR on the GPU;
 * the recorder's audio is cut at pauses into short windows, each window is sent to that server and the
 * text comes back as a line. Off by default, stops with the screen: nothing runs in the background. The
 * final transcript still comes from the box or the phone pipeline; this is a draft to read along.
 */
object LiveAsr {
    private const val TAG = "LiveAsr"

    enum class Phase { OFF, DOWNLOADING, LOADING, ON, ERROR }
    data class Line(val atMs: Long, val text: String)
    data class State(val phase: Phase = Phase.OFF, val detail: String = "", val progress: Float = 0f, val lines: List<Line> = emptyList(), val pending: Boolean = false)

    val state = MutableStateFlow(State())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var windows = Channel<Pair<Long, ByteArray>>(capacity = 6)

    fun start(context: Context) {
        if (job != null) return
        val app = context.applicationContext
        windows = Channel(capacity = 6)
        job = scope.launch {
            try { run(app) }
            catch (_: CancellationException) {}
            catch (t: Throwable) { Log.w(TAG, "live failed", t); state.value = state.value.copy(phase = Phase.ERROR, detail = t.message ?: "failed", pending = false) }
            finally { teardown() }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        teardown()
        if (state.value.phase != Phase.ERROR) state.value = state.value.copy(phase = Phase.OFF, detail = "", pending = false)
    }

    private fun teardown() {
        RecorderState.liveSink = null
        if (TranscribeService.current == null) LlamaServer.release()   // a running note job keeps its own model
        windows.close()
    }

    private suspend fun run(context: Context) {
        for (spec in Models.LIVE) {
            state.value = state.value.copy(phase = Phase.DOWNLOADING, detail = "Downloading ${spec.label} (once)", progress = 0f)
            val watch = scope.launch { Models.progress.collect { p -> if (p != null) state.value = state.value.copy(progress = p.done.toFloat() / p.size, detail = "Downloading ${spec.label} (once) · ${p.done shr 20} of ${p.size shr 20} MB") } }
            try { Models.download(context, spec) { job?.isActive == true } } finally { watch.cancel() }
        }

        state.value = state.value.copy(phase = Phase.LOADING, detail = "Loading the model on the GPU…", progress = 0f)
        if (TranscribeService.current != null) throw IOException("A note is being transcribed on this phone. Live can start when it is done.")
        val (asrSpec, asrProj) = Models.asrFor(context)
        val server = LlamaServer.acquire(context, asrSpec, asrProj, ctx = 1024)

        state.value = state.value.copy(phase = Phase.ON, detail = "", pending = false)
        val cutter = Cutter { at, pcm -> windows.trySend(at to pcm) }   // a full queue drops the window rather than stalling the recorder
        RecorderState.liveSink = cutter::feed
        for ((at, pcm) in windows) {
            state.value = state.value.copy(pending = true)
            // Speech is ~3 words a second; a cap tied to the window length stops a decoding loop early.
            val cap = (pcm.size / 2 / SAMPLE_RATE * 8 + 16).coerceIn(24, 160)
            val text = runCatching { clean(server.transcribe(pcm, cap)) }.getOrElse { Log.w(TAG, "window failed", it); "" }
            if (text.isNotBlank()) state.value = state.value.let { it.copy(lines = (it.lines + Line(at, text)).takeLast(80), pending = false) }
            else state.value = state.value.copy(pending = false)
        }
    }

    /** A silent window gives nothing or echoes the prompt; a loop is collapsed; the script is normalised. */
    private fun clean(t: String): String {
        if (t.equals("Transcribe this audio.", ignoreCase = true) || t.none { it.isLetterOrDigit() }) return ""
        return Hinglish.latin(PhonePipeline.collapseRepeats(t))
    }

    /**
     * Cuts the recorder's PCM stream into windows at pauses: a window closes after a 0.6 s pause once it
     * holds 3 s, or at 12 s regardless. Windows with no speech are dropped. Runs on the recorder thread.
     */
    private class Cutter(private val emit: (Long, ByteArray) -> Unit) {
        private val buf = ByteArrayOutputStream()
        private var startMs = 0L
        private var floor = 400.0           // noise floor, tracked as a slow minimum of frame RMS
        private var silentMs = 0
        private var speechMs = 0
        private var frameAcc = 0.0; private var frameN = 0

        fun feed(pcm: ByteArray, n: Int) {
            if (buf.size() == 0) { startMs = System.currentTimeMillis() - n / 2 * 1000L / SAMPLE_RATE; silentMs = 0; speechMs = 0 }
            buf.write(pcm, 0, n)
            var i = 0
            while (i + 1 < n) {
                val s = (pcm[i].toInt() or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
                frameAcc += s * s; frameN++
                if (frameN == SAMPLE_RATE / 10) {                      // one 100 ms frame
                    val rms = sqrt(frameAcc / frameN); frameAcc = 0.0; frameN = 0
                    floor = if (rms < floor) rms else floor * 0.995 + rms * 0.005
                    if (rms > maxOf(floor * 3, 250.0)) { speechMs += 100; silentMs = 0 } else silentMs += 100
                }
                i += 2
            }
            val ms = buf.size() / 2 * 1000L / SAMPLE_RATE
            if ((ms >= 3_000 && silentMs >= 600 && speechMs >= 300) || ms >= 12_000) {
                if (speechMs >= 300) emit(startMs, buf.toByteArray())
                buf.reset(); silentMs = 0; speechMs = 0
            }
        }
    }
}
