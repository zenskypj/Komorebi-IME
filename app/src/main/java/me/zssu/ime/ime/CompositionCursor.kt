package me.zssu.ime.ime

/** Never derive a composing boundary from a caret returned after replacing the text. */
internal object CompositionCursor {
    data class Snapshot(val text: String, val startOffset: Int, val composingStart: Int, val composingEnd: Int)
    data class Plan(val displayOffset: Int, val absoluteSelection: Int?)

    fun verifiedStart(previousPreedit: String, snapshot: Snapshot?): Int? {
        if (snapshot == null || previousPreedit.isEmpty() || snapshot.startOffset < 0) return null
        val start = snapshot.composingStart
        val end = snapshot.composingEnd
        if (start < 0 || end <= start || end > snapshot.text.length || end - start != previousPreedit.length) return null
        if (snapshot.text.substring(start, end) != previousPreedit) return null
        val absolute = snapshot.startOffset.toLong() + start
        return absolute.takeIf { snapshot.startOffset.toLong() + end <= Int.MAX_VALUE }?.toInt()
    }

    fun matchesAtCaret(preedit: String, offset: Int, converting: Boolean,
                      text: CharSequence, start: Int, end: Int): Boolean {
        if (preedit.isEmpty() || start != end) return false
        if (!converting) {
            val origin = start - offset
            return origin >= 0 && origin <= text.length - preedit.length &&
                text.regionMatches(origin, preedit, 0, preedit.length)
        }
        val earliest = (start - preedit.length).coerceAtLeast(0)
        val latest = minOf(start, text.length - preedit.length)
        return (earliest..latest).any { text.regionMatches(it, preedit, 0, preedit.length) }
    }

    fun plan(preedit: String, nativeCursor: Int, converting: Boolean, previousPreedit: String, before: Snapshot?): Plan {
        val end = Plan(preedit.length, null)
        // Clause focus is shown by spans and the clause bar, not by moving the editor caret.
        if (converting) return end
        var cursor = nativeCursor.coerceIn(0, preedit.length)
        if (cursor in 1 until preedit.length && Character.isLowSurrogate(preedit[cursor]) && Character.isHighSurrogate(preedit[cursor - 1])) cursor--
        if (cursor == preedit.length) return end
        val start = verifiedStart(previousPreedit, before) ?: return end
        if (start.toLong() + preedit.length > Int.MAX_VALUE) return end
        return Plan(cursor, start + cursor)
    }
}
