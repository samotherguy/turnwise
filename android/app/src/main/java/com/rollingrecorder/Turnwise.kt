package com.rollingrecorder

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Uploads audio to the user's Turnwise server (reachable over Tailscale). */
object Turnwise {

    class Uploaded(val id: String, val duplicate: Boolean)

    /**
     * Streams the file to Turnwise. A re-send of the same audio returns the existing note (duplicate = true).
     * [onProgress] gets the fraction sent, from the uploading thread.
     */
    fun upload(
        context: Context, baseUrl: String, uri: Uri, name: String, createdMs: Long,
        title: String = "", folder: String = "", onProgress: (Float) -> Unit = {},
    ): Uploaded {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("http")) { "Set the Turnwise address in settings first" }
        val size = context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = URL("$base/api/upload?filename=${enc(name)}&created=${createdMs / 1000}&title=${enc(title)}&folder=${enc(folder)}")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 180_000
            c.setRequestProperty("Content-Type", context.contentResolver.getType(uri) ?: "audio/mp4")
            if (size > 0) c.setFixedLengthStreamingMode(size) else c.setChunkedStreamingMode(0)
            c.outputStream.use { os ->
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        sent += n
                        if (size > 0) onProgress(sent.toFloat() / size)
                    }
                }
            }
            if (c.responseCode !in 200..299) throw IOException("Turnwise answered ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            return Uploaded(o.getString("id"), o.optBoolean("duplicate"))
        } finally {
            c.disconnect()
        }
    }
}
