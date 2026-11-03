package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class KanaFlickTest {
    @Test fun flickModifiersUseTheSameTableAsMozc() {
        val composer = KanaComposer(emptyMap())
        val rules = KanaComposer.rules(listOf("6\t\tは", "は[\t\tば", "ば]\t\tぱ"))
        composer.flick("6", "は", rules)
        composer.flick("[", "゛", rules)
        composer.flick("]", "゜", rules)
        assertEquals("ぱ", composer.text)
        assertTrue(composer.state().candidates.single().directCommit)
    }
    @Test fun backspaceDeletesWholeSupplementaryCharacter() {
        val composer = KanaComposer(emptyMap())
        composer.insert("あ😀")
        composer.backspace()
        assertEquals("あ", composer.text)
    }
}
