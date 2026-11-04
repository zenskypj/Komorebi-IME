package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ProofreadReviewTest {
    @Test fun correctionsInTheMiddleSurviveWithoutReformattingOtherText() {
        assertEquals("会議は10時です。資料をご確認ください。",
            ProofreadReview.apply("会議は10時です。資科をご確認くだいさい。", "会議は 10 時です。資料をご確認ください。"))
    }
    @Test fun correctNamesAndReadingsAreNotChangedOnlyForParenthesisWidth() {
        val source = "山田太郎(やまだたろう)さんが担当です。"
        assertEquals(source, ProofreadReview.apply(source, source.replace("(", "（").replace(")", "）")))
    }
    @Test fun codeAddressesAndNumbersRemainLiteralWhileProseIsCorrected() {
        val source = "`confrimData()` は https://example.com/a にあります。価格は1,280円です。ご確認くだいさい。"
        val output = "`confirmData()` は https://example.com/b にあります。価格は1,820円です。ご確認ください。"
        assertEquals(source.replace("くだいさい", "ください"), ProofreadReview.apply(source, output))
    }
    @Test fun addedDigitsAtNumericBoundariesCannotChangeTheQuantity() {
        assertEquals("会議は10時です。", ProofreadReview.apply("会議は10時です。", "会議は100時です。"))
        assertEquals("会議は10時です。", ProofreadReview.apply("会議は10時です。", "会議は110時です。"))
    }
    @Test fun numericSignsCannotBeDroppedWhileCorrectingProse() {
        val source = "気温は-10度、差は＋3度です。ご確認くだいさい。"
        assertEquals(source.replace("くだいさい", "ください"), ProofreadReview.apply(source, "気温は10度、差は3度です。ご確認ください。"))
        assertEquals("気温は10度です。", ProofreadReview.apply("気温は10度です。", "気温は-10度です。"))
        assertEquals("気温は10度です。", ProofreadReview.apply("気温は10度です。", "気温は＋10度です。"))
    }
    @Test fun multilineFormattingAndSupplementaryCharactersStayIntact() {
        assertEquals("😀お知らせ\n資料をご確認ください。", ProofreadReview.apply("😀お知らせ\n資料をご確認くだいさい。", "😀お知らせ 資料をご確認ください。"))
    }
    @Test fun grammaticalEditsAndDuplicateRemovalAreKept() {
        assertEquals("私は電車に乗ります。", ProofreadReview.apply("私は電車を乗ります。", "私は電車に乗ります。"))
        assertEquals("この問題は解決しました。", ProofreadReview.apply("この問題は問題は解決しました。", "この問題は解決しました。"))
    }
    @Test fun anAttachedNameReadingCannotBeDeletedAsAnUnnecessaryPhrase() {
        val source = "山田太郎(やまだたろう)さんへ。資料をご確認くだいさい。"
        assertEquals(source.replace("くだいさい", "ください"), ProofreadReview.apply(source, "山田太郎さんへ。資料をご確認ください。"))
    }
}
