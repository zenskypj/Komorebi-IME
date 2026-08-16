package me.zssu.ime.ime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EnterRoutingTest {

    @Test
    fun `composition is submitted before any editor action`() {
        assertEquals(
            EnterRouting.Target.SUBMIT_COMPOSITION,
            EnterRouting.target(
                hadComposition = true,
                rawKeyEvents = true,
                hasEnabledEditorAction = true,
                multiline = true,
            ),
        )
    }

    @Test
    fun `explicit send action beats multiline newline`() {
        assertEquals(
            EnterRouting.Target.EDITOR_ACTION,
            EnterRouting.target(
                hadComposition = false,
                rawKeyEvents = false,
                hasEnabledEditorAction = true,
                multiline = true,
            ),
        )
    }

    @Test
    fun `multiline field without action inserts newline`() {
        assertEquals(
            EnterRouting.Target.NEWLINE,
            EnterRouting.target(
                hadComposition = false,
                rawKeyEvents = false,
                hasEnabledEditorAction = false,
                multiline = true,
            ),
        )
    }

    @Test
    fun `terminal field receives raw enter`() {
        assertEquals(
            EnterRouting.Target.RAW_KEY_EVENT,
            EnterRouting.target(
                hadComposition = false,
                rawKeyEvents = true,
                hasEnabledEditorAction = true,
                multiline = true,
            ),
        )
    }

    @Test
    fun `single line field without action receives raw enter`() {
        assertEquals(
            EnterRouting.Target.RAW_KEY_EVENT,
            EnterRouting.target(
                hadComposition = false,
                rawKeyEvents = false,
                hasEnabledEditorAction = false,
                multiline = false,
            ),
        )
    }

    @Test
    fun `every explicit editor action wins even when field reports multiline`() {
        val multiline = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        listOf(
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
        ).forEach { action ->
            assertEquals(
                EnterRouting.Target.EDITOR_ACTION,
                EnterRouting.targetForEditor(
                    hadComposition = false,
                    rawKeyEvents = false,
                    imeOptions = action,
                    inputType = multiline,
                ),
            )
        }
    }

    @Test
    fun `no enter action flag returns control according to field shape`() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(
            EnterRouting.Target.NEWLINE,
            EnterRouting.targetForEditor(
                hadComposition = false,
                rawKeyEvents = false,
                imeOptions = options,
                inputType =
                    EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE,
            ),
        )
        assertEquals(
            EnterRouting.Target.RAW_KEY_EVENT,
            EnterRouting.targetForEditor(
                hadComposition = false,
                rawKeyEvents = false,
                imeOptions = options,
                inputType = EditorInfo.TYPE_CLASS_TEXT,
            ),
        )
    }

    @Test
    fun `unspecified single line search-like field receives hardware enter`() {
        assertEquals(
            EnterRouting.Target.RAW_KEY_EVENT,
            EnterRouting.targetForEditor(
                hadComposition = false,
                rawKeyEvents = false,
                imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED,
                inputType = EditorInfo.TYPE_CLASS_TEXT,
            ),
        )
    }

    @Test
    fun `rejected editor action falls back to newline in multiline field`() {
        assertEquals(
            EnterRouting.Target.NEWLINE,
            EnterRouting.fallbackAfterFailure(
                EnterRouting.Target.EDITOR_ACTION,
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE,
            ),
        )
    }

    @Test
    fun `rejected editor action falls back to raw enter in single line field`() {
        assertEquals(
            EnterRouting.Target.RAW_KEY_EVENT,
            EnterRouting.fallbackAfterFailure(
                EnterRouting.Target.EDITOR_ACTION,
                EditorInfo.TYPE_CLASS_TEXT,
            ),
        )
    }

    @Test
    fun `rejected newline falls back to raw enter`() {
        assertEquals(
            EnterRouting.Target.RAW_KEY_EVENT,
            EnterRouting.fallbackAfterFailure(
                EnterRouting.Target.NEWLINE,
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE,
            ),
        )
    }

    @Test
    fun `successful terminal routes have no fallback`() {
        assertEquals(
            null,
            EnterRouting.fallbackAfterFailure(
                EnterRouting.Target.SUBMIT_COMPOSITION,
                EditorInfo.TYPE_CLASS_TEXT,
            ),
        )
        assertEquals(
            null,
            EnterRouting.fallbackAfterFailure(
                EnterRouting.Target.RAW_KEY_EVENT,
                EditorInfo.TYPE_CLASS_TEXT,
            ),
        )
    }
}
