package com.rollingrecorder

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/**
 * Notes made on the phone, stored in the server's own layout so every screen renders them through the same
 * parser: `notes/<id>/meta.json`, `transcript.json`, the audio, and `chats.json` for Ask. Ids start with
 * "phone-", which is how [Api] routes calls here instead of to the box.
 */
object LocalNotes {
    const val PREFIX = "phone-"
    private val ID_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    fun isLocal(id: String) = id.startsWith(PREFIX)
    fun root(context: Context): File = File(context.filesDir, "notes").apply { mkdirs() }
    fun dir(context: Context, id: String): File = File(root(context), id)
    fun audio(context: Context, id: String): File? = dir(context, id).listFiles()?.firstOrNull { it.nameWithoutExtension == "audio" }

    /** Copies the recording in and writes a queued note. */
    fun create(context: Context, uri: Uri, name: String, createdMs: Long, title: String, folder: String): String {
        val created = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(createdMs), ZoneId.systemDefault())
        var id = PREFIX + ID_FMT.format(created); var k = 1
        while (dir(context, id).exists()) id = PREFIX + ID_FMT.format(created) + "-" + (++k)
        val d = dir(context, id).apply { mkdirs() }
        val ext = name.substringAfterLast('.', "m4a").lowercase().take(5)
        context.contentResolver.openInputStream(uri)!!.use { input -> File(d, "audio.$ext").outputStream().use { input.copyTo(it) } }
        write(d, "meta.json", JSONObject()
            .put("title", title.ifBlank { "Recording ${created.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}" }).put("title_auto", title.isBlank())
            .put("folder", folder).put("created", created.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).put("origin", "phone")
            .put("status", JSONObject().put("state", "queued").put("detail", "")))
        return id
    }

    fun meta(context: Context, id: String): JSONObject = read(dir(context, id), "meta.json")
    fun transcript(context: Context, id: String): JSONObject? = File(dir(context, id), "transcript.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
    fun saveMeta(context: Context, id: String, meta: JSONObject) = write(dir(context, id), "meta.json", meta)
    fun saveTranscript(context: Context, id: String, t: JSONObject) = write(dir(context, id), "transcript.json", t)

    fun setStatus(context: Context, id: String, state: String, detail: String = "") {
        val m = meta(context, id)
        val st = JSONObject().put("state", state).put("detail", detail)
        if (state != "done" && state != "error") st.put("started", m.optJSONObject("status")?.optDouble("started")?.takeIf { !it.isNaN() } ?: System.currentTimeMillis() / 1000.0)
        saveMeta(context, id, m.put("status", st))
    }

    /** The list entry, like `/api/notes`. */
    fun summary(context: Context, id: String): JSONObject {
        val m = meta(context, id); val t = transcript(context, id)
        val segs = t?.optJSONArray("segments")
        val speakers = JSONArray()
        t?.optJSONObject("speakers")?.let { sp -> sp.keys().forEach { k -> if (!sp.isNull(k) && sp.optString(k).isNotBlank()) speakers.put(sp.optString(k)) } }
        return JSONObject().put("id", id).put("title", m.optString("title")).put("created", m.optString("created")).put("folder", m.optString("folder"))
            .put("duration", m.opt("duration") ?: JSONObject.NULL).put("status", m.optJSONObject("status") ?: JSONObject().put("state", "queued"))
            .put("speakers", speakers).put("summary", t?.optString("summary") ?: "").put("snippet", t?.optString("summary")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("origin", "phone")
    }

    /** The note, like `/api/notes/{id}`: the transcript merged over the summary, embeddings left out. */
    fun note(context: Context, id: String): JSONObject {
        val o = summary(context, id); val t = transcript(context, id)
        if (t != null) t.keys().forEach { k -> if (k != "embeddings") o.put(k, t.get(k)) }
        if (!o.has("segments")) o.put("segments", JSONArray()); if (!o.has("action_items")) o.put("action_items", JSONArray())
        o.put("backend", "phone"); o.put("warning", t?.optString("warning")?.ifBlank { null } ?: meta(context, id).optString("warning").ifBlank { null } ?: JSONObject.NULL)
        return o
    }

    fun list(context: Context): List<JSONObject> = root(context).listFiles()?.filter { File(it, "meta.json").exists() }?.map { summary(context, it.name) }?.sortedByDescending { it.optString("created") } ?: emptyList()

    fun patch(context: Context, id: String, body: JSONObject): JSONObject {
        val m = meta(context, id)
        if (body.has("title")) { m.put("title", body.optString("title")); m.put("title_auto", false) }
        if (body.has("folder")) m.put("folder", body.optString("folder"))
        saveMeta(context, id, m)
        body.optJSONObject("speakers")?.let { names ->
            val t = transcript(context, id) ?: JSONObject()
            val sp = t.optJSONObject("speakers") ?: JSONObject()
            names.keys().forEach { k -> sp.put(k, names.optString(k).ifBlank { null } ?: JSONObject.NULL); rememberSpeaker(context, id, k, names.optString(k)) }
            saveTranscript(context, id, t.put("speakers", sp))
        }
        return note(context, id)
    }

    fun delete(context: Context, id: String) { dir(context, id).deleteRecursively() }

    fun markdown(context: Context, id: String): String {
        val n = Parse.note(note(context, id))
        val sb = StringBuilder("# ${n.title}\n\n${n.created}\n\n")
        if (n.summary.isNotBlank()) sb.append("## Summary\n\n${n.summary}\n\n")
        if (n.actionItems.isNotEmpty()) { sb.append("## Action items\n\n"); n.actionItems.forEach { sb.append("- ${it.owner?.let { o -> "$o: " } ?: ""}${it.item}\n") }; sb.append("\n") }
        sb.append("## Transcript\n\n"); n.segments.forEach { s -> sb.append("[${fmtTs(s.start)}] ${n.speakerName(s.speaker)}: ${s.hinglish ?: s.text}\n") }
        return sb.toString()
    }

    // ---- speaker memory on the phone (its own embedding space) ----
    private fun speakersFile(context: Context) = File(context.filesDir, "speakers.json")
    fun speakers(context: Context): JSONObject = speakersFile(context).takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
    fun forgetSpeaker(context: Context, name: String) { speakersFile(context).writeText(speakers(context).apply { remove(name) }.toString()) }

    /** Naming a voice on a note stores that voice's embedding under the name, so the next note recognises it. */
    private fun rememberSpeaker(context: Context, id: String, key: String, name: String) {
        if (name.isBlank()) return
        val emb = transcript(context, id)?.optJSONObject("embeddings")?.optJSONArray(key) ?: return
        speakersFile(context).writeText(speakers(context).put(name, emb).toString())
    }

    /** Known names for the given cluster embeddings, by cosine similarity. */
    fun matchSpeakers(context: Context, embeddings: Map<Int, FloatArray>, threshold: Float = 0.55f): Map<Int, String> {
        val known = speakers(context); val out = HashMap<Int, String>()
        for ((spk, e) in embeddings) {
            var best = threshold; var name: String? = null
            known.keys().forEach { n ->
                val k = known.optJSONArray(n) ?: return@forEach
                if (k.length() != e.size) return@forEach
                var dot = 0f; var na = 0f; var nb = 0f
                for (i in e.indices) { val b = k.optDouble(i).toFloat(); dot += e[i] * b; na += e[i] * e[i]; nb += b * b }
                val cos = dot / (sqrt(na) * sqrt(nb) + 1e-9f)
                if (cos > best) { best = cos; name = n }
            }
            name?.let { out[spk] = it }
        }
        return out
    }

    // ---- Ask threads for phone notes ----
    private fun chatsFile(context: Context, noteId: String) = File(dir(context, noteId), "chats.json")
    private fun chatsArr(context: Context, noteId: String): JSONArray = chatsFile(context, noteId).takeIf { it.exists() }?.let { JSONArray(it.readText()) } ?: JSONArray()
    private fun saveChats(context: Context, noteId: String, a: JSONArray) = chatsFile(context, noteId).writeText(a.toString())
    fun chatNote(sid: String) = sid.substringAfter("chat:").substringBeforeLast("#")
    fun isLocalChat(sid: String) = sid.startsWith("chat:$PREFIX")

    fun chats(context: Context, noteId: String?): List<JSONObject> {
        val ids = if (noteId != null) listOf(noteId) else root(context).list()?.filter { isLocal(it) } ?: emptyList()
        return ids.flatMap { nid -> chatsArr(context, nid).let { a -> List(a.length()) { a.getJSONObject(it) } } }.map { c ->
            JSONObject().put("id", c.optString("id")).put("title", c.optString("title")).put("note", c.optString("note")).put("model", "phone")
                .put("updated", c.optDouble("updated")).put("count", c.optJSONArray("messages")?.length() ?: 0)
        }.sortedByDescending { it.optDouble("updated") }
    }

    fun newChat(context: Context, noteId: String): JSONObject {
        val a = chatsArr(context, noteId)
        val c = JSONObject().put("id", "chat:$noteId#${a.length() + 1}").put("title", "Ask about " + meta(context, noteId).optString("title")).put("note", noteId)
            .put("model", "phone").put("updated", System.currentTimeMillis() / 1000.0).put("messages", JSONArray())
        a.put(c); saveChats(context, noteId, a)
        return c
    }

    fun chat(context: Context, sid: String): JSONObject? {
        val a = chatsArr(context, chatNote(sid))
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == sid) return a.getJSONObject(i)
        return null
    }

    fun saveChat(context: Context, c: JSONObject) {
        val nid = c.optString("note"); val a = chatsArr(context, nid)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == c.optString("id")) { a.put(i, c.put("updated", System.currentTimeMillis() / 1000.0)); saveChats(context, nid, a); return }
    }

    fun deleteChat(context: Context, sid: String) {
        val nid = chatNote(sid); val a = chatsArr(context, nid); val b = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") != sid) b.put(a.getJSONObject(i))
        saveChats(context, nid, b)
    }

    private fun read(d: File, name: String) = JSONObject(File(d, name).readText())
    private fun write(d: File, name: String, o: JSONObject) { val tmp = File(d, "$name.tmp"); tmp.writeText(o.toString()); tmp.renameTo(File(d, name)) }
    fun fmtTs(s: Double): String { val t = s.toInt(); return "%d:%02d".format(t / 60, t % 60) }
}
