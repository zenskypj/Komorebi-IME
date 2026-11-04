package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class ReadingBoundaryTest {
    @Test fun anUnchangedArrowAtTheEndDoesNotForceOneWholeReadingClause() {
        val boundary = ReadingBoundary()
        boundary.moved("にほんご", 4, 4)
        assertNull(boundary.target("にほんご"))
        boundary.moved("にほんご", 4, 2)
        boundary.moved("にほんご", 2, 2)
        assertEquals(2, boundary.target("にほんご"))
    }
    @Test fun manualReadingPrefixSurvivesRepeatedConversionAndAppendedInput() {
        val boundary = ReadingBoundary()
        boundary.record("わたしはがっこうへ", 4)
        repeat(3) { assertEquals(4, boundary.target("わたしはがっこうへ")) }
        assertEquals(4, boundary.target("わたしはがっこうへいきます"))
        assertEquals(4, ReadingBoundary.restore<Int>(2, 4, 14, { it }) { value, expand -> value + if (expand) 1 else -1 })
    }
    @Test fun editsToTheChosenPrefixInvalidateTheOldBoundary() {
        val boundary = ReadingBoundary()
        boundary.record("わたしはがっこうへ", 4)
        boundary.update("ぼくはがっこうへ")
        assertNull(boundary.target("ぼくはがっこうへ"))
    }
    @Test fun readingCodePointsAreIndependentOfKanjiWidthAndSurrogatePairs() {
        val boundary = ReadingBoundary()
        boundary.record("あ😀いえ", 2)
        assertEquals(2, boundary.target("あ😀いえお"))
        assertEquals(5, ReadingBoundary.restore<Int>(2, 5, 10, { it }) { value, expand -> value + if (expand) 1 else -1 })
        assertEquals(2, ReadingBoundary.restore<Int>(6, 2, 10, { it }) { value, expand -> value + if (expand) 1 else -1 })
    }
    @Test fun unsupportedOrUnchangedNativeBoundaryDoesNotLoopOrEraseTheState() {
        var commands = 0
        assertEquals(3, ReadingBoundary.restore<Int>(3, 7, 10, { it }) { value, _ -> commands++; value })
        assertEquals(1, commands)
        assertEquals(3, ReadingBoundary.restore<Int>(3, 7, 10, { null }) { _, _ -> error("No reading metadata") })
        assertEquals(3, ReadingBoundary.restore<Int>(3, 11, 10, { it }) { _, _ -> error("Invalid target") })
        assertNull(ReadingBoundary.restore<Int>(3, 7, 10, { it }) { _, _ -> null })
        assertEquals(-1, ReadingBoundary.restore<Int>(3, 7, 10, { it.takeIf { value -> value >= 0 } }) { _, _ -> -1 })
    }
    @Test fun movingToTheStartOrClearingEndsTheManualPrefix() {
        val boundary = ReadingBoundary()
        boundary.record("にほんご", 2)
        boundary.record("にほんご", 0)
        assertNull(boundary.target("にほんご"))
        boundary.record("にほんご", 4)
        assertEquals(4, boundary.target("にほんご"))
        boundary.clear()
        assertNull(boundary.target("にほんご"))
    }
}
