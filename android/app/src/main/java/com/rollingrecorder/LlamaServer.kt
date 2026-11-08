package com.rollingrecorder

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * llama.cpp's server as a child process of the app, with one model on the GPU. The ASR model and the
 * text model do not fit together, so [acquire] stops whatever else is running before starting the one asked for.
 */
class LlamaServer private constructor(private val context: Context, val spec: Models.Spec, private val mmproj: Models.Spec?, private val ctx: Int) {
    private val port = 8089
    private var process: Process? = null
    val log = File(context.cacheDir, "llama-server.log")

    private suspend fun start() {
        val lib = context.applicationInfo.nativeLibraryDir
        val dir = Models.dir(context)
        val args = mutableListOf("$lib/libllamaserver.so", "-m", File(dir, spec.name).path, "-ngl", "99", "--host", "127.0.0.1", "--port", "$port", "-t", "4", "-c", "$ctx", "-np", "1", "--no-warmup")
        if (mmproj != null) args += listOf("--mmproj", File(dir, mmproj.name).path)
        val pb = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log)
        pb.environment()["LD_LIBRARY_PATH"] = "$lib:/vendor/lib64:/system/lib64"
        process = pb.start()
        val deadline = System.currentTimeMillis() + 120_000
        while (!healthy()) {
            if (process?.isAlive != true) throw IOException("model server exited: " + log.readText().lines().lastOrNull { it.isNotBlank() }?.take(160))
            if (System.currentTimeMillis() > deadline) throw IOException("model server did not start in time")
            delay(1_000)
        }
    }

    private fun healthy(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        try { c.connectTimeout = 800; c.readTimeout = 800; c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    fun stop() { process?.destroy(); process = null }
    val alive get() = process?.isAlive == true

    /** Speech to text for one window of 16 kHz PCM; the model's language prefix is stripped and loops collapsed. */
    fun transcribe(pcm: ByteArray, maxTokens: Int): String {
        val wav = ByteArrayOutputStream(pcm.size + 44).apply { write(wavHeader(pcm.size)); write(pcm) }.toByteArray()
        val content = JSONArray()
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav")))
            .put(JSONObject().put("type", "text").put("text", "Transcribe this audio."))
        val body = JSONObject().put("temperature", 0).put("max_tokens", maxTokens).put("repeat_penalty", 1.1).put("repeat_last_n", 48)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        return post(body, 120_000).replace(Regex("^\\s*language\\s+\\w+\\s*<asr_text>"), "").trim()
    }

    /** A chat completion, optionally constrained to a JSON schema. */
    fun chat(system: String, user: String, schema: JSONObject? = null, maxTokens: Int = 600, temperature: Double = 0.2): String {
        val body = JSONObject().put("temperature", temperature).put("max_tokens", maxTokens)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        if (schema != null) body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject().put("name", "out").put("schema", schema)))
        return post(body, 900_000)
    }

    private fun post(body: JSONObject, readTimeout: Int): String {
        val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5_000; c.readTimeout = readTimeout
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) throw IOException("model server answered ${c.responseCode}: " + (c.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""))
            return JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally { c.disconnect() }
    }

    companion object {
        private const val TAG = "LlamaServer"
        private var current: LlamaServer? = null
        private val lock = Any()

        /** The server for [spec], starting it (and stopping any other) if needed. Callers must not keep it across a release. */
        suspend fun acquire(context: Context, spec: Models.Spec, mmproj: Models.Spec? = null, ctx: Int = 1024): LlamaServer {
            val cur = current
            if (cur != null && cur.spec == spec && cur.alive) return cur
            cur?.stop()
            val s = LlamaServer(context.applicationContext, spec, mmproj, ctx)
            synchronized(lock) { current = s }
            try { s.start() } catch (t: Throwable) { s.stop(); synchronized(lock) { if (current === s) current = null }; throw t }
            Log.i(TAG, "started ${spec.name}")
            return s
        }

        fun release() { synchronized(lock) { current?.stop(); current = null } }

        fun wavHeader(dataLen: Int): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            b.putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(dataLen)
            return b.array()
        }
    }
}
