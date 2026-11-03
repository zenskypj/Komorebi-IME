package me.zssu.ime.ime

/** A review may be applied only to the same still-selected text or unchanged preceding text. */
internal object ProofreadApply {
    fun matches(original: String, selected: Boolean, currentSelection: String?, beforeCursor: String?): Boolean =
        if (selected) currentSelection == original
        else currentSelection.isNullOrEmpty() && beforeCursor == original
}
