package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class ConversionNavigationTest {
    private val first = MozcSession.Candidate(4, "私")
    private val second = MozcSession.Candidate(5, "わたし")
    private val whole = MozcSession.Candidate(-2, "私は学校へ", directCommit = true)

    @Test fun enterDuringMultiSegmentConversionCommitsTheFocusedNativeCandidate() {
        assertEquals(second, ConversionNavigation.partialCandidate(true, 2, listOf(first, second), 1))
    }
    @Test fun singleSegmentAndUnfocusedOrWholeSentenceCandidatesAreNotPartiallyCommitted() {
        assertNull(ConversionNavigation.partialCandidate(true, 1, listOf(first), 0))
        assertNull(ConversionNavigation.partialCandidate(false, 2, listOf(first), 0))
        assertNull(ConversionNavigation.partialCandidate(true, 2, listOf(first), -1))
        assertNull(ConversionNavigation.partialCandidate(true, 2, listOf(whole), 0))
    }
    @Test fun neuralSentenceChoicesDoNotReplaceOrReorderNativeSegmentCandidates() {
        val result = ConversionNavigation.appendNeuralCandidates(listOf(first, second), listOf("私は学校へ", "私", "私は学校へ"), "わたしは", 7)
        assertEquals(listOf("私", "わたし", "私は学校へ"), result.map { it.text })
        assertFalse(result[0].directCommit)
        assertTrue(result[2].directCommit)
    }
}
