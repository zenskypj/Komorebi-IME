package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionUpdateTest {
    @Test fun `delayed cursor updates are recognized without hiding current edits`() {
        assertTrue(SelectionUpdate.isStale(3, 3, 5, 5))
        assertFalse(SelectionUpdate.isStale(5, 5, 5, 5))
        assertFalse(SelectionUpdate.isStale(3, 3, -1, -1))
    }


    @Test
    fun `own composing update keeps native composition`() {
        assertFalse(
            SelectionUpdate.invalidatesComposition(
                isComposing = true,
                newSelectionStart = 5,
                newSelectionEnd = 5,
                composingStart = 2,
                composingEnd = 5,
                preeditCursor = 3,
            )
        )
    }

    @Test
    fun `editor clearing composing span resets native composition`() {
        assertTrue(
            SelectionUpdate.invalidatesComposition(
                isComposing = true,
                newSelectionStart = 0,
                newSelectionEnd = 0,
                composingStart = -1,
                composingEnd = -1,
                preeditCursor = 0,
            )
        )
    }

    @Test
    fun `caret move or range selection resets native composition`() {
        assertTrue(
            SelectionUpdate.invalidatesComposition(
                isComposing = true,
                newSelectionStart = 3,
                newSelectionEnd = 3,
                composingStart = 0,
                composingEnd = 5,
                preeditCursor = 5,
            )
        )
        assertTrue(
            SelectionUpdate.invalidatesComposition(
                isComposing = true,
                newSelectionStart = 2,
                newSelectionEnd = 5,
                composingStart = 0,
                composingEnd = 5,
                preeditCursor = 5,
            )
        )
    }

    @Test
    fun `selection changes are irrelevant without a native composition`() {
        assertFalse(
            SelectionUpdate.invalidatesComposition(
                isComposing = false,
                newSelectionStart = 1,
                newSelectionEnd = 4,
                composingStart = -1,
                composingEnd = -1,
                preeditCursor = 0,
            )
        )
    }

    @Test
    fun `caret at mozc cursor inside composition stays synchronized`() {
        assertFalse(
            SelectionUpdate.invalidatesComposition(
                isComposing = true,
                newSelectionStart = 3,
                newSelectionEnd = 3,
                composingStart = 1,
                composingEnd = 6,
                preeditCursor = 2,
            )
        )
    }
    @Test fun `conversion clause focus notifications inside the span do not cancel conversion`() {
        for (caret in 10..20) {
            assertFalse(SelectionUpdate.invalidatesComposition(true, caret, caret, 10, 20, 10, converting = true))
        }
        assertTrue(SelectionUpdate.invalidatesComposition(true, 9, 9, 10, 20, 10, converting = true))
        assertTrue(SelectionUpdate.invalidatesComposition(true, 21, 21, 10, 20, 10, converting = true))
        assertTrue(SelectionUpdate.invalidatesComposition(true, 10, 12, 10, 20, 10, converting = true))
        assertTrue(SelectionUpdate.invalidatesComposition(true, 20, 20, -1, -1, 10, converting = true))
        assertTrue(SelectionUpdate.invalidatesComposition(true, 10, 10, 10, 10, 10, converting = true))
    }
}
