package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridCandidateRankingTest {

    @Test
    fun `converter agreement outranks a neural-only guess`() {
        val result = HybridCandidateRanking.merge(
            reading = "きょうはいしゃにいく",
            neuralValues = listOf("今日は医者に行く", "今日は会社に行く"),
            mozcCandidates = listOf(
                candidate(7, "今日は会社に行く"),
                candidate(8, "今日、歯医者に行く"),
            ),
            priorityLookup = { _, _ -> PriorityMatch.NONE },
            limit = 5,
        )

        assertEquals("今日は会社に行く", result.first().text)
        assertFalse(result.first().directCommit)
    }

    @Test
    fun `explicit priority remains stronger than converter agreement`() {
        val result = HybridCandidateRanking.merge(
            reading = "こうせい",
            neuralValues = listOf("構成", "校正"),
            mozcCandidates = listOf(candidate(1, "構成"), candidate(2, "校正")),
            priorityLookup = { _, value ->
                if (value == "校正") PriorityMatch.EXACT else PriorityMatch.NONE
            },
            limit = 4,
        )

        assertEquals(listOf("校正", "構成", "こうせい"), result.map { it.text })
    }

    @Test
    fun `neural only candidates commit directly while Mozc ids are retained`() {
        val result = HybridCandidateRanking.merge(
            reading = "しんせい",
            neuralValues = listOf("申請", "新生"),
            mozcCandidates = listOf(candidate(42, "申請")),
            priorityLookup = { _, _ -> PriorityMatch.NONE },
            limit = 4,
        )

        assertEquals(42, result[0].id)
        assertFalse(result[0].directCommit)
        assertTrue(result[1].directCommit)
        assertEquals("新生", result[1].text)
    }

    private fun candidate(id: Int, text: String) = MozcSession.Candidate(
        id = id,
        text = text,
        inputReading = "reading",
        sourceReading = "reading",
    )
}
