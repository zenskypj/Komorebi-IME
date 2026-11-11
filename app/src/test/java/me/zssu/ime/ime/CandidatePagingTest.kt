package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class CandidatePagingTest {
    @Test fun rowSettingControlsPackingInsteadOfAFixedSixCandidates() {
        val widths = List(8) { 70 }
        assertEquals(listOf(listOf(0, 1, 2)), CandidatePaging.pages(widths, 240, 1).first().rows)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), CandidatePaging.pages(widths, 240, 2).first().rows)
    }
    @Test fun longCandidatesAndLargeTextReduceTheVisiblePage() {
        assertEquals(listOf(0), CandidatePaging.pages(listOf(400, 60, 60), 200, 1).first().indices)
        assertEquals(listOf(0, 1), CandidatePaging.pages(List(8) { 100 }, 240, 1).first().indices)
    }
    @Test fun everyCandidateAppearsOnceWithoutOverflowAcrossWidthsAndRows() {
        for (width in listOf(1, 120, 240, 600)) for (rows in 1..2) {
            val sizes = List(120) { 48 + it % 7 * 30 }
            val pages = CandidatePaging.pages(sizes, width, rows)
            assertEquals(sizes.indices.toList(), pages.flatMap { it.indices })
            pages.forEach { page ->
                assertTrue(page.rows.size <= rows)
                page.rows.forEach { row -> assertTrue(row.sumOf { sizes[it].coerceAtMost(width) } <= width) }
            }
        }
    }
    @Test fun repeatedConversionCrossesPagesAndWrapsOnlyAtTheEnd() {
        val available = (1..30).toList()
        var focus: Int? = 9
        repeat(21) { focus = CandidatePaging.nextCandidate(available, available, focus) }
        assertEquals(30, focus)
        assertEquals(1, CandidatePaging.nextCandidate(available, available, focus))
        assertEquals(10, CandidatePaging.nextCandidate(available, emptyList(), 9))
        assertEquals(10, CandidatePaging.nextCandidate(available, listOf(999, 10), 999))
        assertNull(CandidatePaging.nextCandidate(emptyList(), emptyList(), null))
    }
    @Test fun nativeGlobalIndexIsNotUsedAsAnIndexIntoTheVisiblePage() {
        val page = listOf(9 to 150, 10 to 200, 11 to 240)
        assertEquals(200, CandidatePaging.focusedId(page, 10))
        assertNull(CandidatePaging.focusedId(page, 1))
    }
}
