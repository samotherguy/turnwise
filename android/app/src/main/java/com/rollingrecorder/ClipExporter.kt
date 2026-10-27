package com.rollingrecorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedClip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val addedMs: Long,
    val durationMs: Long,
    val noteId: String?,
)

data class ExportResult(val id: Long, val uri: Uri, val name: String, val durationMs: Long)

/**
 * Cuts a time range out of the rolling buffer and saves it permanently to shared storage
 * (Recordings/RollingRecorder, or Music/RollingRecorder before Android 12).
 * Runs on the caller's thread and never touches the microphone, so recording carries on.
 * Call it off the main thread.
 */
object ClipExporter {

    private const val FOLDER = "RollingRecorder"

    class Cut(val durationMs: Long, val firstStartMs: Long)

    /**
     * Writes the range to [out] without re-encoding. The chunk being written is flushed only
     * when the range reaches into it, so previews of older audio leave the buffer alone.
     */
    fun cut(store: ChunkStore, fromMs: Long, toMs: Long, out: File): Cut {
        require(toMs > fromMs) { "The selected range is empty" }
        val engine = RecorderState.engine
        if (engine != null && toMs > (store.list().lastOrNull()?.endMs ?: 0L)) engine.flush()

        val chunks = store.list().filter { it.endMs > fromMs && it.startMs < toMs }
        if (chunks.isEmpty()) throw IllegalStateException("Nothing was recorded in that range")

        chunks.forEach { store.pin(it.file) }
        try {
            return Cut(concatenate(chunks, fromMs, toMs, out), chunks.first().startMs)
        } finally {
            chunks.forEach { store.unpin(it.file) }
        }
    }

    fun export(context: Context, store: ChunkStore, fromMs: Long, toMs: Long): ExportResult {
        val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}.m4a")
        try {
            val c = cut(store, fromMs, toMs, tmp)
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(maxOf(fromMs, c.firstStartMs)))
            val minutes = ((c.durationMs + 30_000) / 60_000).coerceAtLeast(1)
            val name = "Clip_${stamp}_${minutes}min.m4a"
            val uri = saveToSharedStorage(context, tmp, name)
            return ExportResult(ContentUris.parseId(uri), uri, name, c.durationMs)
        } finally {
            tmp.delete()
        }
    }

    /** Joins the chunks and trims the first and last one to the requested range, without re-encoding. */
    private fun concatenate(chunks: List<ChunkStore.Chunk>, fromMs: Long, toMs: Long, out: File): Long {
        var muxer: MediaMuxer? = null
        var outTrack = -1
        var baseUs = 0L
        var lastPts = -1L
        val buf = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            for (c in chunks) {
                val from = maxOf(fromMs, c.startMs)
                val to = minOf(toMs, c.endMs)
                if (to <= from) continue
                val startOffUs = (from - c.startMs) * 1000L
                val endOffUs = (to - c.startMs) * 1000L

                val ex = MediaExtractor()
                try {
                    ex.setDataSource(c.file.absolutePath)
                    var audioTrack = -1
                    for (i in 0 until ex.trackCount) {
                        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        if (mime != null && mime.startsWith("audio/")) { audioTrack = i; break }
                    }
                    if (audioTrack < 0) continue
                    ex.selectTrack(audioTrack)
                    if (muxer == null) {
                        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        outTrack = muxer.addTrack(ex.getTrackFormat(audioTrack))
                        muxer.start()
                    }
                    if (startOffUs > 0) ex.seekTo(startOffUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val t = ex.sampleTime
                        if (t > endOffUs) break
                        if (t >= startOffUs) {
                            var pts = baseUs + (t - startOffUs)
                            if (pts <= lastPts) pts = lastPts + 1
                            info.set(0, size, pts, ex.sampleFlags)
                            muxer!!.writeSampleData(outTrack, buf, info)
                            lastPts = pts
                        }
                        ex.advance()
                    }
                } finally {
                    ex.release()
                }
                baseUs += (to - from) * 1000L
            }
        } finally {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
        }
        check(lastPts >= 0) { "No audio could be read for that range" }
        return baseUs / 1000L
    }

    private fun saveToSharedStorage(context: Context, file: File, name: String): Uri {
        val resolver = context.contentResolver
        val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Recordings" else "Music"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$folder/$FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Could not create the file")
        try {
            resolver.openOutputStream(uri)!!.use { os ->
                FileInputStream(file).use { it.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    /** Clips saved by this app, newest first. */
    fun listSaved(context: Context): List<SavedClip> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
        )
        val settings = Settings(context)
        val result = mutableListOf<SavedClip>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%$FOLDER%"),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                result += SavedClip(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(1) ?: "clip",
                    sizeBytes = c.getLong(2),
                    addedMs = c.getLong(3) * 1000L,
                    durationMs = c.getLong(4),
                    noteId = settings.noteId(id),
                )
            }
        }
        return result
    }
}
