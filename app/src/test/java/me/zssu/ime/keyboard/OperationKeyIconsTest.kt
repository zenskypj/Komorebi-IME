package me.zssu.ime.keyboard
import org.junit.Assert.*
import org.junit.Test
class OperationKeyIconsTest {
    @Test fun typedSymbolsAndCustomLabelsStayAsText() {
        assertNull(OperationKeyIcons.resource(KeyAction.Input("←"), "←"))
        assertNull(OperationKeyIcons.resource(KeyAction.Backspace, "消す"))
        assertNotNull(OperationKeyIcons.resource(KeyAction.Backspace, "⌫"))
    }
    @Test fun shiftStatesRemainVisuallyDistinct() {
        val states = listOf("⇧", "⬆", "⇪").map { OperationKeyIcons.resource(KeyAction.Shift, it) }
        assertTrue(states.all { it != null })
        assertEquals(3, states.toSet().size)
    }
}
