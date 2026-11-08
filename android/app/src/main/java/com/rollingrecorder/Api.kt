package com.rollingrecorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime

// ---------- models (shapes as served by audio-notes/server.py) ----------

data class NoteStatus(val state: String, val detail: String, val started: Double?) {
    val busy get() = state !in setOf("done", "error", "recording")
}

data class NoteSummary(
    val id: String, val title: String, val created: String, val folder: String, val duration: Double?,
    val status: NoteStatus, val speakers: List<String>, val summary: String, val snippet: String?,
)

data class Segment(val start: Double, val end: Double, val speaker: String, val text: String, val hinglish: String?, val english: String?)
data class ActionItem(val owner: String?, val item: String)

data class Note(
    val id: String, val title: String, val created: String, val folder: String, val duration: Double?,
    val status: NoteStatus, val warning: String?, val backend: String?, val summary: String,
    /** Diarization key → given name (null until someone names the voice). */
    val speakers: Map<String, String?>,
    val segments: List<Segment>, val actionItems: List<ActionItem>,
) {
    fun speakerName(key: String) = speakers[key] ?: spkLabel(key)
}

data class ChatSummary(val id: String, val title: String, val note: String?, val model: String, val updated: Double, val count: Int)
data class Source(
    val note: String, val title: String, val created: String, val turn: Int, val start: Double,
    val speaker: String, val hinglish: String, val english: String,
)
data class Message(
    val role: String, val text: String, val pending: Boolean, val detail: String, val error: String?,
    val model: String, val ts: Double, val sources: List<Source>,
)
data class Chat(val id: String, val title: String, val note: String?, val model: String, val messages: List<Message>) {
    val answering get() = messages.lastOrNull()?.pending == true
}
data class Me(val login: String, val name: String, val owner: Boolean)

fun spkLabel(k: String) = if (k.startsWith("SPEAKER_")) "Speaker ${(k.substringAfterLast('_').toIntOrNull() ?: 0) + 1}" else k

/** Note "created" is naive local ISO time (the server's clock), not UTC. */
fun parseCreated(iso: String): LocalDateTime? = runCatching { LocalDateTime.parse(iso) }.getOrNull()

/** Pure JSON → model functions, kept apart from the network so they can be unit-tested. */
object Parse {
    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k, "")
    private fun JSONObject.strOrNull(k: String) = if (!has(k) || isNull(k)) null else optString(k)
    private fun JSONObject.dbl(k: String) = if (!has(k) || isNull(k)) null else optDouble(k)
    private fun <T> JSONArray?.map(f: (JSONObject) -> T) = if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }
    private fun JSONArray?.strings() = if (this == null) emptyList() else List(length()) { optString(it) }

    fun status(o: JSONObject?) = NoteStatus(o?.str("state")?.ifEmpty { "queued" } ?: "queued", o?.str("detail") ?: "", o?.dbl("started"))

    fun summary(o: JSONObject) = NoteSummary(
        o.str("id"), o.str("title"), o.str("created"), o.str("folder"), o.dbl("duration"),
        status(o.optJSONObject("status")), o.optJSONArray("speakers").strings(), o.str("summary"), o.strOrNull("snippet"),
    )

    fun note(o: JSONObject): Note {
        // /api/notes/{id} merges the transcript over the summary: speakers is a dict once transcribed, a list before.
        val spk = o.optJSONObject("speakers")
        val speakers = spk?.keys()?.asSequence()?.associateWith { k -> if (spk.isNull(k)) null else spk.optString(k).ifBlank { null } } ?: emptyMap()
        return Note(
            o.str("id"), o.str("title"), o.str("created"), o.str("folder"), o.dbl("duration"),
            status(o.optJSONObject("status")), o.strOrNull("warning"), o.strOrNull("backend"), o.str("summary"), speakers,
            o.optJSONArray("segments").map { s ->
                Segment(s.optDouble("start"), s.optDouble("end"), s.str("speaker"), s.str("text"), s.strOrNull("hinglish"), s.strOrNull("english"))
            },
            o.optJSONArray("action_items").map { ActionItem(it.strOrNull("owner")?.ifBlank { null }, it.str("item")) },
        )
    }

    fun chatSummary(o: JSONObject) = ChatSummary(o.str("id"), o.str("title"), o.strOrNull("note"), o.str("model").ifEmpty { "local" }, o.optDouble("updated"), o.optInt("count"))

    fun chat(o: JSONObject) = Chat(
        o.str("id"), o.str("title"), o.strOrNull("note"), o.str("model").ifEmpty { "local" },
        o.optJSONArray("messages").map { m ->
            Message(
                m.str("role"), m.str("text"), m.optBoolean("pending"), m.str("detail"), m.strOrNull("error"),
                m.str("model").ifEmpty { "local" }, m.optDouble("ts"),
                m.optJSONArray("sources").map { s ->
                    Source(s.str("note"), s.str("title"), s.str("created"), s.optInt("turn"), s.optDouble("start"), s.str("speaker"), s.str("hinglish"), s.str("english"))
                },
            )
        },
    )
}

class ApiError(val code: Int, message: String) : IOException(message)

/** The Turnwise server, reached over Tailscale. No auth: Tailscale Serve stamps the login. */
object Api {
    lateinit var settings: Settings

    lateinit var context: android.content.Context
    val ready get() = ::context.isInitialized
    val base get() = settings.turnwiseUrl.trim().trimEnd('/')
    fun audioUrl(id: String) = if (LocalNotes.isLocal(id)) LocalNotes.audio(context, id)?.toURI().toString() ?: "" else "$base/api/notes/$id/audio"

    /** The box answered the list with an error while phone notes were still shown; the Notes tab says so. */
    @Volatile var serverListError: String? = null

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // 20 s cap like the web app: a stalled tunnel becomes a retry banner, never a hang.
    // A connection failure never wakes the box on its own (the recorder is used offline): it only asks the waker
    // whether the box is off, so screens can say so and offer a Wake button.
    private suspend fun call(method: String, path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        if (!base.startsWith("http")) throw IOException("Set the Turnwise address in Settings")
        try { once(method, path, body).also { Waker.noteUp() } } catch (e: IOException) { if (e !is ApiError && Waker.isServerOff()) throw ServerOff() else throw e }
    }

    private fun once(method: String, path: String, body: JSONObject?): String {
        val c = URL("$base/api$path").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val text = c.errorStream?.bufferedReader()?.readText().orEmpty()
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()?.ifBlank { null }
                throw ApiError(code, detail ?: text.ifBlank { "Turnwise answered $code" })
            }
            c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private suspend fun obj(method: String, path: String, body: JSONObject? = null) = JSONObject(call(method, path, body))
    private suspend fun arr(path: String) = JSONArray(call("GET", path))
    private fun <T> JSONArray.list(f: (JSONObject) -> T) = List(length()) { f(getJSONObject(it)) }

    /** Phone notes and box notes in one list, newest first. With phone notes present, a box that is off is a line, not a failure. */
    suspend fun notes(): List<NoteSummary> {
        val local = withContext(Dispatchers.IO) { LocalNotes.list(context).map(Parse::summary) }
        val remote = try { arr("/notes").list(Parse::summary).also { serverListError = null } } catch (t: Throwable) {
            if (local.isEmpty()) throw t
            serverListError = errorText(t); emptyList()
        }
        return (local + remote).sortedByDescending { it.created }
    }
    suspend fun search(q: String): List<NoteSummary> {
        val local = withContext(Dispatchers.IO) { LocalNotes.list(context).map(Parse::summary).filter { n -> runCatching { n.title.contains(q, true) || LocalNotes.transcript(context, n.id)?.toString()?.contains(q, true) == true }.getOrDefault(false) } }
        val remote = try { arr("/search?q=${enc(q)}").list(Parse::summary) } catch (t: Throwable) { if (local.isEmpty()) throw t; emptyList() }
        return (local + remote).sortedByDescending { it.created }
    }
    suspend fun note(id: String) = if (LocalNotes.isLocal(id)) withContext(Dispatchers.IO) { Parse.note(LocalNotes.note(context, id)) } else Parse.note(obj("GET", "/notes/$id"))
    suspend fun patchNote(id: String, body: JSONObject) = if (LocalNotes.isLocal(id)) withContext(Dispatchers.IO) { Parse.note(LocalNotes.patch(context, id, body)) } else Parse.note(obj("PATCH", "/notes/$id", body))
    suspend fun markdown(id: String) = if (LocalNotes.isLocal(id)) withContext(Dispatchers.IO) { LocalNotes.markdown(context, id) } else call("GET", "/notes/$id/md")
    suspend fun retranscribe(id: String) { if (LocalNotes.isLocal(id)) Ingest.redoOnPhone(context, id) else call("POST", "/notes/$id/retranscribe", JSONObject()) }
    suspend fun deleteNote(id: String) { if (LocalNotes.isLocal(id)) withContext(Dispatchers.IO) { LocalNotes.delete(context, id) } else call("DELETE", "/notes/$id") }
    suspend fun folders(): Map<String, Int> = obj("GET", "/folders").let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }

    suspend fun me() = obj("GET", "/me").let { Me(it.optString("login"), it.optString("name"), it.optBoolean("owner")) }
    suspend fun settings() = obj("GET", "/settings")
    suspend fun putSettings(body: JSONObject) = obj("PUT", "/settings", body)
    suspend fun speakers(): Map<String, Int> = obj("GET", "/speakers").let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }
    suspend fun forgetSpeaker(name: String) { call("DELETE", "/speakers/${enc(name).replace("+", "%20")}") }

    suspend fun chats(note: String? = null): List<ChatSummary> {
        if (note != null && LocalNotes.isLocal(note)) return withContext(Dispatchers.IO) { LocalNotes.chats(context, note).map(Parse::chatSummary) }
        val local = if (note == null) withContext(Dispatchers.IO) { LocalNotes.chats(context, null).map(Parse::chatSummary) } else emptyList()
        val remote = try { arr("/chats" + (note?.let { "?note=${enc(it)}" } ?: "")).list(Parse::chatSummary) } catch (t: Throwable) { if (local.isEmpty()) throw t; emptyList() }
        return (local + remote).sortedByDescending { it.updated }
    }
    suspend fun newChat(note: String?, model: String? = null) =
        if (note != null && LocalNotes.isLocal(note)) withContext(Dispatchers.IO) { Parse.chat(LocalNotes.newChat(context, note)) }
        else Parse.chat(obj("POST", "/chats", JSONObject().apply { if (note != null) put("note", note); if (model != null) put("model", model) }))
    suspend fun chat(sid: String) = if (LocalNotes.isLocalChat(sid)) withContext(Dispatchers.IO) { Parse.chat(LocalNotes.chat(context, sid) ?: throw ApiError(404, "chat gone")) } else Parse.chat(obj("GET", "/chats/$sid"))
    suspend fun ask(sid: String, text: String, model: String): Chat {
        if (!LocalNotes.isLocalChat(sid)) return Parse.chat(obj("POST", "/chats/$sid/ask", JSONObject().put("text", text).put("model", model)))
        return withContext(Dispatchers.IO) {
            val c = LocalNotes.chat(context, sid) ?: throw ApiError(404, "chat gone")
            val msgs = c.getJSONArray("messages"); val now = System.currentTimeMillis() / 1000.0
            msgs.put(JSONObject().put("role", "user").put("text", text).put("ts", now).put("model", "phone"))
            try {
                val (answer, sources) = PhonePipeline.ask(context, c.optString("note"), text)
                msgs.put(JSONObject().put("role", "assistant").put("text", answer).put("sources", sources).put("ts", System.currentTimeMillis() / 1000.0).put("model", "phone"))
            } catch (t: Throwable) {
                msgs.put(JSONObject().put("role", "assistant").put("text", "").put("error", errorText(t)).put("ts", now).put("model", "phone"))
            }
            if (TranscribeService.current == null) LlamaServer.release()
            LocalNotes.saveChat(context, c); Parse.chat(c)
        }
    }
    suspend fun clearChat(sid: String) { if (LocalNotes.isLocalChat(sid)) withContext(Dispatchers.IO) { LocalNotes.chat(context, sid)?.let { LocalNotes.saveChat(context, it.put("messages", JSONArray())) } } else call("POST", "/chats/$sid/clear", JSONObject()) }
    suspend fun deleteChat(sid: String) { if (LocalNotes.isLocalChat(sid)) withContext(Dispatchers.IO) { LocalNotes.deleteChat(context, sid) } else call("DELETE", "/chats/$sid") }
}
