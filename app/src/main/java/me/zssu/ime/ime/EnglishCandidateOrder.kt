package me.zssu.ime.ime

/** English spellings of kana readings are alternatives, even with an exact dictionary match. */
internal object EnglishCandidateOrder {
    fun isFallback(candidate: MozcSession.Candidate): Boolean {
        if (candidate.priorityMatch == PriorityMatch.EXACT || candidate.personalRegistration ||
            candidate.id == DictionaryPriority.REGISTERED_DICTIONARY_ID) return false
        // Prefix suggestions for named entities retain rc.40's gradual completion behavior.
        if (candidate.dictionaryCompletion && candidate.prediction) return false
        val reading = ReadingSimilarity.normalize(candidate.inputReading)
        if (reading.none { it in '\u3041'..'\u3096' }) return false
        val text = java.text.Normalizer.normalize(candidate.text, java.text.Normalizer.Form.NFKC)
        return text.any { it in 'A'..'Z' || it in 'a'..'z' } && text.none {
            it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' || it == '々' || it == '〆'
        }
    }

    /** Stable partition: preserve native IDs and relative order within both groups. */
    fun order(candidates: List<MozcSession.Candidate>): List<MozcSession.Candidate> {
        val (english, other) = candidates.partition(::isFallback)
        return other + english
    }
}
