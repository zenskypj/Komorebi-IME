package me.zssu.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalCursorFlicksTest {
    @Test fun arrowsGainVerticalFlicksWithoutOverwritingCustomActions() {
        val custom = KeyOutput("特別", KeyAction.Input("x"))
        val left = KeySpec(center = KeyOutput("◀", KeyAction.MoveCursor(-1)), up = custom, repeatable = true)
        val right = KeySpec(center = KeyOutput("▶", KeyAction.MoveCursor(1)), repeatable = true)
        val letter = KeySpec(center = KeyOutput("あ", KeyAction.Input("あ")))
        val layout = KeyboardLayout("test", "test", rows = listOf(KeyRow(listOf(left, right, letter))))
        val keys = layout.withVerticalCursorFlicks().rows[0].keys
        assertEquals(custom, keys[0].up)
        assertEquals(KeyAction.MoveCursor(1, vertical = true), keys[0].down?.action)
        assertEquals(KeyAction.MoveCursor(-1, vertical = true), keys[1].up?.action)
        assertEquals(KeyAction.MoveCursor(1, vertical = true), keys[1].down?.action)
        assertTrue(keys[1].hasFlicks)
        assertFalse(keys[2].hasFlicks)
        assertEquals(FlickDirection.UP, FlickResolver.resolve(keys[1], 0f, -40f, 20f))
        assertEquals(FlickDirection.DOWN, FlickResolver.resolve(keys[1], 0f, 40f, 20f))
    }
}
