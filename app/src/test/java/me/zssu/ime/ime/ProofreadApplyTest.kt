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

    @Test fun beforeCursorAcceptsAnyReliableSource() {
        // getTextBeforeCursor alone is enough when present.
        assertTrue(ProofreadApply.beforeCursorMatches("誤字", "誤字", null, null))
        // WebView returns null there; ExtractedText carries the same evidence.
        assertTrue(ProofreadApply.beforeCursorMatches("誤字", null, "前の文誤字", null))
        // Android 12+ SurroundingText is the last resort.
        assertTrue(ProofreadApply.beforeCursorMatches("誤字", null, null, "前の文誤字"))
    }

    @Test fun beforeCursorRefusesWhenNoSourceStillMatches() {
        assertFalse(ProofreadApply.beforeCursorMatches("誤字", "文字", null, null))
        assertFalse(ProofreadApply.beforeCursorMatches("誤字", null, null, null))
        // Empty original is never a valid target: there is nothing to verify against.
        assertFalse(ProofreadApply.beforeCursorMatches("", "", "", ""))
    }
}
