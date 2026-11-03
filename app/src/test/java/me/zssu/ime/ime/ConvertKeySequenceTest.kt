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
    private fun converting(index: Int) = MozcSession.State(
        preedit = "漢字", isConverting = true, focusedCandidateIndex = index,
        candidates = listOf(MozcSession.Candidate(42, "漢字"), MozcSession.Candidate(81, "感じ")),
    )

    @Test fun firstPressRewindsNativeSecondCandidateWithoutCommitting() {
        val keys = mutableListOf<KeyEvent.SpecialKey>()
        val highlights = mutableListOf<Int>()
        val result = ConvertKeySequence.convert(true, false,
            stop = { MozcSession.State(preedit = "かんじ") },
            send = { keys.add(it); converting(1) },
            highlight = { highlights.add(it); converting(0) },
        )
        assertEquals(listOf(KeyEvent.SpecialKey.HENKAN), keys)
        assertEquals(listOf(42), highlights)
        assertEquals(0, result?.focusedCandidateIndex)
        assertEquals("", result?.committedText)
    }

    @Test fun stopTogglingAlreadyOpenedConversionDoesNotAdvanceAgain() {
        val result = ConvertKeySequence.convert(true, false,
            stop = { converting(0) },
            send = { error("must not send another conversion key") },
            highlight = { error("first candidate already focused") },
        )
        assertEquals(0, result?.focusedCandidateIndex)
    }

    @Test fun secondPressAdvancesOnceWithoutResettingFocus() {
        val keys = mutableListOf<KeyEvent.SpecialKey>()
        val result = ConvertKeySequence.convert(true, true,
            stop = { converting(0) },
            send = { keys.add(it); converting(1) },
            highlight = { error("must not rewind a subsequent press") },
        )
        assertEquals(listOf(KeyEvent.SpecialKey.SPACE), keys)
        assertEquals(1, result?.focusedCandidateIndex)
    }

    @Test fun stopTogglingCommitIsNotDiscardedByAnotherKey() {
        val result = ConvertKeySequence.convert(false, false,
            stop = { MozcSession.State(committedText = "abc", consumed = true) },
            send = { error("must preserve committed text") },
            highlight = { error("nothing to highlight") },
        )
        assertEquals("abc", result?.committedText)
    }
}
