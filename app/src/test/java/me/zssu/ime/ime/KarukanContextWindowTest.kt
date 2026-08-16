package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KarukanContextWindowTest {

    @Test
    fun `active composition is removed from context`() {
        assertEquals(
            "昨日は雨でした。",
            KarukanContextWindow.extract("昨日は雨でした。きょう", "きょう", 192),
        )
    }

    @Test
    fun `partial old sentence is dropped while recent sentence remains`() {
        val context = KarukanContextWindow.extract(
            "古い説明がとても長く続きます。私は明日の資料を確認します。へんかん",
            "へんかん",
            24,
        )
        assertEquals("私は明日の資料を確認します。", context)
    }

    @Test
    fun `crop never starts with a low surrogate`() {
        val context = KarukanContextWindow.extract("前文😀後続文", "", 4)
        assertFalse(context.first().isLowSurrogate())
    }
}
