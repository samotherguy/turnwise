package com.rollingrecorder

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** One MediaPlayer with Compose-observable state. Plays preview renders, saved clips and Turnwise notes. */
class PreviewPlayer(private val context: Context) {
    private var mp: MediaPlayer? = null
    private var file: File? = null

    var title by mutableStateOf("")
        private set
    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** Set when a Turnwise note is loaded, so the mini player can show who is talking and step by turn. */
    var noteId by mutableStateOf<String?>(null)
        private set
    var segments by mutableStateOf<List<Segment>>(emptyList())
        private set
    var speakerNames by mutableStateOf<Map<String, String?>>(emptyMap())
        private set

    /**
     * Loads a rendered file (deleted when replaced), a content uri or an https uri.
     * The MediaPlayer is only ever touched on the main thread (the progress ticker reads it there);
     * the new one is prepared on IO, which is a network round trip for notes.
     */
    suspend fun load(source: File?, uri: Uri?, title: String, note: Note? = null) = withContext(NonCancellable) {
        // Not cancellable as a whole: a player prepared for a stale selection is handed over, never leaked,
        // and the next load releases it.
        withContext(Dispatchers.Main) { unload() }
        val p = withContext(Dispatchers.IO) {
            val p = MediaPlayer()
            try {
                if (source != null) p.setDataSource(source.absolutePath) else p.setDataSource(context, uri!!)
                p.prepare()
                p
            } catch (t: Throwable) {
                p.release()
                source?.delete()
                throw t
            }
        }
        withContext(Dispatchers.Main) {
            p.setOnCompletionListener { playing = false; positionMs = durationMs }
            mp?.release()   // two loads in flight at once: the later one wins
            file?.takeIf { it != source }?.delete()
            mp = p
            file = source
            this@PreviewPlayer.title = title
            // Browser recordings (webm) often carry no duration: fall back to the transcript's.
            durationMs = p.duration.toLong().takeIf { it > 0 } ?: ((note?.duration ?: 0.0) * 1000).toLong()
            positionMs = 0
            noteId = note?.id
            segments = note?.segments ?: emptyList()
            speakerNames = note?.speakers ?: emptyMap()
            ready = true
        }
    }

    fun unload() {
        mp?.release()
        mp = null
        file?.delete()
        file = null
        ready = false
        playing = false
        positionMs = 0
        durationMs = 0
        noteId = null
        segments = emptyList()
        speakerNames = emptyMap()
        title = ""
    }

    /** Index of the turn under the playhead, or -1. */
    fun turnIndex(): Int = turnAt(segments, positionMs / 1000.0)

    fun stepTurn(d: Int) {
        if (segments.isEmpty()) return
        val i = (turnIndex() + d).coerceIn(0, segments.size - 1)
        playFrom((segments[i].start * 1000).toLong())
    }

    fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
    }

    fun playFrom(ms: Long) {
        val p = mp ?: return
        val at = clamp(ms)
        p.seekTo(at.toInt())
        p.start()
        playing = true
        positionMs = at
    }

    fun seek(ms: Long) {
        mp?.seekTo(clamp(ms).toInt())
        positionMs = clamp(ms)
    }

    private fun clamp(ms: Long) = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)

    fun tick() {
        mp?.let { if (it.isPlaying) positionMs = it.currentPosition.toLong() }
    }
}

/** Last turn starting at or before [sec] (10 ms slack, as in the web player). */
fun turnAt(segs: List<Segment>, sec: Double): Int {
    var i = -1
    for (k in segs.indices) { if (segs[k].start <= sec + 0.01) i = k else break }
    return i
}
