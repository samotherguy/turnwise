package com.rollingrecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000
const val BIT_RATE = 48_000
private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC

/**
 * Encodes PCM (16-bit mono) to AAC and writes one .m4a chunk.
 * Not thread-safe: only the recording thread touches it.
 */
class ChunkWriter(private val dir: File, val startMs: Long) {

    private val part = File(dir, "$startMs.part")
    private val codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var totalSamples = 0L
    private var released = false

    // One loudness byte per second (0 = -60 dBFS or quieter, 255 = -15 dBFS or louder).
    private val levels = ArrayList<Byte>(32)
    private var secSumSq = 0.0
    private var secCount = 0

    init {
        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec = MediaCodec.createEncoderByType(MIME)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (t: Throwable) {
            try { codec.release() } catch (_: Throwable) {}
            part.delete()
            throw t
        }
    }

    /** Length of audio written so far. */
    fun durationMs(): Long = totalSamples * 1000L / SAMPLE_RATE

    /** Loudness per second so far, for the live end of the timeline. */
    fun levels(): ByteArray = levels.toByteArray()

    fun write(pcm: ByteArray, length: Int) {
        measure(pcm, length)
        var offset = 0
        var stalls = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(false)
                if (++stalls > 200) throw IllegalStateException("AAC encoder stopped accepting input")
                continue
            }
            stalls = 0
            val buf = codec.getInputBuffer(index)!!
            buf.clear()
            val n = min(buf.capacity(), length - offset)
            buf.put(pcm, offset, n)
            val ptsUs = totalSamples * 1_000_000L / SAMPLE_RATE
            codec.queueInputBuffer(index, 0, n, ptsUs, 0)
            totalSamples += n / 2
            offset += n
            drain(false)
        }
    }

    private fun measure(pcm: ByteArray, length: Int) {
        var i = 0
        while (i + 1 < length) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            secSumSq += v.toDouble() * v
            if (++secCount == SAMPLE_RATE) flushLevel()
            i += 2
        }
    }

    private fun flushLevel() {
        if (secCount == 0) return
        val rms = sqrt(secSumSq / secCount) / 32768.0
        val db = if (rms > 0) 20 * log10(rms) else -120.0
        levels += ((db + 60.0) / 45.0 * 255.0).toInt().coerceIn(0, 255).toByte()
        secSumSq = 0.0
        secCount = 0
    }

    /** Finalises the chunk and returns the finished file, or null if it held no audio. */
    fun finish(): File? {
        if (released) return null
        try {
            if (totalSamples == 0L) {
                abort()
                return null
            }
            var queued = false
            var tries = 0
            while (!queued && tries++ < 100) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(
                        index, 0, 0,
                        totalSamples * 1_000_000L / SAMPLE_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    queued = true
                } else {
                    drain(false)
                }
            }
            check(queued) { "could not queue end-of-stream" }
            drain(true)

            codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
            released = true

            val endMs = startMs + durationMs()
            val out = File(dir, "${startMs}_${endMs}.m4a")
            if (!muxerStarted || !part.renameTo(out)) {
                part.delete()
                return null
            }
            if (secCount >= SAMPLE_RATE / 4) flushLevel()
            runCatching { File(dir, "${startMs}_${endMs}.lvl").writeBytes(levels()) }
            return out
        } catch (t: Throwable) {
            abort()
            throw t
        }
    }

    /** Throws the chunk away and frees the codec and muxer. Safe to call more than once. */
    fun abort() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { if (muxerStarted) muxer.stop() } catch (_: Throwable) {}
        try { muxer.release() } catch (_: Throwable) {}
        part.delete()
    }

    private fun drain(endOfStream: Boolean) {
        var idleRounds = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idleRounds > 300) {
                        throw IllegalStateException("encoder never signalled end of stream")
                    }
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buf != null && info.size > 0 && !isConfig && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }
}
