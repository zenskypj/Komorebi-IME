package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class ConversionInteractionTest {
    @Test fun firstConversionMovesClausesAndSecondConversionResizesWithArrows() {
        val interaction = ConversionInteraction()
        interaction.convertPressed(composing = true, converting = false)
        interaction.observe(true)
        assertFalse(interaction.resize(false, false))
        interaction.convertPressed(composing = true, converting = true)
        interaction.observe(true)
        assertTrue(interaction.resize(false, false))
        interaction.observe(true) // Native Resize temporarily hides its window, not its state.
        assertTrue(interaction.resize(false, false))
    }
    @Test fun explicitClauseButtonsRemainClauseMovesInCandidateSelection() {
        val interaction = ConversionInteraction()
        interaction.chooseCandidates()
        assertFalse(interaction.resize(false, true))
        assertTrue(interaction.resize(true, true))
    }
    @Test fun newInputOrCommitResetsCandidateSelection() {
        val interaction = ConversionInteraction()
        interaction.chooseCandidates()
        interaction.observe(false)
        assertEquals(ConversionInteraction.Phase.INPUT, interaction.phase)
        interaction.convertPressed(true, false)
        assertEquals(ConversionInteraction.Phase.CLAUSES, interaction.phase)
        interaction.convertPressed(false, false)
        assertEquals(ConversionInteraction.Phase.INPUT, interaction.phase)
    }
    @Test fun automaticContinuationIsAlreadyInCandidateSelectionWithoutAnotherPress() {
        val interaction = ConversionInteraction()
        interaction.convertPressed(true, false)
        assertEquals(ConversionInteraction.Phase.CLAUSES, interaction.phase)
        interaction.committed(hasRemaining = true)
        interaction.observe(false) // A partial native commit can return a remaining reading.
        interaction.chooseCandidates() // Auto-convert that remaining reading.
        interaction.observe(true)
        assertEquals(ConversionInteraction.Phase.CANDIDATES, interaction.phase)
        assertTrue(interaction.resize(false, false))
        interaction.committed(hasRemaining = false)
        interaction.observe(false)
        interaction.convertPressed(true, false)
        assertEquals(ConversionInteraction.Phase.CLAUSES, interaction.phase)
    }
}
