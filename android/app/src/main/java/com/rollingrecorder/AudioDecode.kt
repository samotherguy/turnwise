package com.rollingrecorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio Android can play (the app's own AAC clips, shared m4a/mp3/wav) to 16 kHz mono samples in [-1, 1]. */
object AudioDecode {
    fun toSamples(context: Context, uri: Uri, maxSeconds: Int = 4 * 3600): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("no audio track")
            val fmt = ex.getTrackFormat(track); ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcm = ShortList()
            val info = MediaCodec.BufferInfo(); var inputDone = false; var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!; val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.size > 0) {
                            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(); val frames = info.size / 2 / channels
                            for (f in 0 until frames) {                         // mix to mono
                                var acc = 0; for (c in 0 until channels) acc += sb.get(f * channels + c)
                                pcm.add((acc / channels).toShort())
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (pcm.size > maxSeconds.toLong() * rate) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            codec.stop(); codec.release()
            return resample(pcm, rate)
        } finally { ex.release() }
    }

    /** Linear resampling to 16 kHz; fine for speech. The app's own clips are already 16 kHz. */
    private fun resample(pcm: ShortList, rate: Int): FloatArray {
        if (rate == SAMPLE_RATE) return FloatArray(pcm.size) { pcm[it] / 32768f }
        val n = (pcm.size.toLong() * SAMPLE_RATE / rate).toInt()
        return FloatArray(n) { i ->
            val src = i.toDouble() * rate / SAMPLE_RATE; val a = src.toInt(); val t = (src - a).toFloat()
            val s0 = pcm[a.coerceAtMost(pcm.size - 1)].toFloat(); val s1 = pcm[(a + 1).coerceAtMost(pcm.size - 1)].toFloat()
            (s0 + (s1 - s0) * t) / 32768f
        }
    }

    /** Growable short array without boxing. */
    private class ShortList {
        private var a = ShortArray(1 shl 20); var size = 0; private set
        fun add(v: Short) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
    }
}
