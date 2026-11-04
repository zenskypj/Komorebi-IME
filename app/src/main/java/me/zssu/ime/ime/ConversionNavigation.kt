package me.zssu.ime.ime

/** Keeps native segment navigation separate from whole-sentence neural suggestions. */
internal object ConversionNavigation {
    fun advanceAfterCommit(candidate: MozcSession.Candidate, state: MozcSession.State?, japanese: Boolean): Boolean =
        japanese && !candidate.directCommit && state?.hasComposition == true && state.committedText.isNotEmpty()

    fun partialCandidate(
        converting: Boolean,
        segmentCount: Int,
        candidates: List<MozcSession.Candidate>,
        focusedIndex: Int,
    ): MozcSession.Candidate? {
        if (!converting || segmentCount < 2) return null
        return candidates.getOrNull(focusedIndex)?.takeUnless { it.directCommit }
    }

    fun appendNeuralCandidates(
        native: List<MozcSession.Candidate>,
        neural: List<String>,
        reading: String,
        limit: Int,
    ): List<MozcSession.Candidate> {
        val existing = native.map { it.text }.toMutableSet()
        val extra = neural.asSequence().filter { it.isNotBlank() && existing.add(it) }
            .mapIndexed { index, text -> MozcSession.Candidate(-10_000 - index, text, inputReading = reading, directCommit = true) }
            .take((limit - native.size).coerceAtLeast(0)).toList()
        return native + extra
    }
}
