package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ProofreadDiffTest {
    @Test fun separatesTwoEditsAndPreservesEmoji() {
        val result = ProofreadDiff.compare("誤字😀と誤字", "文字😀と文字")
        assertEquals("誤字😀と誤字", result.before.joinToString("") { it.text })
        assertEquals("文字😀と文字", result.after.joinToString("") { it.text })
        assertEquals(listOf("誤", "誤"), result.before.filter { it.changed }.map { it.text })
        assertEquals(listOf("文", "文"), result.after.filter { it.changed }.map { it.text })
    }
    @Test fun deletionAppearsOnOriginalOnly() {
        val result = ProofreadDiff.compare("あ😀い", "あい")
        assertEquals("😀", result.before.filter { it.changed }.joinToString("") { it.text })
        assertEquals("", result.after.filter { it.changed }.joinToString("") { it.text })
    }
    @Test fun editOffsetsReconstructTheTargetWithoutSplittingEmoji() {
        for ((a, b) in listOf("あ😀い" to "前あ😀う後", "誤字😀と誤字" to "文字😀と文字", "問題は問題は解決" to "問題は解決", "abc" to "", "" to "😀")) {
            val edits = ProofreadDiff.compare(a, b).edits
            val rebuilt = StringBuilder(a)
            edits.asReversed().forEach { rebuilt.replace(it.start, it.end, it.replacement) }
            assertEquals(b, rebuilt.toString())
        }
    }
}
