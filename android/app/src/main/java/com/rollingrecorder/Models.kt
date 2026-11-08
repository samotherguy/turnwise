package com.rollingrecorder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The on-device models: where they live, where they come from, and a resumable download. All four are
 * needed for a phone-made note; the first two alone for the live transcript.
 */
object Models {
    data class Spec(val name: String, val size: Long, val label: String)

    val ASR = Spec("Qwen3-ASR-1.7B-Q8_0.gguf", 2_165_034_944L, "Speech to text (Qwen3-ASR 1.7B)")
    val ASR_PROJECTOR = Spec("mmproj-Qwen3-ASR-1.7B-Q8_0.gguf", 355_709_344L, "Speech to text, audio projector")
    val LLM = Spec("Qwen3.5-2B-Q8_0.gguf", 2_012_012_800L, "Summaries and Ask (Qwen3.5 2B)")
    val SEGMENTATION = Spec("pyannote-segmentation-3.0.onnx", 5_992_913L, "Speakers, segmentation")
    val EMBEDDING = Spec("wespeaker-resnet34-lm.onnx", 26_530_550L, "Speakers, voices")
    val ALL = listOf(ASR, ASR_PROJECTOR, LLM, SEGMENTATION, EMBEDDING)
    val LIVE = listOf(ASR, ASR_PROJECTOR)

    /** Download progress for the models screen and the job notifications. */
    data class Progress(val name: String, val done: Long, val size: Long)
    val progress = MutableStateFlow<Progress?>(null)

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }
    fun has(context: Context, spec: Spec): Boolean = File(dir(context), spec.name).let { it.exists() && (spec.size <= 0 || it.length() == spec.size) }
    fun missing(context: Context, specs: List<Spec>) = specs.filter { !has(context, it) }

    /** Fetches a model (resumable), waking the box if it is the source and it is off. */
    fun download(context: Context, spec: Spec, isActive: () -> Boolean = { true }) {
        val out = File(dir(context), spec.name)
        if (has(context, spec)) return
        val settings = Settings(context)
        val base = settings.modelsUrl.ifBlank { settings.turnwiseUrl + "/models" }
        if (base.startsWith(settings.turnwiseUrl)) Waker.wakeIfOff()
        val part = File(dir(context), spec.name + ".part"); val have = part.length()
        val c = URL("$base/${spec.name}").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 60_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            if (c.responseCode !in 200..299) throw IOException("${spec.name}: server answered ${c.responseCode}")
            val append = c.responseCode == 206
            val total = if (spec.size > 0) spec.size else (if (append) have else 0L) + c.contentLengthLong
            c.inputStream.use { input -> java.io.FileOutputStream(part, append).use { os ->
                val buf = ByteArray(256 * 1024); var done = if (append) have else 0L; var last = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    os.write(buf, 0, n); done += n
                    if (done - last > 2 shl 20) { last = done; progress.value = Progress(spec.name, done, total) }
                    if (!isActive()) throw CancellationException()
                }
            } }
        } finally { c.disconnect(); progress.value = null }
        if (spec.size > 0 && part.length() != spec.size) throw IOException("${spec.name} is ${part.length()} bytes, expected ${spec.size}")
        if (!part.renameTo(out)) throw IOException("could not finish ${spec.name}")
        Log.i("Models", "downloaded ${spec.name}")
    }

    fun delete(context: Context, spec: Spec) { File(dir(context), spec.name).delete(); File(dir(context), spec.name + ".part").delete() }
    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
