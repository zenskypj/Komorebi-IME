package me.zssu.ime.ime

/** Builds a bounded, sentence-aware context window for neural conversion. */
internal object KarukanContextWindow {
    fun extract(textBeforeCursor: String, composingReading: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val committed = textBeforeCursor.removeSuffix(composingReading)
        if (committed.length <= maxChars) return committed

        var start = committed.length - maxChars
        // Never hand JNI half of a surrogate pair when an emoji sits on the crop boundary.
        if (start > 0 && Character.isLowSurrogate(committed[start]) &&
            Character.isHighSurrogate(committed[start - 1])
        ) {
            start++
        }
        var window = committed.substring(start)

        // If the hard crop cut through an older sentence, discard only that partial prefix. Keep
        // the full recent sentence(s), which carry more useful subject/register evidence.
        val boundary = window.indexOfFirst { it in SENTENCE_BOUNDARIES }
        if (boundary in 0 until minOf(48, window.lastIndex)) {
            window = window.substring(boundary + 1)
        }
        return window
    }

    private val SENTENCE_BOUNDARIES = charArrayOf('。', '！', '？', '!', '?', '\n')
}
