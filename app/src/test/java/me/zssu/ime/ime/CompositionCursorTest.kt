package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class CompositionCursorTest {
    @Test fun clauseResizingNeverRestoresAnAbsoluteEditorCaret() {
        // The editor may still expose the old length/caret after each replacement.
        val stale = CompositionCursor.Snapshot("前文今日はいい天気です後文", 500, 2, 10)
        for (preedit in listOf("今日はいい天気です", "今日は良い天気です", "きょうはいい天気です", "今日はいい天気です")) {
            for (nativeCursor in 0..preedit.length) {
                val plan = CompositionCursor.plan(preedit, nativeCursor, true, "今日はいい天気です", stale)
                assertEquals(preedit.length, plan.displayOffset)
                assertNull(plan.absoluteSelection)
            }
        }
    }

    @Test fun readingCursorUsesTheVerifiedSpanBeforeReplacement() {
        val before = CompositionCursor.Snapshot("前文かんじ後文", 100, 2, 5)
        assertEquals(CompositionCursor.Plan(1, 103), CompositionCursor.plan("かんじ", 1, false, "かんじ", before))
    }

    @Test fun missingOrStaleSpanDoesNotInventAComposingStart() {
        val invalid = listOf(null,
            CompositionCursor.Snapshot("前文かんじ後文", 100, -1, -1),
            CompositionCursor.Snapshot("前文別の文後文", 100, 2, 5),
            CompositionCursor.Snapshot("かんじ", 100, 0, 99),
            CompositionCursor.Snapshot("かんじ", -1, 0, 3))
        for (before in invalid) assertEquals(CompositionCursor.Plan(3, null), CompositionCursor.plan("かんじ", 1, false, "かんじ", before))
    }

    @Test fun partialExtractionOffsetAndSupplementaryCharactersStayInsideTheSpan() {
        val text = "あ😀い"
        val before = CompositionCursor.Snapshot("前$text", 1000, 1, 5)
        assertEquals(CompositionCursor.Plan(1, 1002), CompositionCursor.plan(text, 2, false, text, before))
        assertEquals(CompositionCursor.Plan(0, 1001), CompositionCursor.plan(text, -99, false, text, before))
        assertEquals(CompositionCursor.Plan(text.length, null), CompositionCursor.plan(text, 99, false, text, before))
    }

    @Test fun overflowingEditorOffsetsCannotWrapToNegativeSelection() {
        val before = CompositionCursor.Snapshot("かんじ", Int.MAX_VALUE - 1, 0, 3)
        assertEquals(CompositionCursor.Plan(3, null), CompositionCursor.plan("かんじ", 1, false, "かんじ", before))
    }
    @org.junit.Test fun detectsReplacementInNonemptyEditor() {
        org.junit.Assert.assertFalse(CompositionCursor.matchesAtCaret("こんにち", 4, false, "こんにちは！", 6, 6))
        org.junit.Assert.assertTrue(CompositionCursor.matchesAtCaret("こんにち", 4, false, "先:こんにち", 6, 6))
        org.junit.Assert.assertTrue(CompositionCursor.matchesAtCaret("変換中", 3, true, "先:変換中", 4, 4))
    }
}
