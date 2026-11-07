package me.zssu.ime.ime

/**
 * Decides when Enter may commit the whole composition in one action.
 *
 * The first Convert press opens a conversion but has not yet expressed any clause-level choice.
 * Confirming there should commit the sentence as a whole. The second Convert press only enters
 * candidate selection; it must not advance the provisional candidate. Any further conversion,
 * clause movement, candidate selection, or editing clears the armed state and restores the
 * per-clause behaviour.
 */
internal object WholeSentenceConfirm {
    fun arm(composing: Boolean, wasConverting: Boolean): Boolean = composing && !wasConverting

    fun commitsAll(converting: Boolean, armed: Boolean, segmentCount: Int): Boolean =
        converting && armed && segmentCount > 1

    /** The untouched second Convert opens candidate selection without advancing the candidate. */
    fun beginsCandidateSelection(converting: Boolean, armed: Boolean): Boolean = converting && armed
}
