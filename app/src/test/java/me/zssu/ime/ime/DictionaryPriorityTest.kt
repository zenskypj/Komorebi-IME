package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryPriorityTest {

    private fun mozc(id: Int, text: String, reading: String = "えーあい") =
        MozcSession.Candidate(id = id, text = text, inputReading = reading)

    private fun userDict(id: Int, text: String, reading: String) =
        MozcSession.Candidate(id = id, text = text, fromUserDictionary = true, inputReading = reading, personalRegistration = true)

    private fun registered(text: String, reading: String) =
        MozcSession.Candidate(
            id = DictionaryPriority.REGISTERED_DICTIONARY_ID,
            text = text,
            inputReading = reading,
            directCommit = true,
        )

    private fun pinned(id: Int, text: String, reading: String) =
        MozcSession.Candidate(id = id, text = text, inputReading = reading, priorityMatch = PriorityMatch.EXACT)

    @Test
    fun exactPriorityPinIsSpecified() {
        assertTrue(DictionaryPriority.isSpecified(pinned(3, "AI", "えーあい"), "えーあい"))
    }

    @Test
    fun userDictionaryEntryForTheReadingIsSpecified() {
        assertTrue(DictionaryPriority.isSpecified(userDict(4, "AI", "えーあい"), "えーあい"))
    }

    @Test
    fun registeredDictionaryCandidateIsSpecified() {
        assertTrue(DictionaryPriority.isSpecified(registered("AI", "えーあい"), "えーあい"))
    }

    @Test
    fun plainMozcVocabularyAndAiOutputAreNotSpecified() {
        assertFalse(DictionaryPriority.isSpecified(mozc(1, "愛"), "えーあい"))
        assertFalse(DictionaryPriority.isSpecified(MozcSession.Candidate(ZenzaiConversion.CANDIDATE_ID, "AI"), "えーあい"))
    }

    @Test
    fun matchingIsReadingSensitive() {
        assertFalse(DictionaryPriority.isSpecified(userDict(4, "AI", "ええあい"), "えーあい"))
    }

    @Test
    fun firstSpecifiedPicksTheSpecifiedEntryInListOrder() {
        val candidates = listOf(mozc(1, "愛"), userDict(4, "AI", "えーあい"), mozc(2, "挨拶"))
        assertEquals("AI", DictionaryPriority.firstSpecified(candidates, "えーあい")?.text)
        assertNull(DictionaryPriority.firstSpecified(listOf(mozc(1, "愛")), "えーあい"))
    }

    @Test
    fun neuralLeadsWhenNothingIsSpecified() {
        val base = listOf(mozc(1, "愛"), mozc(2, "挨拶"))
        val neural = MozcSession.Candidate(ZenzaiConversion.CANDIDATE_ID, "AI")
        assertEquals(listOf("AI", "愛", "挨拶"), DictionaryPriority.order(base, neural, "えーあい").map { it.text })
    }

    @Test
    fun specifiedEntryLeadsAndNeuralFollowsIt() {
        val base = listOf(mozc(1, "愛"), userDict(4, "AI", "えーあい"), mozc(2, "挨拶"))
        val neural = MozcSession.Candidate(ZenzaiConversion.CANDIDATE_ID, "あい")
        assertEquals(listOf("AI", "あい", "愛", "挨拶"), DictionaryPriority.order(base, neural, "えーあい").map { it.text })
    }

    @Test
    fun specifiedEntryLeadsWithoutNeural() {
        val base = listOf(mozc(1, "愛"), userDict(4, "AI", "えーあい"))
        assertEquals(listOf("AI", "愛"), DictionaryPriority.order(base, null, "えーあい").map { it.text })
    }

    @Test
    fun baseIsUnchangedWithoutNeuralOrSpecified() {
        val base = listOf(mozc(1, "愛"), mozc(2, "挨拶"))
        assertEquals(base, DictionaryPriority.order(base, null, "えーあい"))
    }
}
