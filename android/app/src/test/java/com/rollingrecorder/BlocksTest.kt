package com.rollingrecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class BlocksTest {
    private val bucketMs = 10_000L

    private fun levels(vararg talking: IntRange): IntArray =
        IntArray(300) { i -> if (talking.any { i in it }) 200 else 50 }

    @Test
    fun separatesBlocksAndDropsBlips() {
        // 100..130 and 140..160 are 90 s apart: separate. 200..203 is 40 s: kept. 250 alone is 10 s: dropped.
        val blocks = Blocks.find(levels(100..130, 140..160, 200..203, 250..250), bucketMs)
        assertEquals(listOf(97..133, 137..163, 197..206), blocks)
    }

    @Test
    fun bridgesShortGaps() {
        // 100..110 and 115..120 are 40 s apart: one block.
        assertEquals(listOf(97..123), Blocks.find(levels(100..110, 115..120), bucketMs))
    }

    @Test
    fun quietBufferHasNoBlocks() {
        assertEquals(emptyList<IntRange>(), Blocks.find(IntArray(300) { 50 }, bucketMs))
    }

    @Test
    fun bucketsAverageTheSecondsAndMarkGaps() {
        val a = Blocks.Span(0, 10_000, byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100))
        val b = Blocks.Span(20_000, 30_000, ByteArray(0)) // old chunk without levels: present, quiet
        val out = Blocks.buckets(listOf(a, b), 0, 30_000, 3)
        assertEquals(listOf(55, Blocks.NONE, 0), out.toList())
    }
}
