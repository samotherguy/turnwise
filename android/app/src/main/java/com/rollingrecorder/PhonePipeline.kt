package com.rollingrecorder

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The whole pipeline on the phone, nothing leaving it: decode → speakers → one ASR call per turn → script
 * normalisation → summary with title and action items. No denoise and no English column (the note says so).
 * Status goes into the note's meta so the note screen shows progress like it does for the box.
 */
object PhonePipeline {
    private const val TAG = "PhonePipeline"
    const val WARNING = "Transcribed on this phone: no English translation, speakers are approximate. Redo on the box for the full version."

    suspend fun run(context: Context, id: String, isActive: () -> Boolean, progress: (String) -> Unit) {
        fun stage(state: String, detail: String = "") { LocalNotes.setStatus(context, id, state, detail); progress(detail.ifBlank { state }) }
        try {
            for (spec in Models.ALL) if (!Models.has(context, spec)) { stage("downloading", spec.label); Models.download(context, spec, isActive) }

            stage("decoding", "Reading the audio")
            val audio = LocalNotes.audio(context, id) ?: error("no audio")
            val samples = AudioDecode.toSamples(context, Uri.fromFile(audio))
            val duration = samples.size.toDouble() / SAMPLE_RATE
            LocalNotes.saveMeta(context, id, LocalNotes.meta(context, id).put("duration", duration))

            stage("diarizing", "Finding who speaks when")
            val diar = Diarizer(context)
            val turns = try { diar.turns(samples).ifEmpty { listOf(Diarizer.Turn(0.0, duration, 0)) } } finally { }
            val embeddings = runCatching { diar.embeddings(samples, turns) }.getOrDefault(emptyMap())
            diar.release()
            val names = LocalNotes.matchSpeakers(context, embeddings)

            stage("transcribing", "0/${turns.size} turns")
            val asr = LlamaServer.acquire(context, Models.ASR, Models.ASR_PROJECTOR, ctx = 1024)
            val segments = JSONArray()
            turns.forEachIndexed { i, t ->
                if (!isActive()) throw kotlinx.coroutines.CancellationException()
                val a = (t.start * SAMPLE_RATE).toInt().coerceIn(0, samples.size); val b = (t.end * SAMPLE_RATE).toInt().coerceIn(a, samples.size)
                val pcm = ByteArray((b - a) * 2); var k = 0
                for (j in a until b) { val v = (samples[j] * 32767f).toInt().coerceIn(-32768, 32767); pcm[k++] = (v and 0xFF).toByte(); pcm[k++] = ((v shr 8) and 0xFF).toByte() }
                val seconds = (b - a) / SAMPLE_RATE
                val text = runCatching { asr.transcribe(pcm, (seconds * 8 + 16).coerceIn(24, 400)) }.getOrElse { Log.w(TAG, "turn $i failed", it); "" }
                val clean = Hinglish.latin(collapseRepeats(text))
                if (clean.isNotBlank() && clean.any { it.isLetterOrDigit() } && !clean.equals("Transcribe this audio.", true)) {
                    segments.put(JSONObject().put("start", t.start).put("end", t.end).put("speaker", "SPEAKER_%02d".format(t.speaker)).put("text", clean).put("hinglish", clean))
                }
                if (i % 5 == 4 || i == turns.lastIndex) {
                    stage("transcribing", "${i + 1}/${turns.size} turns")
                    LocalNotes.saveTranscript(context, id, transcriptJson(segments, turns, names, embeddings, "", JSONArray(), null))
                }
            }
            LlamaServer.release()

            stage("summarizing", "Writing the summary")
            val llm = LlamaServer.acquire(context, Models.LLM, ctx = 7168)
            val speakersMap = HashMap<String, String?>()
            for (t in turns) speakersMap["SPEAKER_%02d".format(t.speaker)] = names[t.speaker]
            val (title, summary, items) = summarize(llm, segments, speakersMap)
            LlamaServer.release()

            val meta = LocalNotes.meta(context, id)
            if (meta.optBoolean("title_auto", true) && title.isNotBlank()) meta.put("title", title)
            LocalNotes.saveMeta(context, id, meta)
            LocalNotes.saveTranscript(context, id, transcriptJson(segments, turns, names, embeddings, summary, items, WARNING))
            stage("done")
        } catch (t: Throwable) {
            LlamaServer.release()
            if (t is kotlinx.coroutines.CancellationException) { stage("error", "Cancelled"); throw t }
            Log.w(TAG, "pipeline failed", t)
            stage("error", t.message?.take(200) ?: t.javaClass.simpleName)
        }
    }

    private fun transcriptJson(segments: JSONArray, turns: List<Diarizer.Turn>, names: Map<Int, String>, embeddings: Map<Int, FloatArray>, summary: String, items: JSONArray, warning: String?): JSONObject {
        val speakers = JSONObject(); val embs = JSONObject()
        for (spk in turns.map { it.speaker }.distinct().sorted()) {
            val key = "SPEAKER_%02d".format(spk)
            speakers.put(key, names[spk] ?: JSONObject.NULL)
            embeddings[spk]?.let { e -> embs.put(key, JSONArray().apply { e.forEach { put(it.toDouble()) } }) }
        }
        return JSONObject().put("segments", segments).put("speakers", speakers).put("embeddings", embs).put("summary", summary).put("action_items", items)
            .put("warning", warning ?: JSONObject.NULL).put("backend", "phone")
    }

    // ---- summary: the server's prompts, in chunks the phone can read in a couple of minutes ----
    private const val SUMMARY_PROMPT = "You write meeting notes from a transcript (a Hindi/English meeting written in Latin letters; speakers may be unnamed).\n" +
        "Return JSON: title (max 8 words, specific to what was discussed, no quotes), summary (3-8 sentences or short bullet lines in markdown: what was discussed, decisions, open questions), action_items (concrete follow-ups; owner = speaker name or role if clear, else \"\"). Only include what is actually in the transcript."
    private const val CHUNK_PROMPT = "You reduce one part of a long meeting transcript (Hindi/English in Latin letters). Return JSON: key_points (short lines: what was discussed, decisions, open questions in this part), action_items (concrete follow-ups with owner if clear). Only what is in the text."
    private const val MERGE_PROMPT = "You write meeting notes from the key points of several parts of one meeting, in order. Return JSON: title (max 8 words, specific), summary (3-8 sentences or short bullet lines in markdown), action_items (deduplicated; owner = name or role if clear, else \"\")."
    private val SUMMARY_SCHEMA = JSONObject("""{"type":"object","properties":{"title":{"type":"string"},"summary":{"type":"string"},"action_items":{"type":"array","items":{"type":"object","properties":{"owner":{"type":"string"},"item":{"type":"string"}},"required":["owner","item"]}}},"required":["title","summary","action_items"]}""")
    private val CHUNK_SCHEMA = JSONObject("""{"type":"object","properties":{"key_points":{"type":"array","items":{"type":"string"}},"action_items":{"type":"array","items":{"type":"string"}}},"required":["key_points","action_items"]}""")
    private const val CHUNK_CHARS = 16_000      // ~20 minutes of talk; the 2B reads it in ~30 s

    fun lines(segments: JSONArray, speakers: Map<String, String?>): List<String> = List(segments.length()) { i ->
        val s = segments.getJSONObject(i); val k = s.optString("speaker")
        "[${LocalNotes.fmtTs(s.optDouble("start"))}] ${speakers[k] ?: spkLabel(k)}: ${s.optString("hinglish").ifBlank { s.optString("text") }}"
    }

    private fun summarize(llm: LlamaServer, segments: JSONArray, speakers: Map<String, String?>): Triple<String, String, JSONArray> {
        val text = lines(segments, speakers).joinToString("\n")
        if (text.isBlank()) return Triple("", "", JSONArray())
        val out: JSONObject = if (text.length <= CHUNK_CHARS) {
            JSONObject(llm.chat(SUMMARY_PROMPT, text, SUMMARY_SCHEMA, maxTokens = 700))
        } else {
            val parts = text.chunked(CHUNK_CHARS); val notes = StringBuilder()
            parts.forEachIndexed { i, p ->
                val r = runCatching { JSONObject(llm.chat(CHUNK_PROMPT, p, CHUNK_SCHEMA, maxTokens = 400)) }.getOrNull() ?: return@forEachIndexed
                notes.append("Part ${i + 1}:\n")
                r.optJSONArray("key_points")?.let { a -> for (j in 0 until a.length()) notes.append("- ${a.optString(j)}\n") }
                r.optJSONArray("action_items")?.let { a -> for (j in 0 until a.length()) notes.append("- Action: ${a.optString(j)}\n") }
                notes.append("\n")
            }
            JSONObject(llm.chat(MERGE_PROMPT, notes.toString(), SUMMARY_SCHEMA, maxTokens = 700))
        }
        val items = JSONArray(); out.optJSONArray("action_items")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("item").isNotBlank()) items.put(JSONObject().put("owner", o.optString("owner")).put("item", o.optString("item"))) } }
        return Triple(out.optString("title").trim().trim('"'), out.optString("summary").trim(), items)
    }

    /** Ask over one phone note: the segments that best match the question, within what the 2B can read, then an answer with its sources. */
    suspend fun ask(context: Context, noteId: String, question: String): Pair<String, JSONArray> {
        val t = LocalNotes.transcript(context, noteId) ?: error("not transcribed yet")
        val segs = t.optJSONArray("segments") ?: JSONArray()
        val sp = t.optJSONObject("speakers"); val speakers = HashMap<String, String?>()
        sp?.keys()?.forEach { k -> speakers[k] = if (sp.isNull(k)) null else sp.optString(k).ifBlank { null } }
        val all = lines(segs, speakers)
        val words = question.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        val keep = HashSet<Int>()
        var budget = 20_000; var used = all.sumOf { it.length + 1 }
        if (used <= budget) keep.addAll(all.indices) else {
            val scored = all.indices.sortedByDescending { i -> words.count { w -> all[i].lowercase().contains(w) } }
            for (i in scored) { for (j in (i - 1)..(i + 1)) if (j in all.indices && j !in keep && used <= budget) { keep.add(j); used += 0 }; budget -= all[i].length * 3; if (budget <= 0) break }
        }
        val idx = keep.sorted(); val text = idx.joinToString("\n") { all[it] }
        val meta = LocalNotes.meta(context, noteId)
        val llm = LlamaServer.acquire(context, Models.LLM, ctx = 7168)
        val answer = llm.chat("Answer the question from this meeting transcript (\"${meta.optString("title")}\"), briefly and concretely, in the user's language. Quote who said what when useful. If the transcript does not say, say so.\n\n$text", question, maxTokens = 400)
        val sources = JSONArray()
        for (i in idx.sortedByDescending { i -> words.count { w -> all[i].lowercase().contains(w) } }.take(4)) {
            val s = segs.getJSONObject(i)
            sources.put(JSONObject().put("note", noteId).put("title", meta.optString("title")).put("created", meta.optString("created")).put("turn", i)
                .put("start", s.optDouble("start")).put("speaker", speakers[s.optString("speaker")] ?: spkLabel(s.optString("speaker"))).put("hinglish", s.optString("hinglish")).put("english", ""))
        }
        return answer.trim() to sources
    }

    /** A phrase of 1 to 4 words repeated three or more times in a row is a decoder loop: keep two. */
    fun collapseRepeats(text: String): String {
        val w = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        var i = 0
        while (i < w.size) {
            var cut = false
            for (n in 1..4) {
                if (i + 3 * n > w.size) break
                val a = w.subList(i, i + n).map { it.lowercase().trim(',', '.', '!', '?') }
                var reps = 1
                while (i + (reps + 1) * n <= w.size && w.subList(i + reps * n, i + (reps + 1) * n).map { it.lowercase().trim(',', '.', '!', '?') } == a) reps++
                if (reps >= 3) { repeat((reps - 2) * n) { w.removeAt(i + 2 * n) }; cut = true; break }
            }
            if (!cut) i++
        }
        return w.joinToString(" ")
    }
}
