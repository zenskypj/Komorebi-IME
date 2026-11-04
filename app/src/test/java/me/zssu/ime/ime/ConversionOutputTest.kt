package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateList
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateWord
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.Category

class ConversionOutputTest {
    private fun hiddenWindow(category: Category = Category.CONVERSION): Output = Output.newBuilder()
        .setConsumed(true)
        .setPreedit(Preedit.newBuilder().setCursor(3)
            .addSegment(Preedit.Segment.newBuilder().setValue("学校").setKey("がっこう")
                .setValueLength(2).setAnnotation(Preedit.Segment.Annotation.HIGHLIGHT))
            .addSegment(Preedit.Segment.newBuilder().setValue("へ").setKey("へ")
                .setValueLength(1).setAnnotation(Preedit.Segment.Annotation.UNDERLINE)))
        .setAllCandidateWords(CandidateList.newBuilder().setCategory(category).setFocusedIndex(1)
            .addCandidates(CandidateWord.newBuilder().setId(42).setValue("学校"))
            .addCandidates(CandidateWord.newBuilder().setId(81).setValue("がっこう")))
        .build()

    @Test fun conversionRemainsActiveWhenResizeHidesTheCandidateWindow() {
        val output = hiddenWindow()
        assertFalse(output.hasCandidateWindow())
        assertTrue(ConversionOutput.isConverting(output))
        assertEquals("がっこうへ", ConversionOutput.reading(output))
        assertEquals(81, ConversionOutput.focusedId(output))
    }
    @Test fun ordinaryPredictionIsNotMisclassifiedAsExplicitConversion() {
        assertFalse(ConversionOutput.isConverting(hiddenWindow(Category.PREDICTION)))
        assertFalse(ConversionOutput.isConverting(hiddenWindow(Category.SUGGESTION)))
        val noCategory = hiddenWindow().toBuilder().setAllCandidateWords(hiddenWindow().allCandidateWords.toBuilder().clearCategory()).build()
        assertFalse(ConversionOutput.isConverting(noCategory))
    }
    @Test fun conversionAfterResizeDoesNotSendHenkanAndRecomputeTheBoundary() {
        val output = hiddenWindow()
        val state = MozcSession.State(preedit = "学校へ", preeditReading = ConversionOutput.reading(output)!!,
            isConverting = ConversionOutput.isConverting(output), focusedCandidateIndex = 1,
            candidates = listOf(MozcSession.Candidate(42, "学校"), MozcSession.Candidate(81, "がっこう")))
        val result = ConvertKeySequence.convert(true, state.isConverting,
            stop = { state }, send = { error("Must not restart conversion") },
            highlight = { assertEquals(42, it); state.copy(focusedCandidateIndex = 0) },
            visibleCandidateIds = listOf(42, 81))
        assertTrue(result!!.isConverting)
        assertEquals("がっこうへ", result.preeditReading)
    }
}
