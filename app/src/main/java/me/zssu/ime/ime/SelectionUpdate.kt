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
     * Reading edits expect the restored caret. During conversion, any collapsed caret inside
     * the composing span is harmless: native clause focus is independent of that caret.
     * Outside/range selections and missing spans remain external changes.
     */
    fun invalidatesComposition(
        isComposing: Boolean,
        newSelectionStart: Int,
        newSelectionEnd: Int,
        composingStart: Int,
        composingEnd: Int,
        preeditCursor: Int,
        converting: Boolean = false,
    ): Boolean =
        isComposing &&
            (
                composingStart < 0 ||
                    composingEnd <= composingStart ||
                    newSelectionStart != newSelectionEnd ||
                    newSelectionStart < composingStart || newSelectionStart > composingEnd ||
                    (!converting && newSelectionStart != composingStart + preeditCursor)
                )
}
