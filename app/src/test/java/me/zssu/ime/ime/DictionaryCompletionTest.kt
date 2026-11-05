package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class DictionaryCompletionTest {
    private fun completion(input: String, source: String = "まいくろそふと", value: String = "Microsoft") =
        MozcSession.Candidate(-50000, value, inputReading = input, sourceReading = source,
            prediction = input != source, directCommit = true, dictionaryCompletion = true)

    private fun ranked(input: String) = CandidateRanking.rankLiveJapaneseSuggestions(listOf(
        MozcSession.Candidate(1, "通常", inputReading = input, sourceReading = input),
        MozcSession.Candidate(2, "別候補", inputReading = input, sourceReading = input),
        completion(input),
    ), 4)

    @Test fun earlyCompletionSurvivesKanaFallbacksAndRisesAsReadingApproaches() {
        val early = ranked("まい")
        val late = ranked("まいくろそふ")
        assertEquals(2, early.indexOfFirst { it.text == "Microsoft" })
        assertEquals("Microsoft", late.first().text)
        assertTrue(early.any { it.text == "まい" })
        assertTrue(early.any { it.text == "マイ" })
        assertTrue(early.any { it.text == "ﾏｲ" })
    }

    @Test fun aDifferentReadingIsNotPromotedAsAPrefix() {
        val input = "さい"
        val ranked = CandidateRanking.rankLiveJapaneseSuggestions(listOf(
            completion(input, "さっぽろ", "札幌"),
            MozcSession.Candidate(1, "才", inputReading = input, sourceReading = input),
        ), 7)
        assertEquals("才", ranked.first().text)
    }

    @Test fun latinEnglishDictionaryPredictionRemainsBelowExactJapanese() {
        val input = "ぷろ"
        val ranked = CandidateRanking.rankLiveJapaneseSuggestions(listOf(
            MozcSession.Candidate(1, "program", inputReading = input, sourceReading = "ぷろぐらむ",
                fromUserDictionary = true, prediction = true),
            MozcSession.Candidate(2, "プロ", inputReading = input, sourceReading = input),
        ), 7)
        assertEquals("プロ", ranked.first().text)
    }

    @Test fun inputReadingDoesNotTurnAnIncompleteRegistrationIntoAnExactMatch() {
        val prefix = MozcSession.Candidate(1, "さいたま市", fromUserDictionary = true,
            inputReading = "さい", sourceReading = "さいたまし", prediction = true)
        assertFalse(DictionaryPriority.isSpecified(prefix, "さい"))
        assertTrue(DictionaryPriority.isSpecified(prefix.copy(inputReading = "さいたまし"), "さいたまし"))
    }

    @Test fun prefixDoesNotForceItsWayAboveAiButCompleteRegistrationDoes() {
        val prefix = completion("まい")
        val ai = MozcSession.Candidate(ZenzaiConversion.CANDIDATE_ID, "舞", directCommit = true)
        assertEquals("舞", DictionaryPriority.order(listOf(prefix), ai, "まい").first().text)
        val complete = completion("まいくろそふと")
        assertEquals("Microsoft", DictionaryPriority.order(listOf(complete), ai, "まいくろそふと").first().text)
    }

    @Test fun explicitPinsAreNotDisplacedByCompletionVisibilityReservation() {
        val pins = (1..3).map { MozcSession.Candidate(it, "指定$it", inputReading = "まい",
            sourceReading = "まい", priorityMatch = PriorityMatch.EXACT) }
        val result = CandidateRanking.rankLiveJapaneseSuggestions(pins + completion("まい"), 8)
        assertEquals(listOf("指定1", "指定2", "指定3"), result.take(3).map { it.text })
    }
}
