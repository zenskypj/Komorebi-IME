package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetTemplateTest {
    @Test fun expandsValuesWithoutExpandingTokensFromSelectionAndPreservesUtf16Caret() {
        val result = SnippetTemplate.expand("😀{{名前}}：{{カーソル}}{{選択}}（{{日付}}）", "葵", "{{名前}}", "2026-11-14")
        assertEquals("😀葵：{{名前}}（2026-11-14）", result.text)
        assertEquals("😀葵：".length, result.caret)
    }
}
