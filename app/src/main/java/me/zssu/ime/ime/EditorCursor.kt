package me.zssu.ime.ime

/** Move past a complete Unicode code point, including supplementary emoji. */
internal object EditorCursor {
    fun step(text: String, cursor: Int, delta: Int): Int {
        val start = cursor.coerceIn(0, text.length)
        if (delta == 0 || delta < 0 && start == 0 || delta > 0 && start == text.length) return start
        return text.offsetByCodePoints(start, if (delta < 0) -1 else 1)
    }
}
