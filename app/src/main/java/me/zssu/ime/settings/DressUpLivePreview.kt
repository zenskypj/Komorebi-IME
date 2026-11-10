package me.zssu.ime.settings

import me.zssu.ime.keyboard.KeyboardLayout
import me.zssu.ime.theme.KeyboardTheme

/** In-process, ephemeral state: only the marked editor may display this unsaved draft. */
internal object DressUpLivePreview {
    const val EDITOR_MARKER = "me.zssu.ime.dressup.preview"
    data class Draft(val theme: KeyboardTheme, val layout: KeyboardLayout?, val media: DressUpMedia?)
    var current: Draft? = null
        private set
    var listener: ((Draft) -> Unit)? = null
    fun update(value: Draft) {
        if (current == value) return
        current = value
        listener?.invoke(value)
    }
    fun clear() { current = null }
}
