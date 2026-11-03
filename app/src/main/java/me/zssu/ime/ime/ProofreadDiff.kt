package me.zssu.ime.ime

/** Code-point diff keeps emoji intact and separates multiple independent edits. */
internal object ProofreadDiff {
    data class Piece(val text: String, val changed: Boolean)
    data class Result(val before: List<Piece>, val after: List<Piece>)
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
        var i = 0; var j = 0
        while (i < left.size || j < right.size) {
            when {
                i < left.size && j < right.size && left[i] == right[j] -> {
                    append(before, left[i++], false); append(after, right[j++], false)
                }
                j < right.size && (i == left.size || table[i * width + j + 1] >= table[(i + 1) * width + j]) -> append(after, right[j++], true)
                else -> append(before, left[i++], true)
            }
        }
        return Result(before, after)
    }
}
