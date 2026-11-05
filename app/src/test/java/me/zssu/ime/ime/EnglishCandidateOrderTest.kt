package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class EnglishCandidateOrderTest {
    private fun word(id: Int, value: String, user: Boolean = false) = MozcSession.Candidate(
        id, value, inputReading = "しーる", sourceReading = "しーる", fromUserDictionary = user)

    @Test fun bundledAndDownloadedEnglishStayBehindJapaneseEvenAtExactMatch() {
        val english = listOf(word(1, "seal"), word(2, "SEAL", user = true),
            word(-40000, "ｓｅａｌ").copy(directCommit = true))
        val japanese = listOf(word(3, "シール"), word(4, "印"), word(5, "しーる"))
        val result = EnglishCandidateOrder.order(english + japanese)
        assertEquals(japanese + english, result)
        assertFalse(DictionaryPriority.isSpecified(english[1], "しーる"))
        assertEquals(japanese + english, DictionaryPriority.order(english + japanese, null, "しーる"))
    }

    @Test fun englishCannotReturnToTheFrontThroughDictionaryPriority() {
        val english = word(1, "seal", user = true)
        val japanese = word(2, "シール")
        val ai = word(ZenzaiConversion.CANDIDATE_ID, "印")
        assertEquals(listOf(ai, japanese, english), DictionaryPriority.order(listOf(english, japanese), ai, "しーる"))
    }

    @Test fun kanaFallbacksAlsoPrecedeEnglishInLiveSuggestions() {
        val result = CandidateRanking.rankLiveJapaneseSuggestions(
            listOf(word(1, "seal", user = true), word(2, "印")), 8)
        val english = result.indexOfFirst { it.text == "seal" }
        for (text in listOf("印", "しーる", "シール", "ｼｰﾙ")) {
            assertTrue(text, result.indexOfFirst { it.text == text } in 0 until english)
        }
    }

    @Test fun explicitPinAndPersonalRegistrationCanStillOverrideAi() {
        val ai = word(ZenzaiConversion.CANDIDATE_ID, "印")
        val pinned = word(1, "seal").copy(priorityMatch = PriorityMatch.EXACT)
        val personal = word(2, "seal").copy(dictionaryCompletion = true, personalRegistration = true)
        for (chosen in listOf(pinned, personal)) {
            assertFalse(EnglishCandidateOrder.isFallback(chosen))
            assertEquals(chosen, DictionaryPriority.order(listOf(chosen), ai, "しーる").first())
        }
    }

    @Test fun namedEntityCompletionDoesNotPromoteExactEnglishOverKatakana() {
        val bundled = word(1, "SEAL").copy(dictionaryCompletion = true)
        assertTrue(EnglishCandidateOrder.isFallback(bundled))
        assertFalse(DictionaryPriority.isSpecified(bundled, "しーる"))
        assertFalse(EnglishCandidateOrder.isFallback(bundled.copy(prediction = true, sourceReading = "しーるど")))
    }

    @Test fun latinInputAndJapaneseNamesRetainTheirOrder() {
        val latin = listOf(word(1, "seal"), word(2, "SEAL")).map { it.copy(inputReading = "seal") }
        assertEquals(latin, EnglishCandidateOrder.order(latin))
        assertFalse(EnglishCandidateOrder.isFallback(word(3, "AI研究所")))
    }

    @Test fun firstConversionHighlightsJapaneseUsingItsOriginalNativeId() {
        val candidates = EnglishCandidateOrder.order(listOf(word(42, "seal"), word(73, "シール")))
        val converted = MozcSession.State(preedit = "seal", preeditReading = "しーる",
            candidates = candidates, focusedCandidateIndex = 1, isConverting = true)
        var selected: Int? = null
        val result = ConvertKeySequence.convert(composing = true, wasConverting = false,
            stop = { MozcSession.State(preedit = "しーる") }, send = { converted },
            highlight = { id -> selected = id; converted.copy(preedit = "シール", focusedCandidateIndex = 0) })
        assertEquals(73, selected)
        assertEquals("シール", result?.preedit)
    }
}
