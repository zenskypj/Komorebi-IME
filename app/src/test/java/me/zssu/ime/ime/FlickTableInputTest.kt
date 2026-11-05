package me.zssu.ime.ime

import me.zssu.ime.keyboard.InputStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlickTableInputTest {
    @Test fun qwertyAndNumericInputDoNotSendAnExtraStopCommandPerCharacter() {
        for (style in listOf(InputStyle.QWERTY_HIRAGANA, InputStyle.QWERTY_HALFWIDTH_ASCII,
            InputStyle.GODAN_HIRAGANA, InputStyle.FLICK_NUMBER)) {
            assertFalse(style.name, FlickTableInput.shouldStopBeforeText(style, false, "a"))
        }
    }

    @Test fun repeatedFlickKeysKeepTheirStopButModifiersAndToggleInputDoNot() {
        assertTrue(FlickTableInput.shouldStopBeforeText(InputStyle.FLICK_HIRAGANA, false, "1"))
        assertTrue(FlickTableInput.shouldStopBeforeText(InputStyle.TOGGLE_FLICK_NUMBER, false, "1"))
        assertTrue(FlickTableInput.shouldStopBeforeText(InputStyle.TOGGLE_FLICK_HALFWIDTH_ASCII, false, "a"))
        assertFalse(FlickTableInput.shouldStopBeforeText(InputStyle.FLICK_HIRAGANA, false, "*"))
        assertFalse(FlickTableInput.shouldStopBeforeText(InputStyle.FLICK_HIRAGANA, true, "1"))
    }

    @Test
    fun `kana modifier keys keep the preceding table chunk open`() {
        for (key in listOf("*", "[", "]", "`")) {
            assertTrue(
                "$key must modify the preceding kana",
                FlickTableInput.isModifierKey(InputStyle.FLICK_HIRAGANA, key),
            )
        }
    }

    @Test
    fun `ordinary flick outputs start a new table chunk`() {
        for (key in listOf("1", "a", "_", "@", "☆")) {
            assertFalse(
                "$key must start a new character",
                FlickTableInput.isModifierKey(InputStyle.FLICK_HIRAGANA, key),
            )
        }
    }

    @Test
    fun `same punctuation keys remain literal on the symbol plane`() {
        for (key in listOf("*", "[", "]", "`")) {
            assertFalse(
                "$key must be punctuation on the number table",
                FlickTableInput.isModifierKey(InputStyle.TOGGLE_FLICK_NUMBER, key),
            )
        }
    }
}
