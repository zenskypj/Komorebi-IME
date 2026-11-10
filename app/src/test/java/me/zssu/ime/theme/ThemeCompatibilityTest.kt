package me.zssu.ime.theme

import kotlinx.serialization.json.Json
import me.zssu.ime.keyboard.KeyAction
import me.zssu.ime.keyboard.KeyOutput
import me.zssu.ime.keyboard.KeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCompatibilityTest {
    @Test
    fun `legacy themes retain the existing outline or filled keys and do not add effects`() {
        val legacy = """
            {"id":"old","label":"Old","backgroundColor":"#FFFFFF","keyColor":"#FFFFFF",
            "keyPressedColor":"#666666","modifierKeyColor":"#EEEEEE","labelColor":"#000000",
            "flickGuideColor":"#FFFFFF","flickGuideLabelColor":"#000000",
            "flickGuideSelectedLabelColor":"#000000","candidateBackgroundColor":"#FFFFFF",
            "candidateTextColor":"#000000","flatKeys":%s}
        """.trimIndent()
        val outlined = Json.decodeFromString<KeyboardTheme>(legacy.format(true))
        val filled = Json.decodeFromString<KeyboardTheme>(legacy.format(false))
        assertEquals(KeyFrame.OUTLINE, outlined.frameFor(null))
        assertEquals(KeyFrame.FILLED, filled.frameFor(null))
        assertNull(outlined.wallpaper)
        assertTrue(outlined.keywordEffects.isEmpty())
        assertFalse(outlined.keyClickSound)
    }

    @Test
    fun `key override round trips without changing the action and can suppress theme texture`() {
        val key = KeySpec(center = KeyOutput("あ", KeyAction.Input("あ")),
            appearance = KeyAppearance(frame = KeyFrame.NONE, texture = KeyTexture.NONE, showLabel = false,
                fillColor = 0x7f002233))
        val decoded = Json.decodeFromString<KeySpec>(Json.encodeToString(KeySpec.serializer(), key))
        assertEquals(key, decoded)
        val theme = KeyboardTheme.Default.copy(keyFrame = KeyFrame.DIVIDERS, keyTexture = KeyTexture.OPAL)
        assertEquals(KeyFrame.NONE, theme.frameFor(decoded.appearance))
        assertEquals(KeyFrame.DIVIDERS, theme.frameFor(null))
        assertEquals(KeyTexture.NONE, theme.textureFor(decoded.appearance, character = true))
        assertEquals(KeyTexture.OPAL, theme.textureFor(null, character = true))
    }

    @Test
    fun `a new theme keeps art IDs and alpha when exported and reimported`() {
        val theme = KeyboardTheme.Default.copy(
            lineColor = 0x71234567,
            keyFrame = KeyFrame.DIVIDERS,
            wallpaper = ThemeWallpaper.PAPER,
            modifierTexture = KeyTexture.PLUM,
            keywordEffects = listOf(KeywordEffect("梅")),
        )
        val encoded = Json.encodeToString(KeyboardTheme.serializer(), theme)
        assertTrue(encoded.contains("#71234567"))
        assertEquals(theme, Json.decodeFromString<KeyboardTheme>(encoded))
    }
}
