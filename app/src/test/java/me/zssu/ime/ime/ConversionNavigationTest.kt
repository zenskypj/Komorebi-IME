package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class ConversionNavigationTest {
    private val first = MozcSession.Candidate(4, "私")
    private val second = MozcSession.Candidate(5, "わたし")
    private val whole = MozcSession.Candidate(-2, "私は学校へ", directCommit = true)

    @Test fun repeatedConvertReachesEveryCandidateIncludingWholeSentenceAndWraps() {
        val candidates = listOf(first, second, whole)
        assertEquals(second, ConversionNavigation.nextCandidate(candidates, 0))
        assertEquals(whole, ConversionNavigation.nextCandidate(candidates, 1))
        assertEquals(first, ConversionNavigation.nextCandidate(candidates, 2))
        assertEquals(first, ConversionNavigation.nextCandidate(candidates, -1))
        assertNull(ConversionNavigation.nextCandidate(emptyList(), -1))
    }
    @Test fun nativeRefreshRetainsUniqueWholeSentenceCandidates() {
        val result = ConversionNavigation.preserveDirectCandidates(listOf(first, second), listOf(first, whole, whole))
        assertEquals(listOf(first, second, whole), result)
    }
    @Test fun convertCanReachCandidatesPast120BeforeWrapping() {
        val candidates = (0 until 240).map { MozcSession.Candidate(it, "候補$it") }
        var index = 0
        repeat(239) {
            val next = ConversionNavigation.nextCandidate(candidates, index)
            index = candidates.indexOf(next)
        }
        assertEquals(239, index)
        assertEquals(candidates.first(), ConversionNavigation.nextCandidate(candidates, index))
    }

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
    @Test fun confirmingAHeadWithRemainingReadingAutomaticallyOpensTheNextCandidates() {
        val remaining = MozcSession.State(committedText = "私", preedit = "はがっこうへ", preeditReading = "はがっこうへ")
        assertTrue(ConversionNavigation.advanceAfterCommit(first, remaining, true))
        assertFalse(ConversionNavigation.advanceAfterCommit(whole, remaining, true))
        assertFalse(ConversionNavigation.advanceAfterCommit(first, remaining, false))
        assertFalse(ConversionNavigation.advanceAfterCommit(first, remaining.copy(preedit = ""), true))
        assertFalse(ConversionNavigation.advanceAfterCommit(first, remaining.copy(committedText = ""), true))
        assertFalse(ConversionNavigation.advanceAfterCommit(first, null, true))
    }
}
