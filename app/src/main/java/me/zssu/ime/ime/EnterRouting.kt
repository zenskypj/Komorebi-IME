package me.zssu.ime.ime

import android.view.inputmethod.EditorInfo

/**
 * Chooses Enter behaviour without letting a multiline flag hide an explicit editor action.
 *
 * InputConnection methods return false when an editor advertises an operation but does not
 * actually implement it. The fallback route keeps Enter usable in those custom/older editors
 * while ensuring a successful action is never followed by a duplicate newline or key event.
 */
internal object EnterRouting {

    enum class Target {
        SUBMIT_COMPOSITION,
        EDITOR_ACTION,
        NEWLINE,
        RAW_KEY_EVENT,
    }

    fun target(
        hadComposition: Boolean,
        rawKeyEvents: Boolean,
        hasEnabledEditorAction: Boolean,
        multiline: Boolean,
    ): Target = when {
        hadComposition -> Target.SUBMIT_COMPOSITION
        rawKeyEvents -> Target.RAW_KEY_EVENT
        hasEnabledEditorAction -> Target.EDITOR_ACTION
        multiline -> Target.NEWLINE
        else -> Target.RAW_KEY_EVENT
    }

    fun targetForEditor(
        hadComposition: Boolean,
        rawKeyEvents: Boolean,
        imeOptions: Int,
        inputType: Int,
    ): Target {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val hasEnabledEditorAction =
            action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED &&
                imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
        val multiline = inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0
        return target(hadComposition, rawKeyEvents, hasEnabledEditorAction, multiline)
    }

    fun fallbackAfterFailure(target: Target, inputType: Int): Target? = when (target) {
        Target.EDITOR_ACTION -> {
            if (inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
                Target.NEWLINE
            } else {
                Target.RAW_KEY_EVENT
            }
        }
        Target.NEWLINE -> Target.RAW_KEY_EVENT
        Target.SUBMIT_COMPOSITION,
        Target.RAW_KEY_EVENT,
        -> null
    }
}
