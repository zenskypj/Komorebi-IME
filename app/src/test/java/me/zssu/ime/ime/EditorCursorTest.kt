package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorCursorTest {
    @Test fun skipsSupplementaryEmojiInBothDirections() {
        val text = "あ😀い"
        assertEquals(3, EditorCursor.step(text, 1, 1))
        assertEquals(1, EditorCursor.step(text, 3, -1))
    }
    @Test fun movesAcrossLinesPreservingCodePointColumnAndClamping() {
        val text = "あ😀い\nう\nえ😀お"
        assertEquals(6, EditorCursor.line(text, 3, 1))
        assertEquals(1, EditorCursor.line(text, 6, -1))
        assertEquals(8, EditorCursor.line(text, 6, 1))
        assertEquals(0, EditorCursor.line(text, 0, -1))
        assertEquals(text.length, EditorCursor.line(text, text.length, 1))
    }
    @Test fun stopsAtEdgesAndHandlesMissingSelection() {
        assertEquals(0, EditorCursor.step("", -1, -1))
        assertEquals(0, EditorCursor.step("あ", 0, -1))
        assertEquals(1, EditorCursor.step("あ", 1, 1))
    }
}
