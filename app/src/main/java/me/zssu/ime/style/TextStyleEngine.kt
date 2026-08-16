package me.zssu.ime.style

/**
 * Public entry point for on-device text style correction.
 *
 * Transformation remains fully offline. The accuracy-first implementation uses phrase analysis,
 * dictionary-backed conjugation, clause boundaries, and subject-aware honorific selection.
 */
object TextStyleEngine {

    fun apply(text: String, style: TextStyle): String {
        if (style == TextStyle.ORIGINAL || text.isBlank()) return text
        return ProtectedTextTransformer.transform(text) { editable ->
            HighAccuracyTextStyleTransformer.apply(editable, style)
        }
    }
}

/** Keeps user-authored literals out of linguistic rewriting. */
private object ProtectedTextTransformer {
    private val spans = Regex(
        """```[\s\S]*?```|`[^`\r\n]+`|https?://[^\s。、，！？「」『』]+?(?=(?:です|だ)?[\s。、，！？「」『』]|\z)|[\w.+-]+@[\w-]+(?:\.[\w-]+)+|「[^」]*」|『[^』]*』|“[^”]*”"""
    )

    fun transform(text: String, rewrite: (String) -> String): String {
        val matches = spans.findAll(text).toList()
        if (matches.isEmpty()) return rewrite(text)

        val result = StringBuilder(text.length + 16)
        var cursor = 0
        for (match in matches) {
            if (cursor < match.range.first) {
                result.append(rewrite(text.substring(cursor, match.range.first)))
            }
            result.append(match.value)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) result.append(rewrite(text.substring(cursor)))
        return result.toString()
    }
}
