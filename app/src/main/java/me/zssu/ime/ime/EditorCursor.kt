package me.zssu.ime.ime

/** Move past a complete Unicode code point, including supplementary emoji. */
internal object EditorCursor {
    /** Fallback for editors rejecting DPAD events: move between newline-delimited lines. */
    fun line(text: String, cursor: Int, delta: Int): Int {
        val pos = cursor.coerceIn(0, text.length)
        if (delta == 0) return pos
        val start = text.lastIndexOf('\n', pos - 1) + 1
        val column = text.codePointCount(start, pos)
        val targetStart: Int
        val targetEnd: Int
        if (delta < 0) {
            if (start == 0) return pos
            targetEnd = start - 1
            targetStart = text.lastIndexOf('\n', targetEnd - 1) + 1
        } else {
            val next = text.indexOf('\n', pos)
            if (next < 0) return pos
            targetStart = next + 1
            targetEnd = text.indexOf('\n', targetStart).let { if (it < 0) text.length else it }
        }
        return text.offsetByCodePoints(targetStart, minOf(column, text.codePointCount(targetStart, targetEnd)))
    }

    fun step(text: String, cursor: Int, delta: Int): Int {
        val start = cursor.coerceIn(0, text.length)
        if (delta == 0 || delta < 0 && start == 0 || delta > 0 && start == text.length) return start
        return text.offsetByCodePoints(start, if (delta < 0) -1 else 1)
    }
}
