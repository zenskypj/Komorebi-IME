package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test

class ConfirmedLearningTest {
    @Test fun wordLearningReusesACompoundWithDifferentParticles() {
        val a = ConfirmedLearning.fromReading("よそくへんかんで", "予測変換で")
        val b = ConfirmedLearning.fromReading("のよそくへんかんに", "の予測変換に")
        val word = ConfirmedLearning.Example("よそくへんかん", "予測変換")
        assertTrue(word in a)
        assertTrue(word in b)
    }
    @Test fun okuriganaAndNamesAreNotGuessedAsParticles() {
        assertFalse(ConfirmedLearning.Example("およい", "泳い") in ConfirmedLearning.fromReading("およいで", "泳いで"))
        assertFalse(ConfirmedLearning.Example("りこ", "り子") in ConfirmedLearning.fromReading("のりこ", "のり子"))
    }
    @Test fun differentSurroundingSentencesShareAContextFreeTrainingExample() {
        val a = ConfirmedLearning.trainingPrompts("よそくへんかん", "文章A。", "予測変換")
        val b = ConfirmedLearning.trainingPrompts("よそくへんかん", "文章B。", "予測変換")
        assertEquals(2, a.size)
        assertNotEquals(a.first(), b.first())
        assertEquals(a.last(), b.last())
        assertTrue(ConfirmedLearning.trainingPrompts("よそく", "", "ヨソク").isEmpty())
    }
    @Test fun onlyTheConfirmedNativeSegmentsAreLearned() {
        val state = MozcSession.State(isConverting = true, segments = listOf(
            MozcSession.Segment("学校へ", true, "がっこうへ"), MozcSession.Segment("行きます", false, "いきます")))
        val examples = ConfirmedLearning.examples(state, "学校へ", null)
        assertTrue(ConfirmedLearning.Example("がっこう", "学校") in examples)
        assertFalse(examples.any { it.value == "行きます" })
        assertTrue(ConfirmedLearning.examples(state, "別の言葉", null).isEmpty())
        assertTrue(ConfirmedLearning.examples(state, "学園へ", MozcSession.Candidate(3, "学園へ")).isEmpty())
    }
    @Test fun neuralSentencesDoNotInventWordAlignment() {
        val ai = MozcSession.Candidate(-2, "今日は学校へ行きます", directCommit = true)
        assertTrue(ConfirmedLearning.examples(MozcSession.State(preeditReading = "きょうはがっこうへいきます"), ai.text, ai).isEmpty())
    }
}
