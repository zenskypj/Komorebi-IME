package me.zssu.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofreadApplyTest {
    @Test fun onlyReplacesUnchangedSelection() {
        assertTrue(ProofreadApply.matches("原文", true, "原文", null))
        assertFalse(ProofreadApply.matches("原文", true, "別の文章", null))
        assertFalse(ProofreadApply.matches("原文", true, null, "原文"))
    }
    @Test fun onlyReplacesUnchangedTextWithNoSelection() {
        assertTrue(ProofreadApply.matches("原文", false, null, "原文"))
        assertFalse(ProofreadApply.matches("原文", false, null, "変更済み"))
        assertFalse(ProofreadApply.matches("原文", false, "選択", "原文"))
        assertFalse(ProofreadApply.matches("原文", false, null, null))
    }
}
