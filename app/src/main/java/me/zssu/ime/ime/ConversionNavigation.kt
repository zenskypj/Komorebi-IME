package me.zssu.ime.ime

/** Keeps native segment navigation separate from whole-sentence neural suggestions. */
internal object ConversionNavigation {
    fun nextCandidate(candidates: List<MozcSession.Candidate>, focusedIndex: Int): MozcSession.Candidate? =
        if (candidates.isEmpty()) null else candidates[(focusedIndex.takeIf { it in candidates.indices }?.plus(1) ?: 0) % candidates.size]

    fun preserveDirectCandidates(native: List<MozcSession.Candidate>, previous: List<MozcSession.Candidate>): List<MozcSession.Candidate> {
        val ids = native.mapTo(hashSetOf()) { it.id }
        val texts = native.mapTo(hashSetOf()) { it.text }
        return native + previous.filter { it.directCommit && ids.add(it.id) && texts.add(it.text) }
    }
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
