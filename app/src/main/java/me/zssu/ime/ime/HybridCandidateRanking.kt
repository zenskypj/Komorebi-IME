package me.zssu.ime.ime

/**
 * Combines the neural and dictionary converters without making either one a single point of
 * failure. Agreement is the strongest automatic signal, explicit user priorities always win, and
 * native Mozc candidates retain their ids so choosing one still participates in normal learning.
 */
internal object HybridCandidateRanking {
    private const val DIRECT_ID_START = -10_000

    fun merge(
        reading: String,
        neuralValues: List<String>,
        mozcCandidates: List<MozcSession.Candidate>,
        priorityLookup: (String, String) -> PriorityMatch,
        limit: Int,
    ): List<MozcSession.Candidate> {
        if (limit <= 0) return emptyList()

        val mozcByText = mozcCandidates.associateBy { it.text }
        val neural = neuralValues.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .mapIndexed { index, value ->
                mozcByText[value]?.copy(priorityMatch = priorityLookup(reading, value))
                    ?: MozcSession.Candidate(
                        id = DIRECT_ID_START - index,
                        text = value,
                        inputReading = reading,
                        sourceReading = reading,
                        priorityMatch = priorityLookup(reading, value),
                        directCommit = true,
                    )
            }
            .toList()
        val neuralTexts = neural.asSequence().map { it.text }.toHashSet()
        val consensusTexts = neuralTexts.intersect(mozcByText.keys)
        val native = mozcCandidates.map { candidate ->
            candidate.copy(priorityMatch = priorityLookup(reading, candidate.text))
        }
        val readingFallback = MozcSession.Candidate(
            id = DIRECT_ID_START - neural.size,
            text = reading,
            inputReading = reading,
            sourceReading = reading,
            priorityMatch = priorityLookup(reading, reading),
            directCommit = true,
        )

        return (neural + native + readingFallback)
            .distinctBy { it.text }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<MozcSession.Candidate>> { indexed ->
                    when {
                        indexed.value.priorityMatch == PriorityMatch.EXACT -> 0
                        indexed.value.priorityMatch == PriorityMatch.SIMILAR -> 1
                        indexed.value.text in consensusTexts -> 2
                        indexed.value.text in neuralTexts -> 3
                        indexed.value.text == reading -> 5
                        else -> 4
                    }
                }.thenBy { it.index }
            )
            .take(limit)
            .map { it.value }
    }
}
