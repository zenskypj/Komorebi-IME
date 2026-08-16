package me.zssu.ime.style

import org.junit.Assert.assertEquals
import org.junit.Test

class TextStyleEngineTest {

    @Test
    fun originalDoesNotChangeText() {
        assertEquals(
            "了解です",
            TextStyleEngine.apply("了解です", TextStyle.ORIGINAL),
        )
    }

    @Test
    fun plainAndCasualStylesChangeSentenceEnding() {
        assertEquals("了解だ", TextStyleEngine.apply("了解です", TextStyle.PLAIN))
        assertEquals("了解だよ", TextStyleEngine.apply("了解です", TextStyle.CASUAL))
    }

    @Test
    fun threePoliteLevelsRemainDistinct() {
        assertEquals(
            "了解です",
            TextStyleEngine.apply("了解だ", TextStyle.NORMAL_KEIGO),
        )
        assertEquals(
            "了解ですね",
            TextStyleEngine.apply("了解だ", TextStyle.SOFT_KEIGO),
        )
        assertEquals(
            "了解でございます",
            TextStyleEngine.apply("了解だ", TextStyle.HARD_KEIGO),
        )
    }

    @Test
    fun fixedPoliteExpressionNormalizesBeforeGenericEnding() {
        assertEquals(
            "ありがとう",
            TextStyleEngine.apply("ありがとうございます", TextStyle.PLAIN),
        )
    }

    @Test
    fun longHonorificVerbIsNotPartiallyNormalized() {
        assertEquals(
            "来た",
            TextStyleEngine.apply("いらっしゃいました", TextStyle.PLAIN),
        )
    }

    @Test
    fun dictionaryBackedConjugationDoesNotTurnMasuIntoRuBlindly() {
        assertEquals(
            "資料を書く。報告を読まなかった。",
            TextStyleEngine.apply(
                "資料を書きます。報告を読みませんでした。",
                TextStyle.PLAIN,
            ),
        )
        assertEquals(
            "資料を書きました。報告を読みます。",
            TextStyleEngine.apply(
                "資料を書いた。報告を読む。",
                TextStyle.NORMAL_KEIGO,
            ),
        )
    }

    @Test
    fun politeAndPlainConversionWorksInsideConnectedClauses() {
        assertEquals(
            "確認したが、問題はなかった。",
            TextStyleEngine.apply(
                "確認しましたが、問題はありませんでした。",
                TextStyle.PLAIN,
            ),
        )
        assertEquals(
            "確認しましたが、問題はありませんでした。",
            TextStyleEngine.apply(
                "確認したが、問題はなかった。",
                TextStyle.NORMAL_KEIGO,
            ),
        )
    }

    @Test
    fun questionsGainOrLoseKaWithoutDuplicatingIt() {
        assertEquals(
            "明日は行くか？",
            TextStyleEngine.apply("明日は行きますか？", TextStyle.PLAIN),
        )
        assertEquals(
            "明日は行きますか？",
            TextStyleEngine.apply("明日は行く？", TextStyle.NORMAL_KEIGO),
        )
        assertEquals(
            "明日は行きますか？",
            TextStyleEngine.apply("明日は行きますか？", TextStyle.NORMAL_KEIGO),
        )
    }

    @Test
    fun adjectivePastAndNegativeFormsRemainGrammatical() {
        assertEquals(
            "今日は忙しかった。明日は忙しくない。",
            TextStyleEngine.apply(
                "今日は忙しかったです。明日は忙しくありません。",
                TextStyle.PLAIN,
            ),
        )
        assertEquals(
            "今日は忙しかったです。明日は忙しくありません。",
            TextStyleEngine.apply(
                "今日は忙しかった。明日は忙しくない。",
                TextStyle.NORMAL_KEIGO,
            ),
        )
    }

    @Test
    fun formalKeigoUsesHumbleLanguageOnlyForFirstPersonSubject() {
        assertEquals(
            "私は資料を拝見いたします。",
            TextStyleEngine.apply("私は資料を見る。", TextStyle.HARD_KEIGO),
        )
        assertEquals(
            "先生は資料をご覧になります。",
            TextStyleEngine.apply("先生は資料を見る。", TextStyle.HARD_KEIGO),
        )
        assertEquals(
            "資料を見ます。",
            TextStyleEngine.apply("資料を見る。", TextStyle.HARD_KEIGO),
        )
    }

    @Test
    fun formalRequestsUseBusinessAppropriateHonorifics() {
        assertEquals(
            "資料をご確認ください。問題がございましたらご連絡ください。",
            TextStyleEngine.apply(
                "資料を確認してください。問題があれば連絡してください。",
                TextStyle.HARD_KEIGO,
            ),
        )
    }

    @Test
    fun softPoliteMakesRequestsGentlerWithoutAddingCondescendingNe() {
        assertEquals(
            "資料を確認していただけると助かります。",
            TextStyleEngine.apply(
                "資料を確認してください。",
                TextStyle.SOFT_KEIGO,
            ),
        )
        assertEquals(
            "完了したかと思います。",
            TextStyleEngine.apply(
                "完了したと思う。",
                TextStyle.SOFT_KEIGO,
            ),
        )
    }

    @Test
    fun unknownVerbIsPreservedInsteadOfProducingInvalidConjugation() {
        assertEquals(
            "事情を慮ります。",
            TextStyleEngine.apply("事情を慮ります。", TextStyle.PLAIN),
        )
        assertEquals(
            "計画を企んだ。",
            TextStyleEngine.apply("計画を企んだ。", TextStyle.NORMAL_KEIGO),
        )
    }

    @Test
    fun casualStyleContractsProgressiveAndAddsEndingPerSentence() {
        assertEquals(
            "資料を読んでるよ。結果を送ったよ。",
            TextStyleEngine.apply(
                "資料を読んでいます。結果を送りました。",
                TextStyle.CASUAL,
            ),
        )
    }

    @Test
    fun punctuationAndLineBreaksArePreserved() {
        assertEquals(
            "資料を読みます！\n結果を送ります。",
            TextStyleEngine.apply(
                "資料を読む！\n結果を送る。",
                TextStyle.NORMAL_KEIGO,
            ),
        )
    }

    @Test
    fun applyingTheSameStyleTwiceIsStable() {
        for (style in TextStyle.entries.filterNot { it == TextStyle.ORIGINAL }) {
            val once = TextStyleEngine.apply(
                "私は資料を見る。問題があれば連絡してください。",
                style,
            )
            assertEquals(
                "style=$style should be idempotent",
                once,
                TextStyleEngine.apply(once, style),
            )
        }
    }

    @Test
    fun quotedSpeechAndTechnicalTokensAreNotRewrittenInternally() {
        assertEquals(
            "「明日は行く」と言いました。URLはhttps://example.comです。",
            TextStyleEngine.apply(
                "「明日は行く」と言った。URLはhttps://example.comだ。",
                TextStyle.NORMAL_KEIGO,
            ),
        )
    }

    @Test
    fun protectedLiteralsRemainByteForByteWhileSurroundingProseChanges() {
        assertEquals(
            "「ありがとうございます」と言いました。`return false`です。" +
                "https://example.com/ありがとうございます を見ます。dev@example.comです。",
            TextStyleEngine.apply(
                "「ありがとうございます」と言った。`return false`だ。" +
                    "https://example.com/ありがとうございます を見る。dev@example.comだ。",
                TextStyle.NORMAL_KEIGO,
            ),
        )
    }

    @Test
    fun additionalCommonVerbsUseDictionaryBackedConjugation() {
        assertEquals(
            "資料を直します。人数が増えました。席が減りません。",
            TextStyleEngine.apply(
                "資料を直す。人数が増えた。席が減らない。",
                TextStyle.NORMAL_KEIGO,
            ),
        )
    }

    @Test
    fun businessAndSoftRequestsStayDistinct() {
        assertEquals(
            "資料をお送りください。",
            TextStyleEngine.apply("資料を送ってください。", TextStyle.HARD_KEIGO),
        )
        assertEquals(
            "資料を送っていただけると助かります。",
            TextStyleEngine.apply("資料を送ってください。", TextStyle.SOFT_KEIGO),
        )
    }
}
