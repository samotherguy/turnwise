package com.rollingrecorder

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/**
 * The Cardputer recorder on the local network: a second Listen source. It keeps a rolling buffer of
 * 5-minute WAV chunks; the phone pulls the chunks it wants, stitches them into one WAV and sends that
 * through the normal Turnwise upload (which also wakes the box). Found over mDNS (_turnwise._tcp).
 */
object Cardputer {
    const val SERVICE = "_turnwise._tcp."
    private val NAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    data class Status(
        val recording: Boolean, val chunk: String, val seconds: Int, val chunks: Int, val battery: Int,
        val sdFree: Long, val sdTotal: Long, val time: Long, val uptime: Long, val ip: String, val overruns: Int,
    )

    data class Chunk(val name: String, val size: Long, val kept: Boolean, val seconds: Int) {
        /** Start of the chunk in wall-clock ms, when the device had a clock when it was written. */
        val startMs: Long? get() = runCatching {
            LocalDateTime.parse(name.removeSuffix(".wav"), NAME_FMT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun get(base: String, path: String, timeout: Int = 4_000): String {
        val c = URL("$base$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout; c.readTimeout = timeout
            if (c.responseCode !in 200..299) throw IOException("Cardputer answered ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun post(base: String, path: String): String {
        val c = URL("$base$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 4_000; c.readTimeout = 8_000; c.doOutput = true; c.outputStream.close()
            if (c.responseCode !in 200..299) throw IOException("Cardputer answered ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    suspend fun status(base: String): Status = withContext(Dispatchers.IO) {
        val o = JSONObject(get(base, "/api/status"))
        Status(
            o.optBoolean("recording"), o.optString("chunk"), o.optInt("seconds"), o.optInt("chunks"), o.optInt("battery"),
            o.optLong("sdFree"), o.optLong("sdTotal"), o.optLong("time"), o.optLong("uptime"), o.optString("ip"), o.optInt("overruns"),
        )
    }

    suspend fun chunks(base: String): List<Chunk> = withContext(Dispatchers.IO) {
        val a = JSONArray(get(base, "/api/chunks"))
        List(a.length()) { a.getJSONObject(it).let { o -> Chunk(o.getString("name"), o.optLong("size"), o.optBoolean("kept"), o.optInt("seconds")) } }
    }

    suspend fun setTime(base: String) = withContext(Dispatchers.IO) { post(base, "/api/time?epoch=${System.currentTimeMillis() / 1000}") }
    suspend fun rotate(base: String) = withContext(Dispatchers.IO) { post(base, "/api/rotate") }

    /** Downloads one chunk to the cache, reporting bytes so far. */
    private fun download(base: String, chunk: Chunk, dir: File, onProgress: (Long) -> Unit): File {
        val out = File(dir, chunk.name)
        val c = URL("$base/api/chunk?name=${URLEncoder.encode(chunk.name, "UTF-8")}").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000; c.readTimeout = 30_000
            if (c.responseCode !in 200..299) throw IOException("Cardputer answered ${c.responseCode} for ${chunk.name}")
            c.inputStream.use { input -> out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; os.write(buf, 0, n); total += n; onProgress(total) }
            } }
        } finally { c.disconnect() }
        return out
    }

    /** Joins same-format WAV files into one WAV: the data of each, in order, under a fresh header. */
    private fun stitch(parts: List<File>, out: File) {
        require(parts.isNotEmpty())
        val fmt = ByteArray(44).also { RandomAccessFile(parts[0], "r").use { f -> f.readFully(it) } }
        var dataBytes = 0L
        out.outputStream().buffered().use { os ->
            os.write(fmt)                                   // placeholder; sizes patched below
            for (p in parts) {
                val len = p.length() - 44
                if (len <= 0) continue
                p.inputStream().use { input -> input.skip(44); input.copyTo(os); dataBytes += len }
            }
        }
        RandomAccessFile(out, "rw").use { f ->
            f.seek(4); f.write(le32(36 + dataBytes))
            f.seek(40); f.write(le32(dataBytes))
        }
    }
    private fun le32(v: Long) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    /**
     * Pulls the newest chunks covering [minutes] (the one being written included, rotated first so it is complete),
     * stitches them and uploads the result. Returns the new note.
     */
    suspend fun sendLast(context: Context, base: String, minutes: Int, onProgress: (String, Float) -> Unit): Turnwise.Uploaded = withContext(Dispatchers.IO) {
        rotate(base); Thread.sleep(800)
        val all = chunks(base).sortedBy { it.name }
        val picked = ArrayList<Chunk>(); var covered = 0
        for (c in all.asReversed()) { if (covered >= minutes * 60) break; if (c.seconds <= 0) continue; picked.add(0, c); covered += c.seconds }
        if (picked.isEmpty()) throw IOException("Nothing recorded yet on the Cardputer")
        val dir = File(context.cacheDir, "cardputer").apply { mkdirs() }
        val totalBytes = picked.sumOf { it.size }.coerceAtLeast(1); var done = 0L
        val files = picked.map { c ->
            val f = download(base, c, dir) { got -> onProgress("Pulling ${c.name}", (done + got).toFloat() / totalBytes * 0.7f) }
            done += c.size; f
        }
        val stamp = picked.first().startMs ?: (System.currentTimeMillis() - covered * 1000L)
        val name = "cardputer-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(stamp), ZoneId.systemDefault())) + ".wav"
        val out = File(dir, name)
        onProgress("Stitching ${files.size} chunk(s)", 0.72f)
        stitch(files, out)
        files.forEach { it.delete() }
        onProgress("Sending to Turnwise", 0.75f)
        val settings = Settings(context)
        val up = Ingest.submit(context, Uri.fromFile(out), name, stamp) { p -> onProgress("Sending to Turnwise", 0.75f + 0.25f * p) }
        out.delete()
        up
    }

    /** Finds the device over mDNS and returns its base URL, or null. Cached in settings by the caller. */
    suspend fun discover(context: Context, timeoutMs: Long = 6_000): String? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                lateinit var listener: NsdManager.DiscoveryListener
                listener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(t: String, e: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onStopDiscoveryFailed(t: String, e: Int) {}
                    override fun onDiscoveryStarted(t: String) {}
                    override fun onDiscoveryStopped(t: String) {}
                    override fun onServiceLost(s: NsdServiceInfo) {}
                    override fun onServiceFound(s: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo, e: Int) {}
                            override fun onServiceResolved(si: NsdServiceInfo) {
                                val host = si.host?.hostAddress ?: return
                                runCatching { nsd.stopServiceDiscovery(listener) }
                                if (cont.isActive) cont.resume("http://${if (host.contains(':')) "[$host]" else host}:${si.port}")
                            }
                        })
                    }
                }
                nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
            }
        }
    }
}
