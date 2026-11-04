package me.zssu.ime.ime

import java.text.Normalizer

/** Keep FIX focused on corrections, preserving literals and purely cosmetic differences. */
internal object ProofreadReview {
    private data class Protected(val start: Int, val end: Int, val numeric: Boolean)
    private val code = Regex("```[\\s\\S]*?```|`[^`\\r\\n]*`")
    private val address = Regex("https?://[^\\s<>`。、，！？「」『』]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val reading = Regex("[\\p{IsHan}々]+(?:\\([ぁ-ゖァ-ヺー・\\s]+\\)|（[ぁ-ゖァ-ヺー・\\s]+）)")
    private val number = Regex("[-+＋−－]?\\p{Nd}+(?:[,.．:/／-]\\p{Nd}+)*")
    private val addressSuffix = Regex("[A-Za-z0-9_/?#&=%.,:+@~-]+")

    private fun equivalent(a: String, b: String): Boolean =
        Normalizer.normalize(a, Normalizer.Form.NFKC).filterNot(Char::isWhitespace) ==
            Normalizer.normalize(b, Normalizer.Form.NFKC).filterNot(Char::isWhitespace)

    fun apply(original: String, proposed: String): String {
        if (equivalent(original, proposed)) return original
        val protected = reading.findAll(original).map { Protected(it.range.first, it.range.last + 1, false) }.toList() +
            code.findAll(original).map { Protected(it.range.first, it.range.last + 1, false) }.toList() +
            address.findAll(original).map { Protected(it.range.first, it.range.last + 1, false) }.toList() +
            number.findAll(original).map { Protected(it.range.first, it.range.last + 1, true) }.toList()
        val diff = ProofreadDiff.compare(original, proposed)
        var offset = 0
        return buildString {
            for (edit in diff.edits) {
                append(original.substring(offset, edit.start))
                val before = original.substring(edit.start, edit.end)
                val after = edit.replacement
                val touchesLiteral = protected.any {
                    if (before.isNotEmpty()) edit.start < it.end && edit.end > it.start
                    else edit.start > it.start && edit.start < it.end ||
                        (edit.start in it.start..it.end && if (it.numeric) after.any { char -> char.isDigit() || char in "-+＋−－" } else addressSuffix.matches(after))
                }
                val changedNumbers = before.filter(Char::isDigit) != after.filter(Char::isDigit)
                append(if (equivalent(before, after) || touchesLiteral || changedNumbers) before else after)
                offset = edit.end
            }
            append(original.substring(offset))
        }
    }
}
