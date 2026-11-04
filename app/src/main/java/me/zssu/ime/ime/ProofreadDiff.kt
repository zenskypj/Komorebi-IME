package me.zssu.ime.ime

/** Code-point diff keeps emoji intact and separates multiple independent edits. */
internal object ProofreadDiff {
    data class Piece(val text: String, val changed: Boolean)
    data class Edit(val start: Int, val end: Int, val replacement: String)
    data class Result(val before: List<Piece>, val after: List<Piece>, val edits: List<Edit> = emptyList())
    fun compare(original: String, corrected: String): Result {
        val left = original.codePoints().toArray()
        val right = corrected.codePoints().toArray()
        val width = right.size + 1
        val table = IntArray((left.size + 1) * width)
        for (i in left.size - 1 downTo 0) for (j in right.size - 1 downTo 0) {
            table[i * width + j] = if (left[i] == right[j]) 1 + table[(i + 1) * width + j + 1]
                else maxOf(table[(i + 1) * width + j], table[i * width + j + 1])
        }
        val before = mutableListOf<Piece>()
        val after = mutableListOf<Piece>()
        fun append(list: MutableList<Piece>, cp: Int, changed: Boolean) {
            val value = String(Character.toChars(cp))
            if (list.lastOrNull()?.changed == changed) {
                val previous = list.removeAt(list.lastIndex)
                list.add(Piece(previous.text + value, changed))
            } else list.add(Piece(value, changed))
        }
        val edits = mutableListOf<Edit>()
        var offset = 0
        var editStart = -1
        val replacement = StringBuilder()
        fun flushEdit() {
            if (editStart >= 0) { edits.add(Edit(editStart, offset, replacement.toString())); editStart = -1; replacement.clear() }
        }
        var i = 0; var j = 0
        while (i < left.size || j < right.size) {
            when {
                i < left.size && j < right.size && left[i] == right[j] -> {
                    flushEdit()
                    offset += Character.charCount(left[i])
                    append(before, left[i++], false); append(after, right[j++], false)
                }
                j < right.size && (i == left.size || table[i * width + j + 1] >= table[(i + 1) * width + j]) -> {
                    if (editStart < 0) editStart = offset
                    replacement.appendCodePoint(right[j]); append(after, right[j++], true)
                }
                else -> {
                    if (editStart < 0) editStart = offset
                    offset += Character.charCount(left[i]); append(before, left[i++], true)
                }
            }
        }
        flushEdit()
        return Result(before, after, edits)
    }
}
