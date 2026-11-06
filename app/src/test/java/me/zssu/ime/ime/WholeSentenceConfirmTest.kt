package me.zssu.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeSentenceConfirmTest {
    @Test
    fun `first convert press arms the whole-sentence confirmation`() {
        assertTrue(WholeSentenceConfirm.arm(composing = true, wasConverting = false))
    }

    @Test
    fun `a second convert press and idle space do not arm it`() {
        assertFalse(WholeSentenceConfirm.arm(composing = true, wasConverting = true))
        assertFalse(WholeSentenceConfirm.arm(composing = false, wasConverting = false))
    }

    @Test
    fun `only an armed multi-clause conversion commits everything`() {
        assertTrue(WholeSentenceConfirm.commitsAll(converting = true, armed = true, segmentCount = 2))
        assertFalse(WholeSentenceConfirm.commitsAll(converting = true, armed = true, segmentCount = 1))
        assertFalse(WholeSentenceConfirm.commitsAll(converting = true, armed = false, segmentCount = 3))
        assertFalse(WholeSentenceConfirm.commitsAll(converting = false, armed = true, segmentCount = 3))
    }
}
