package com.rollingrecorder

import android.content.Context
import android.net.Uri

/**
 * Where a recording goes: to the box (today's upload, which wakes it) or into a phone note processed on
 * the device. One place for the Settings choice, used by every send path.
 */
object Ingest {
    fun onPhone(context: Context) = Settings(context).transcribeOn == "phone"

    fun submit(
        context: Context, uri: Uri, name: String, createdMs: Long,
        title: String = "", folder: String = "", onProgress: (Float) -> Unit = {},
    ): Turnwise.Uploaded {
        if (!onPhone(context)) return Turnwise.upload(context, Settings(context).turnwiseUrl, uri, name, createdMs, title, folder, onProgress)
        val id = LocalNotes.create(context, uri, name, createdMs, title, folder)
        onProgress(1f)
        TranscribeService.enqueue(context, id)
        Bus.notesVersion++
        return Turnwise.Uploaded(id, false)
    }

    /** Re-runs the phone pipeline on an existing phone note. */
    fun redoOnPhone(context: Context, id: String) {
        LocalNotes.saveMeta(context, id, LocalNotes.meta(context, id).put("attempts", 0))
        LocalNotes.setStatus(context, id, "queued")
        TranscribeService.enqueue(context, id)
    }

    /** Sends a phone note's audio to the box; on success the local copy goes and the server note's id comes back. */
    fun redoOnBox(context: Context, id: String, onProgress: (Float) -> Unit = {}): String {
        val meta = LocalNotes.meta(context, id)
        val audio = LocalNotes.audio(context, id) ?: error("no audio")
        val createdMs = runCatching { java.time.LocalDateTime.parse(meta.optString("created")).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(audio.lastModified())
        val title = if (meta.optBoolean("title_auto", true)) "" else meta.optString("title")
        val up = Turnwise.upload(context, Settings(context).turnwiseUrl, Uri.fromFile(audio), audio.name, createdMs, title, meta.optString("folder"), onProgress)
        LocalNotes.delete(context, id)
        Bus.notesVersion++
        return up.id
    }
}
