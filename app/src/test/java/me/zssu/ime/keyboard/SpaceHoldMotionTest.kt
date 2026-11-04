package me.zssu.ime.keyboard

import org.junit.Assert.*
import org.junit.Test

class SpaceHoldMotionTest {
    @Test fun centerDeadZoneDoesNotMoveAndGreaterDistanceIsFaster() {
        fun count(x: Float): Int {
            val m = SpaceHoldMotion(0f, 1f)
            return (0L..2000L step 10L).sumOf { m.tick(x, it) }
        }
        assertEquals(0, count(12f))
        assertTrue(count(100f) > count(25f))
        assertTrue(count(100f) <= 16)
        assertEquals(count(100f), count(5000f))
    }
    @Test fun stalledFramesNeverCatchUpInABurstAndReversingWaits() {
        val m = SpaceHoldMotion(0f, 1f)
        assertEquals(0, m.tick(100f, 0))
        assertEquals(1, m.tick(100f, 10000))
        assertEquals(0, m.tick(100f, 10000))
        assertEquals(0, m.tick(-100f, 10010))
        assertEquals(-1, m.tick(-100f, 10200))
        assertEquals(0, m.tick(0f, 10300))
        assertEquals(0, m.tick(-100f, 10301))
    }
    @Test fun physicalDensityDoesNotChangeTheSpeed() {
        val a = SpaceHoldMotion(20f, 1f)
        val b = SpaceHoldMotion(40f, 2f)
        for (time in 0L..2000L step 32L) assertEquals(a.tick(80f, time), b.tick(160f, time))
        assertEquals(0, a.tick(Float.NaN, 3000))
    }
}
