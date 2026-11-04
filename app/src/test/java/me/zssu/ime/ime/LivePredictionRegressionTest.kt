package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.Category

class LivePredictionRegressionTest {
    private fun word(id: Int, text: String, reading: String, source: String = reading, correction: Boolean = false, history: Boolean = false) =
        MozcSession.Candidate(id, text, inputReading = reading, sourceReading = source,
            prediction = source != reading, correction = correction, fromUserHistory = history)

    @Test fun mixedPredictionAndJapaneseQwertyUseTheSameLiveRanking() {
        assertTrue(CandidateRanking.shouldRankLive(Category.PREDICTION, true))
        assertTrue(CandidateRanking.shouldRankLive(Category.SUGGESTION, true))
        assertFalse(CandidateRanking.shouldRankLive(Category.CONVERSION, true))
        assertFalse(CandidateRanking.shouldRankLive(Category.PREDICTION, false))
    }
    @Test fun aSingleTypedParticleDoesNotHideTheCorrectFullConversionBehindCorrections() {
        val r = "よそくへんかんで"
        val ranked = CandidateRanking.rankLiveJapaneseSuggestions(listOf(
            word(1, "ヨソクヘンカンデ", r), word(2, "予速変換で", r, correction = true),
            word(3, "予測変換で", r, "よそくへんかん")), 6)
        assertEquals("予測変換で", ranked.first().text)
    }
    @Test fun aLeadingParticleAndIndependentWordAreRecognizedAsFullInput() {
        val r = "のよそくへんかん"
        assertEquals("の予測変換", CandidateRanking.rankLiveJapaneseSuggestions(listOf(
            word(1, "ノヨソクヘンカン", r), word(2, "野予測変換", r, correction = true),
            word(3, "の予測変換", r, "よそくへんかん")), 6).first().text)
    }
    @Test fun aLongerCompletionIsNotPromotedJustBecauseItEndsInTheSameParticle() {
        val r = "へんかんに"
        assertEquals("変換に", CandidateRanking.rankLiveJapaneseSuggestions(listOf(
            word(1, "変換について確認に", r, "へんかんについてかくにんに"),
            word(2, "ヘンカンニ", r), word(3, "変換に", r, "へんかん")), 6).first().text)
    }
    @Test fun nativeHistoryPreferencesApplyAcrossTheLivePredictionPath() {
        val r = "こうせい"
        assertEquals("校正", CandidateRanking.rankLiveJapaneseSuggestions(listOf(
            word(1, "構成", r), word(2, "校正", r, history = true)), 5).first().text)
    }
    @Test fun aiStillStaysFirstAndAutomaticallyFocusedByTheCaller() {
        val native = word(1, "予測変換で", "よそくへんかんで")
        val ai = ZenzaiConversion.candidate("ヨソクヘンカンデ")!!
        val result = ZenzaiConversion.candidates(listOf(native), ai, native.inputReading)
        assertEquals(ai.text, result.first().text)
        assertTrue(result.first().directCommit)
        assertEquals(native.id, result[1].id)
    }
    @Test fun halfWidthKanaAreRecognizedAsTheSameReading() {
        assertEquals("よそくへんかんで", ReadingSimilarity.normalize("ﾖｿｸﾍﾝｶﾝﾃﾞ"))
    }
}
