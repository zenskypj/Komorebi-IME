package me.zssu.ime.ime

import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.Category

/** Mozc hides CandidateWindow after Convert/Resize, while AllCandidateWords stays in conversion. */
internal object ConversionOutput {
    fun candidateMetadata(output: Output, converting: Boolean): List<org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateWord> {
        if (!output.hasAllCandidateWords()) return emptyList()
        val candidates = output.allCandidateWords.candidatesList
        return if (converting) candidates else candidates.take(CandidateRanking.MAX_LIVE_POOL_SIZE)
    }
    fun isConverting(output: Output): Boolean =
        (output.hasCandidateWindow() && output.candidateWindow.category == Category.CONVERSION && output.candidateWindow.hasFocusedIndex()) ||
            (output.hasAllCandidateWords() && output.allCandidateWords.hasCategory() &&
                output.allCandidateWords.category == Category.CONVERSION && output.allCandidateWords.hasFocusedIndex())

    fun focusedId(output: Output): Int? {
        if (output.hasCandidateWindow() && output.candidateWindow.hasFocusedIndex()) {
            val window = output.candidateWindow
            CandidatePaging.focusedId(window.candidateList.map { it.index to it.id }, window.focusedIndex)?.let { return it }
        }
        // CandidateList's index is relative to its own list. JNI may omit CandidateWord.index.
        return output.allCandidateWords.takeIf { output.hasAllCandidateWords() && it.hasFocusedIndex() }
            ?.let { it.candidatesList.getOrNull(it.focusedIndex)?.id }
    }

    fun reading(output: Output): String? = output.preedit.takeIf {
        output.hasPreedit() && it.segmentCount > 0 && it.segmentList.all { segment -> segment.hasKey() }
    }?.segmentList?.joinToString("") { it.key }
}
