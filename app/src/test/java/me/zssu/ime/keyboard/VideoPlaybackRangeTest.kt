package me.zssu.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackRangeTest {
    @Test
    fun `a full clip keeps native looping available`() {
        assertTrue(VideoPlaybackRange.resolve(0f, 1f, 4100).isFull(4100))
    }

    @Test
    fun `edited range stays inside the clip with a safe minimum`() {
        assertEquals(VideoPlaybackRange(2500, 7500), VideoPlaybackRange.resolve(0.25f, 0.75f, 10000))
        assertEquals(VideoPlaybackRange(9500, 10000), VideoPlaybackRange.resolve(1f, 0f, 10000))
        assertEquals(VideoPlaybackRange(0, 210), VideoPlaybackRange.resolve(0.8f, 1f, 210))
    }
}
