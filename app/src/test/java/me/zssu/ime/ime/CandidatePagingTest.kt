package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class CandidatePagingTest {
    @Test fun conversionNeverAdvancesIntoAnUnopenedPage() {
        for (count in 1..40) for (initial in 0 until count) {
            val start = CandidatePaging.pageStart(count, initial)
            val end = CandidatePaging.pageEnd(count, initial)
            var focus = initial
            repeat(50) { focus = CandidatePaging.next(count, focus)!!; assertTrue(focus in start until end) }
        }
    }
    @Test fun nextPageRequiresAnExplicitActionAndDoesNotWrap() {
        assertEquals(6, CandidatePaging.adjacentPage(20, 4, 1))
        assertEquals(0, CandidatePaging.adjacentPage(20, 7, -1))
        assertNull(CandidatePaging.adjacentPage(20, 0, -1))
        assertNull(CandidatePaging.adjacentPage(20, 19, 1))
        assertEquals(18, CandidatePaging.next(20, 19))
    }
    @Test fun missingOrEmptyFocusDoesNotSkipTheFirstCandidate() {
        assertNull(CandidatePaging.next(0, -1))
        assertEquals(0, CandidatePaging.next(20, -1))
    }
    @Test fun nativeGlobalIndexIsNotUsedAsAnIndexIntoTheVisiblePage() {
        val page = listOf(9 to 150, 10 to 200, 11 to 240)
        assertEquals(200, CandidatePaging.focusedId(page, 10))
        assertNull(CandidatePaging.focusedId(page, 1))
    }
}
