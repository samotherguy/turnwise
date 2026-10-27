package com.rollingrecorder

import android.content.Context
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The rolling buffer on disk. Audio is stored as short AAC chunks named
 * "<startMs>_<endMs>.m4a" (wall-clock epoch milliseconds). In-progress chunks are
 * "<startMs>.part" and are ignored by everything except the writer.
 */
class ChunkStore(context: Context) {

    class Chunk(val file: File, val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
        /** One loudness byte per second, empty for chunks recorded before levels existed. */
        val levels: ByteArray by lazy { runCatching { levelFile(file).readBytes() }.getOrDefault(ByteArray(0)) }
    }

    val dir: File = File(context.applicationContext.filesDir, "buffer").apply { mkdirs() }

    private companion object {
        // Chunks currently being read by an export; pruning must leave them alone.
        // Process-wide: the UI and the service each build their own ChunkStore.
        val pinned: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    }

    fun pin(file: File) {
        pinned.add(file.name)
    }

    fun unpin(file: File) {
        pinned.remove(file.name)
    }

    fun list(): List<Chunk> {
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            if (f.extension != "m4a") return@mapNotNull null
            val parts = f.nameWithoutExtension.split("_")
            val start = parts.getOrNull(0)?.toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            if (start == null || end == null) null else Chunk(f, start, end)
        }.sortedBy { it.startMs }
    }

    /** Deletes chunks that ended before the rolling window began. */
    /** Deletes audio older than the window, except anything after [keepFromMs] (a meeting being recorded). */
    fun prune(windowMs: Long, nowMs: Long = System.currentTimeMillis(), keepFromMs: Long = 0L) {
        val cutoff = if (keepFromMs > 0) minOf(nowMs - windowMs, keepFromMs) else nowMs - windowMs
        for (c in list()) {
            if (c.endMs < cutoff && c.file.name !in pinned) {
                c.file.delete()
                levelFile(c.file).delete()
            }
        }
    }

    /** Leftovers from a crash: an unfinished .part file cannot be played, so remove it. */
    fun cleanupPartials() {
        dir.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
    }
}

private fun levelFile(chunk: File) = File(chunk.parentFile, chunk.nameWithoutExtension + ".lvl")
