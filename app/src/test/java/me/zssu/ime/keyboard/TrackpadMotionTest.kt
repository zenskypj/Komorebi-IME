package me.zssu.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackpadMotionTest {
    @Test fun smallMovesAccumulateWithoutRepeatingAtRest() {
        val motion = TrackpadMotion(18f, 100f)
        assertEquals(0, motion.move(110f))
        assertEquals(1, motion.move(120f))
        assertEquals(0, motion.move(120f))
        assertEquals(0, motion.move(117f))
        assertEquals(-1, motion.move(100f))
    }
    @Test fun longDragMovesSeveralPositionsAndCanReverse() {
        val motion = TrackpadMotion(18f, 0f)
        assertEquals(3, motion.move(60f))
        assertEquals(-2, motion.move(18f))
        assertEquals(0, motion.move(Float.NaN))
    }
}
