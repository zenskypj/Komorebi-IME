package me.zssu.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KeyboardStyleTest {
    private val symbol = KeySpec(
        center = KeyOutput("?123", KeyAction.SwitchLayout("qwerty_symbol")),
    )
    private val kana = KeySpec(
        center = KeyOutput("かな", KeyAction.SwitchLayout("qwerty_kana")),
    )
    private val source = KeyboardLayout(
        id = "qwerty_ascii",
        label = "English",
        rows = listOf(KeyRow(listOf(symbol, kana))),
    )

    @Test
    fun flickKeepsEverythingOnTheFlickPad() {
        with(KeyboardStyle.FLICK) {
            assertEquals("flick_kana", defaultLayoutId)
            assertEquals("flick_kana", resolve("qwerty_kana"))
            assertEquals("flick_ascii", resolve("qwerty_ascii"))
        }
    }

    @Test
    fun qwertyKeepsEverythingOnQwerty() {
        with(KeyboardStyle.QWERTY) {
            assertEquals("qwerty_kana", defaultLayoutId)
            assertEquals("qwerty_kana", resolve("flick_kana"))
            assertEquals("qwerty_ascii", resolve("flick_ascii"))
        }
    }

    @Test
    fun mixedSplitsKanaFromLatin() {
        with(KeyboardStyle.MIXED) {
            assertEquals("flick_kana", defaultLayoutId)
            assertEquals("qwerty_ascii", resolve("flick_ascii"))
            assertEquals("flick_kana", resolve("qwerty_kana"))
        }
    }

    @Test
    fun kanaOnlyQwertyUsesFlickForEnglishAndResolvesBothSwitchDirections() {
        with(KeyboardStyle.KANA_QWERTY) {
            assertEquals("qwerty_kana", defaultLayoutId)
            assertEquals("qwerty_kana", resolve("flick_kana"))
            assertEquals("flick_ascii", resolve("qwerty_ascii"))
            assertEquals("qwerty_kana", resolve("qwerty_kana"))
            assertEquals("flick_ascii", resolve("flick_ascii"))
        }
    }

    @Test
    fun symbolPagesAreNeverRedirected() {
        for (style in KeyboardStyle.entries) {
            for (id in listOf("flick_symbol", "qwerty_symbol", "qwerty_symbol2")) {
                assertEquals("$style redirected $id", id, style.resolve(id))
            }
        }
    }

    @Test
    fun unknownLayoutsPassThrough() {
        for (style in KeyboardStyle.entries) {
            assertEquals("my_layout", style.resolve("my_layout"))
        }
    }

    @Test
    fun unreadableSettingFallsBackToFlick() {
        assertEquals(KeyboardStyle.FLICK, KeyboardStyle.of(null))
        assertEquals(KeyboardStyle.FLICK, KeyboardStyle.of("NONSENSE"))
        assertEquals(KeyboardStyle.MIXED, KeyboardStyle.of("MIXED"))
    }

    @Test
    fun mixedPutsLanguageSwitchAtBottomLeft() {
        val adapted = KeyboardStyle.MIXED.adapt(source)

        assertEquals("かな", adapted.rows.last().keys[0].faceLabel)
        assertEquals("?123", adapted.rows.last().keys[1].faceLabel)
    }

    @Test
    fun qwertyAlsoPutsLanguageSwitchAtBottomLeft() {
        val adapted = KeyboardStyle.QWERTY.adapt(source)
        assertEquals("かな", adapted.rows.last().keys[0].faceLabel)
        assertEquals("?123", adapted.rows.last().keys[1].faceLabel)
    }

    @Test
    fun flickKeepsBundledOrder() {
        assertSame(source, KeyboardStyle.FLICK.adapt(source))
    }
}
