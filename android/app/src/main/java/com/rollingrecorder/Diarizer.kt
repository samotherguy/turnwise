package com.rollingrecorder

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Who spoke when, on the phone: pyannote's segmentation model plus a speaker-embedding model through
 * sherpa-onnx, CPU only, about half real time on the S24. Turns are merged the way the server merges
 * pyannote's output (same speaker within 1 s joins, nothing longer than 30 s), so each turn can be
 * transcribed on its own and a segment has one speaker. Embeddings per speaker feed the phone's speaker
 * memory, which is separate from the box's (different embedding space).
 */
class Diarizer(context: Context, threshold: Float = THRESHOLD) {
    data class Turn(val start: Double, val end: Double, val speaker: Int)

    private val dir = Models.dir(context)
    private val sd = OfflineSpeakerDiarization(
        assetManager = null,
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = File(dir, Models.SEGMENTATION.name).path),
                numThreads = 4,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = File(dir, Models.EMBEDDING.name).path, numThreads = 4),
            clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        ),
    )
    private val extractor by lazy { SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = File(dir, Models.EMBEDDING.name).path, numThreads = 4)) }

    /** Raw diarisation of 16 kHz mono samples in [-1, 1], then merged into transcribable turns. */
    fun turns(samples: FloatArray): List<Turn> {
        val raw = sd.process(samples).map { Turn(it.start.toDouble(), it.end.toDouble(), it.speaker) }.sortedBy { it.start }
        return merge(raw)
    }

    /** One embedding per speaker: the average over that speaker's turns (longest first, up to 60 s of audio). */
    fun embeddings(samples: FloatArray, turns: List<Turn>): Map<Int, FloatArray> {
        val out = HashMap<Int, FloatArray>()
        for ((spk, ts) in turns.groupBy { it.speaker }) {
            var used = 0.0; var sum: FloatArray? = null; var n = 0
            for (t in ts.sortedByDescending { it.end - it.start }) {
                if (used > 60.0) break
                val a = (t.start * SAMPLE_RATE).toInt().coerceIn(0, samples.size); val b = (t.end * SAMPLE_RATE).toInt().coerceIn(a, samples.size)
                if (b - a < SAMPLE_RATE) continue                       // under a second says little
                val stream = extractor.createStream()
                stream.acceptWaveform(samples.copyOfRange(a, b), SAMPLE_RATE); stream.inputFinished()
                if (extractor.isReady(stream)) {
                    val e = extractor.compute(stream)
                    sum = sum?.also { s -> for (i in s.indices) s[i] += e[i] } ?: e.copyOf(); n++; used += t.end - t.start
                }
                stream.release()
            }
            if (sum != null && n > 0) out[spk] = FloatArray(sum.size) { sum[it] / n }
        }
        return out
    }

    fun release() { sd.release() }

    companion object {
        const val THRESHOLD = 0.6f          // 10 min of a real meeting: 0.5 splits into 10 voices, 0.7 merges to 2, 0.6 gives the server's three main speakers

        fun merge(raw: List<Turn>, gap: Double = 1.0, maxLen: Double = 30.0, minLen: Double = 0.4): List<Turn> {
            val out = ArrayList<Turn>()
            for (t in raw) {
                val last = out.lastOrNull()
                if (last != null && last.speaker == t.speaker && t.start - last.end <= gap && t.end - last.start <= maxLen) out[out.lastIndex] = last.copy(end = maxOf(last.end, t.end))
                else out.add(t)
            }
            return out.filter { it.end - it.start >= minLen }
        }
    }
}
