package me.zssu.ime.ime

import java.text.BreakIterator
import java.util.Locale

/** UTF-16 ranges, bounded to the current line; never delete a line separator or half a surrogate. */
internal object DeletionRange {
    data class Range(val start: Int, val end: Int)

    fun plan(text: String, cursor: Int, forward: Boolean, line: Boolean): Range {
        val caret = cursor.coerceIn(0, text.length)
        if (caret in 1 until text.length && Character.isHighSurrogate(text[caret - 1]) &&
            Character.isLowSurrogate(text[caret])) return Range(caret, caret)
        val startOfLine = text.substring(0, caret).indexOfLast { it.isLineBreak() } + 1
        val followingBreak = text.indexOfFirstFrom(caret) { it.isLineBreak() }
        val endOfLine = if (followingBreak < 0) text.length else followingBreak
        if (line) return if (forward) Range(caret, endOfLine) else Range(startOfLine, caret)
        val words = BreakIterator.getWordInstance(Locale.JAPANESE)
        val currentLine = text.substring(startOfLine, endOfLine)
        words.setText(currentLine)
        var position = caret - startOfLine
        val end: Int
        val start: Int
        if (forward) {
            while (position < currentLine.length && currentLine[position].isWhitespace()) position++
            end = if (position == currentLine.length) endOfLine else
                startOfLine + words.following(position).let { if (it == BreakIterator.DONE) currentLine.length else it }
            start = caret
        } else {
            while (position > 0 && currentLine[position - 1].isWhitespace()) position--
            start = if (position == 0) startOfLine else
                startOfLine + words.preceding(position).let { if (it == BreakIterator.DONE) 0 else it }
            end = caret
        }
        // Android's character iterator expands combining sequences to complete display units.
        val characters = BreakIterator.getCharacterInstance(Locale.JAPANESE)
        characters.setText(text)
        val safeStart = if (characters.isBoundary(start)) start else characters.preceding(start).coerceAtLeast(startOfLine)
        val safeEnd = if (characters.isBoundary(end)) end else characters.following(end).let {
            if (it == BreakIterator.DONE) endOfLine else it.coerceAtMost(endOfLine)
        }
        return if (forward) Range(caret, safeEnd) else Range(safeStart, caret)
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return -1
    }
    private fun Char.isLineBreak() = this == '\n' || this == '\r' || this == '\u2028' || this == '\u2029' || this == '\u0085'
}
