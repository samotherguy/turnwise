package com.rollingrecorder

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The always-on phone that can wake (and shut down) the Turnwise box.
 * Direct-first: the app talks to the box itself and only asks the waker when Turnwise does not answer.
 * Blocking calls; every caller is already on an IO thread.
 */
object Waker {
    private const val WAKE_WAIT_MS = 150_000L

    @Volatile private var settings: Settings? = null
    fun init(context: Context) { if (settings == null) settings = Settings(context.applicationContext) }

    private val base get() = settings?.wakerUrl?.trim()?.trimEnd('/').orEmpty()
    val configured get() = base.startsWith("http")

    /** Banner text while a wake is in progress, null when idle. */
    var phase by mutableStateOf<String?>(null)
        private set

    /** True once the waker has confirmed the box is off; screens show a banner with a Wake button instead of waking it. */
    var serverOff by mutableStateOf(false)
        private set
    private val lock = Any()
    @Volatile private var cached: Pair<Long, Status>? = null

    data class Status(val server: Boolean, val llm: Boolean)

    private fun http(method: String, path: String, timeoutMs: Int = 5_000): String {
        if (!configured) throw IOException("Set the waker address in Settings")
        val c = URL("$base$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            if (method == "POST") { c.doOutput = true; c.outputStream.close() }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IOException("Waker answered $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    fun status(): Status = JSONObject(http("GET", "/status")).let { Status(it.optBoolean("server"), it.optBoolean("llm")) }
        .also { cached = System.currentTimeMillis() to it; serverOff = !it.server }

    /** Status at most 10 s old, so a screen retrying with backoff does not hammer the waker. Null if the waker is unreachable. */
    private fun statusCached(): Status? {
        cached?.let { (at, st) -> if (System.currentTimeMillis() - at < 10_000) return st }
        return runCatching { status() }.getOrNull()
    }

    fun wake(llm: Boolean = false) { http("POST", if (llm) "/wake/llm" else "/wake") }

    /** Null on success, otherwise the waker's own error text. */
    fun off(llm: Boolean = false): String? {
        val o = JSONObject(http("POST", if (llm) "/off/llm" else "/off", 30_000))
        if (o.optBoolean("ok")) {
            if (!llm) { serverOff = true; cached = null }
            return null
        }
        return o.optString("error").ifBlank { o.optString("output") }.ifBlank { "shutdown failed" }
    }

    /** Does Turnwise itself answer (not just the box's SSH)? Short timeouts: a cold box costs seconds, not 20 s. */
    private fun turnwiseUp(): Boolean = runCatching {
        val url = settings?.turnwiseUrl?.trim()?.trimEnd('/') ?: return false
        val c = URL("$url/api/me").openConnection() as HttpURLConnection
        try { c.connectTimeout = 4_000; c.readTimeout = 4_000; c.responseCode in 200..299 } finally { c.disconnect() }
    }.getOrDefault(false)

    /** Called after a read failed to connect: is it because the box is off? Never wakes anything. */
    fun isServerOff(): Boolean = configured && statusCached()?.server == false

    /** The user went somewhere that needs Turnwise: wake the box only if it is not answering. */
    fun wakeIfOff() { if (configured && !turnwiseUp()) wakeAndWait() }

    /** A request succeeded, so the box is clearly on. */
    fun noteUp() { serverOff = false }

    /**
     * Wake the box and wait until Turnwise answers (up to 150 s), showing the banner meanwhile.
     * Used by the Wake button and by explicit sends (uploads); reads never call this.
     * True when the box now answers. One wake at a time; concurrent callers wait on it.
     */
    fun wakeAndWait(): Boolean {
        if (!configured) return false
        synchronized(lock) {
            val st = runCatching { status() }.getOrNull() ?: return false   // waker unreachable: nothing we can do
            if (st.server && turnwiseUp()) { serverOff = false; return true }
            if (!st.server) runCatching { wake() }.onFailure { return false }
            phase = if (st.server) "Turnwise is starting…" else "Waking the server…"
            try {
                val deadline = System.currentTimeMillis() + WAKE_WAIT_MS
                var lastWake = System.currentTimeMillis()
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(3_000)
                    if (turnwiseUp()) { serverOff = false; return true }
                    if (System.currentTimeMillis() - lastWake > 30_000 && runCatching { status().server }.getOrDefault(true).not()) {
                        runCatching { wake() }; lastWake = System.currentTimeMillis()
                    }
                }
                return false
            } finally {
                phase = null
            }
        }
    }

}

/** A read failed because the box is off. Screens say so; the banner offers Wake. */
class ServerOff : IOException("Turnwise server is off")
