package me.zssu.ime.ime

/**
 * Keeps content the user individually specified in a user or network dictionary ahead of the two
 * neural converters (zenzai-plus and Karukan).
 *
 * "Individually specified" is deliberately narrow: an entry registered for the current reading in
 * a user/network dictionary, or an exact ★ priority pin. Mozc's own vocabulary, its predictions
 * and corrections, and the neural output are untouched, so a neural candidate still leads whenever
 * the user has not registered or pinned an entry. The text may come from the personal user
 * dictionary or from a downloaded system dictionary — the explicit registration is what counts.
 */
internal object DictionaryPriority {
    /** Client-side id for a live candidate synthesized from a user dictionary entry. */
    const val REGISTERED_DICTIONARY_ID = -100

    fun isSpecified(candidate: MozcSession.Candidate, reading: String): Boolean {
        if (candidate.priorityMatch == PriorityMatch.EXACT) return true
        if (!sameReading(candidate, reading)) return false
        return candidate.fromUserDictionary || candidate.dictionaryCompletion || candidate.id == REGISTERED_DICTIONARY_ID
    }

    /** The first individually specified candidate for [reading], in list order. */
    fun firstSpecified(candidates: List<MozcSession.Candidate>, reading: String): MozcSession.Candidate? =
        candidates.firstOrNull { it.priorityMatch == PriorityMatch.EXACT }
            ?: candidates.firstOrNull { isSpecified(it, reading) }

    /**
     * Orders the live candidate list. Without a specified entry the neural candidate leads exactly
     * as before. With one, the specified entry stays in front and the neural candidate follows it,
     * so the neural converter can contribute but cannot override the user's own entry.
     */
    fun order(
        base: List<MozcSession.Candidate>,
        neural: MozcSession.Candidate?,
        reading: String,
    ): List<MozcSession.Candidate> {
        val specified = firstSpecified(base, reading)
        if (specified == null) {
            return if (neural == null) base else ZenzaiConversion.candidates(base, neural, reading)
        }
        val rest = base.filterNot { it.id == specified.id && it.text == specified.text }
        return if (neural == null || neural.text == specified.text) listOf(specified) + rest
        else listOf(specified) + ZenzaiConversion.candidates(rest, neural, reading)
    }

    private fun sameReading(candidate: MozcSession.Candidate, reading: String): Boolean {
        val input = ReadingSimilarity.normalize(reading)
        if (input.isEmpty()) return false
        // inputReading always contains the current keystrokes, including incomplete prefixes.
        // Only the actual dictionary reading can establish a complete registration match.
        return ReadingSimilarity.normalize(candidate.sourceReading.ifEmpty { candidate.inputReading }) == input
    }
}
