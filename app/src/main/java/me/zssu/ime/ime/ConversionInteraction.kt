package me.zssu.ime.ime

/** The first Convert navigates clauses; a further Convert enters candidate selection. */
internal class ConversionInteraction {
    enum class Phase { INPUT, CLAUSES, CANDIDATES }
    var phase = Phase.INPUT
        private set

    fun observe(converting: Boolean) {
        if (!converting) phase = Phase.INPUT
        else if (phase == Phase.INPUT) phase = Phase.CLAUSES
    }

    fun convertPressed(composing: Boolean, converting: Boolean) {
        phase = when {
            !composing -> Phase.INPUT
            converting -> Phase.CANDIDATES
            else -> Phase.CLAUSES
        }
    }

    fun chooseCandidates() { phase = Phase.CANDIDATES }

    fun resize(explicitResize: Boolean, explicitClauseMove: Boolean): Boolean =
        explicitResize || (!explicitClauseMove && phase == Phase.CANDIDATES)
}
