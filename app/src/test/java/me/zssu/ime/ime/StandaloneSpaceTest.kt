package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class StandaloneSpaceTest {
    @Test fun asciiCompositionCommitsTextAndSpace() {
        assertEquals(StandaloneSpace.Target.COMMIT_AND_SPACE, StandaloneSpace.route(true, false))
    }
    @Test fun japaneseCompositionStillStartsNeuralConversion() {
        assertEquals(StandaloneSpace.Target.NEURAL_CONVERSION, StandaloneSpace.route(true, true))
    }
    @Test fun idleInputDelegatesSpaceToEditor() {
        assertEquals(StandaloneSpace.Target.EDITOR_SPACE, StandaloneSpace.route(false, false))
        assertEquals(StandaloneSpace.Target.EDITOR_SPACE, StandaloneSpace.route(false, true))
    }
}
