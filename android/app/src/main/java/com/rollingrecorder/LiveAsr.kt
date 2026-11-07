package com.rollingrecorder

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Live transcription on the phone. While on, llama.cpp's server runs as a child process with Qwen3-ASR
 * on the GPU; the recorder's audio is cut at pauses into short windows, each window is sent to that
 * server and the text comes back as a line. Off by default, stops with the screen: nothing runs in the
 * background. The final transcript still comes from the box; this is a draft to read along.
 */
object LiveAsr {
    private const val TAG = "LiveAsr"
    private const val PORT = 8089
    private val FILES = listOf("Qwen3-ASR-1.7B-Q8_0.gguf" to 2_165_034_944L, "mmproj-Qwen3-ASR-1.7B-Q8_0.gguf" to 355_709_344L)

    enum class Phase { OFF, DOWNLOADING, LOADING, ON, ERROR }
    data class Line(val atMs: Long, val text: String)
    data class State(val phase: Phase = Phase.OFF, val detail: String = "", val progress: Float = 0f, val lines: List<Line> = emptyList(), val pending: Boolean = false)

    val state = MutableStateFlow(State())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var process: Process? = null
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
        process?.destroy(); process = null
        windows.close()
    }

    private suspend fun run(context: Context) {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        for ((name, size) in FILES) download(context, dir, name, size)

        state.value = state.value.copy(phase = Phase.LOADING, detail = "Loading the model on the GPU…", progress = 0f)
        val lib = context.applicationInfo.nativeLibraryDir
        val log = File(context.cacheDir, "llama-server.log")
        val pb = ProcessBuilder(
            "$lib/libllamaserver.so", "-m", File(dir, FILES[0].first).path, "--mmproj", File(dir, FILES[1].first).path,
            "-ngl", "99", "--host", "127.0.0.1", "--port", "$PORT", "-t", "4", "-c", "1024", "-np", "1", "--no-warmup",
        ).redirectErrorStream(true).redirectOutput(log)
        pb.environment()["LD_LIBRARY_PATH"] = "$lib:/vendor/lib64:/system/lib64"
        process = pb.start()
        val deadline = System.currentTimeMillis() + 90_000
        while (!healthy()) {
            if (process?.isAlive != true) throw IOException("model server exited: " + log.readText().lines().lastOrNull { it.isNotBlank() }?.take(160))
            if (System.currentTimeMillis() > deadline) throw IOException("model server did not start in time")
            delay(1_000)
        }

        state.value = state.value.copy(phase = Phase.ON, detail = "", pending = false)
        val cutter = Cutter { at, pcm -> windows.trySend(at to pcm) }   // a full queue drops the window rather than stalling the recorder
        RecorderState.liveSink = cutter::feed
        for ((at, pcm) in windows) {
            state.value = state.value.copy(pending = true)
            val text = runCatching { transcribe(pcm) }.getOrElse { Log.w(TAG, "window failed", it); "" }
            if (text.isNotBlank()) state.value = state.value.let { it.copy(lines = (it.lines + Line(at, text)).takeLast(80), pending = false) }
            else state.value = state.value.copy(pending = false)
        }
    }

    private fun healthy(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
        try { c.connectTimeout = 800; c.readTimeout = 800; c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    /** Fetches a model file from the box (resumable), waking the box if it is off. */
    private suspend fun download(context: Context, dir: File, name: String, size: Long) {
        val out = File(dir, name)
        if (out.length() == size) return
        state.value = state.value.copy(phase = Phase.DOWNLOADING, detail = "Downloading $name (once)", progress = 0f)
        Waker.wakeIfOff()
        val part = File(dir, "$name.part"); val have = part.length()
        val c = URL("${Settings(context).turnwiseUrl}/models/$name").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 60_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            if (c.responseCode !in 200..299) throw IOException("box answered ${c.responseCode} for $name")
            val append = c.responseCode == 206
            c.inputStream.use { input -> java.io.FileOutputStream(part, append).use { os ->
                val buf = ByteArray(256 * 1024); var done = if (append) have else 0L; var last = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    os.write(buf, 0, n); done += n
                    if (done - last > 4 shl 20) { last = done; state.value = state.value.copy(progress = done.toFloat() / size, detail = "Downloading $name (once) · ${done shr 20} of ${size shr 20} MB") }
                    if (job?.isActive != true) throw CancellationException()
                }
            } }
        } finally { c.disconnect() }
        if (part.length() != size) throw IOException("$name is ${part.length()} bytes, expected $size")
        if (!part.renameTo(out)) throw IOException("could not finish $name")
    }

    private fun transcribe(pcm: ByteArray): String {
        val wav = ByteArrayOutputStream(pcm.size + 44).apply { write(wavHeader(pcm.size)); write(pcm) }.toByteArray()
        val body = JSONObject().put("temperature", 0).put("max_tokens", 160).put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav")))
                .put(JSONObject().put("type", "text").put("text", "Transcribe this audio.")))))
        val c = URL("http://127.0.0.1:$PORT/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5_000; c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) throw IOException("model server answered ${c.responseCode}")
            val text = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            return clean(text)
        } finally { c.disconnect() }
    }

    /** The model prefixes its language guess; a silent window gives only that, or echoes the prompt. */
    private fun clean(raw: String): String {
        val t = raw.replace(Regex("^\\s*language\\s+\\w+\\s*<asr_text>"), "").trim()
        if (t.equals("Transcribe this audio.", ignoreCase = true) || t.none { it.isLetterOrDigit() }) return ""
        return t
    }

    private fun wavHeader(dataLen: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen)
        return b.array()
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
