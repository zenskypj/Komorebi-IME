package me.zssu.ime.ime

/**
 * Pure selection policy kept separate from [ZinnaImeService] so editor edge cases can be tested
 * without constructing an Android [android.inputmethodservice.InputMethodService].
 */
internal object SelectionUpdate {

    /** A delayed callback must not override a newer cursor position in the editor. */
    fun isStale(callbackStart: Int, callbackEnd: Int, actualStart: Int, actualEnd: Int): Boolean =
        actualStart >= 0 && actualEnd >= 0 && (callbackStart != actualStart || callbackEnd != actualEnd)

    /**
     * Resolves Mozc's cursor offset to an absolute editor position after [setComposingText] has
     * temporarily placed the caret at the end of the composing span.
     */
    fun absolutePreeditCursor(
        composingEnd: Int,
        preeditLength: Int,
        preeditCursor: Int,
    ): Int {
        val length = preeditLength.coerceAtLeast(0)
        val cursor = preeditCursor.coerceIn(0, length)
        return composingEnd - length + cursor
    }

    /**
     * A composing editor normally reports a collapsed selection at Mozc's cursor inside its
     * composing span. A different caret, a range selection, or a missing span means the editor
     * changed independently.
     */
    fun invalidatesComposition(
        isComposing: Boolean,
        newSelectionStart: Int,
        newSelectionEnd: Int,
        composingStart: Int,
        composingEnd: Int,
        preeditCursor: Int,
    ): Boolean =
        isComposing &&
            (
                composingStart < 0 ||
                    composingEnd < composingStart ||
                    newSelectionStart != newSelectionEnd ||
                    newSelectionStart != composingStart + preeditCursor
                )
}
