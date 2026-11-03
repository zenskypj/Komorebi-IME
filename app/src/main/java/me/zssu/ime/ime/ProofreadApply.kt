package me.zssu.ime.ime

/** A review may be applied only to the same still-selected text or unchanged preceding text. */
internal object ProofreadApply {
    fun matches(original: String, selected: Boolean, currentSelection: String?, beforeCursor: String?): Boolean =
        if (selected) currentSelection == original
        else currentSelection.isNullOrEmpty() && beforeCursor == original

    /**
     * Whether the reviewed text is still immediately before the caret.
     *
     * Editors expose the preceding text four different ways and a given one may be missing on any
     * particular app (WebView returns null for [beforeCursor]; some Samsung/IMM connections answer
     * only through [extractedBefore]). Treating any usable source as proof avoids refusing to apply
     * on those editors while still refusing when *every* source disagrees — the case where the
     * text genuinely moved and overwriting it would destroy the user's writing.
     */
    fun beforeCursorMatches(
        original: String,
        beforeCursor: String?,
        extractedBefore: String?,
        surroundingBefore: String?,
    ): Boolean {
        if (original.isEmpty()) return false
        return listOf(beforeCursor, extractedBefore, surroundingBefore).any { candidate ->
            candidate != null && candidate.endsWith(original)
        }
    }
}
