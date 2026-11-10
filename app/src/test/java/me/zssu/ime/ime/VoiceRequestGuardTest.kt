package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class VoiceRequestGuardTest {
    private val anchor = VoiceEditorAnchor("example", 1, null, 1, 3, 3, 0, "あいう")
    @Test fun `result is accepted only for unchanged editor and selection`() {
        assertTrue(anchor.matches(anchor.copy()))
        assertFalse(anchor.matches(anchor.copy(packageName = "other")))
        assertFalse(anchor.matches(anchor.copy(fieldId = 2)))
        assertFalse(anchor.matches(anchor.copy(selectionStart = 1)))
        assertFalse(anchor.matches(anchor.copy(text = "あいうえ")))
        assertFalse(anchor.matches(null))
    }
}
