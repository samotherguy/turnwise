package com.rollingrecorder

/** Timeline maths with no Android in it, so it can be unit-tested on the JVM. */
object Blocks {
    const val NONE = -1

    /** A recorded stretch: its wall-clock start and end, and one loudness byte per second. */
    class Span(val startMs: Long, val endMs: Long, val levels: ByteArray)

    /** Mean level per bucket across [fromMs, toMs); NONE where nothing was recorded. */
    fun buckets(spans: List<Span>, fromMs: Long, toMs: Long, count: Int): IntArray {
        // ponytail: mean, not max, so a 10 h view (150 s per bar) does not light up every bar
        // from one door slam; a loud second still shows at the minute-scale zooms.
        val sum = IntArray(count)
        val n = IntArray(count)
        val width = (toMs - fromMs).coerceAtLeast(1)
        for (s in spans) {
            val seconds = if (s.levels.isNotEmpty()) s.levels.size else ((s.endMs - s.startMs) / 1000).toInt()
            for (i in 0 until seconds) {
                val t = s.startMs + i * 1000L
                if (t < fromMs || t >= toMs) continue
                val b = ((t - fromMs) * count / width).toInt().coerceIn(0, count - 1)
                sum[b] += if (s.levels.isNotEmpty()) s.levels[i].toInt() and 0xff else 0
                n[b]++
            }
        }
        return IntArray(count) { if (n[it] == 0) NONE else sum[it] / n[it] }
    }

    /** Level above which a bucket counts as talking. */
    fun threshold(levels: IntArray): Int {
        val present = levels.filter { it >= 0 }.sorted()
        if (present.isEmpty()) return 255
        val p90 = present[(present.size * 9 / 10).coerceAtMost(present.size - 1)]
        // ponytail: fixed floor of 100 (~ -42 dBFS) and 60% of the loud tail; retune if a phone's
        // mic gain makes quiet rooms read as talking or far speakers as silence.
        return maxOf(100, p90 * 6 / 10)
    }

    /**
     * Runs of talking, as bucket index ranges. Gaps shorter than [mergeMs] are bridged, runs
     * shorter than [minMs] dropped, and each block is padded by [padMs] on both sides.
     */
    fun find(levels: IntArray, bucketMs: Long, mergeMs: Long = 60_000, minMs: Long = 20_000, padMs: Long = 30_000): List<IntRange> {
        val thr = threshold(levels)
        val merge = (mergeMs / bucketMs).toInt()
        val pad = (padMs / bucketMs).toInt()
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in levels.indices) {
            val on = levels[i] >= thr
            if (on && start < 0) start = i
            if (!on && start >= 0) { runs += start until i; start = -1 }
        }
        if (start >= 0) runs += start until levels.size
        val merged = mutableListOf<IntRange>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.last - 1 <= merge) merged[merged.size - 1] = last.first..r.last
            else merged += r
        }
        return merged
            .filter { (it.last - it.first + 1) * bucketMs >= minMs }
            .map { (it.first - pad).coerceAtLeast(0)..(it.last + pad).coerceAtMost(levels.size - 1) }
    }
}
