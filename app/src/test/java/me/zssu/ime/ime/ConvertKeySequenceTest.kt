package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent

class ConvertKeySequenceTest {
    @Test fun composingTriesHenkanBeforeSpace() {
        assertEquals(
            listOf(KeyEvent.SpecialKey.HENKAN, KeyEvent.SpecialKey.SPACE),
            ConvertKeySequence.order(composing = true),
        )
    }

    @Test fun idleOnlySendsSpace() {
        assertEquals(
            listOf(KeyEvent.SpecialKey.SPACE),
            ConvertKeySequence.order(composing = false),
        )
    }

    @Test fun conversionCountsAsReached() {
        assertTrue(
            ConvertKeySequence.reachedConversion(
                MozcSession.State(preedit = "かんじ", isConverting = true),
            ),
        )
        assertFalse(
            ConvertKeySequence.reachedConversion(
                MozcSession.State(preedit = "かんじ"),
            ),
        )
        assertFalse(ConvertKeySequence.reachedConversion(null))
    }

    @Test fun retryOnlyWhileSomethingIsStillComposing() {
        assertTrue(ConvertKeySequence.canRetry(null))
        assertTrue(ConvertKeySequence.canRetry(MozcSession.State(preedit = "かんじ")))
        // HENKAN/SPACE committed the composition, so a follow-up Space would only add stray text.
        assertFalse(ConvertKeySequence.canRetry(MozcSession.State(committedText = "漢字")))
    }
}
