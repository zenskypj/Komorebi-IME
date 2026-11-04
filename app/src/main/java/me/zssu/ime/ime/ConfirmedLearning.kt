package me.zssu.ime.ime

/** Learn reusable words only from confirmed, aligned native readings; never guess AI word cuts. */
internal object ConfirmedLearning {
    data class Example(val reading: String, val value: String)
    private val particles = listOf("から", "まで", "より", "には", "では", "は", "が", "を", "に", "へ", "と", "で", "の", "も")

    fun examples(before: MozcSession.State?, committed: String, selected: MozcSession.Candidate?): List<Example> {
        if (before == null || committed.isEmpty()) return emptyList()
        val aligned = mutableListOf<Example>()
        if (before.isConverting && before.segments.all { it.reading.isNotEmpty() }) {
            var offset = 0
            for (segment in before.segments) {
                if (offset == committed.length) break
                if (segment.highlighted && selected != null && !selected.directCommit && selected.text != segment.text) return emptyList()
                val value = segment.text
                if (!committed.startsWith(value, offset)) return emptyList()
                offset += value.length
                aligned.add(Example(segment.reading, value))
            }
            if (offset != committed.length) return emptyList()
        } else if (selected != null && !selected.directCommit && selected.text == committed &&
            !selected.correction && (!selected.prediction || ReadingSimilarity.normalize(selected.sourceReading) == ReadingSimilarity.normalize(selected.inputReading))) {
            aligned.add(Example(selected.inputReading, committed))
        }
        return aligned.flatMap(::forms).filter { example ->
            example.reading.length in 2..40 && example.value.length in 1..40 &&
                example.value.any { it in '\u4e00'..'\u9fff' || it in '\u30a1'..'\u30fa' } &&
                ZenzaiConversion.prompt(example.reading, "") != null
        }.distinct().take(6)
    }

    fun fromReading(reading: String, value: String): List<Example> = forms(Example(reading, value)).filter {
        it.reading.length in 2..40 && it.value.length in 1..40 &&
            it.value.any { ch -> ch in '\u4e00'..'\u9fff' || ch in '\u30a1'..'\u30fa' } && ZenzaiConversion.prompt(it.reading, "") != null
    }.distinct()

    fun trainingPrompts(reading: String, preceding: String, value: String): List<String> {
        if (ReadingSimilarity.normalize(reading) == ReadingSimilarity.normalize(value)) return emptyList()
        val contextual = ZenzaiConversion.prompt(reading, preceding) ?: return emptyList()
        return listOfNotNull(contextual, ZenzaiConversion.prompt(reading, "")).distinct()
    }

    private fun forms(example: Example): List<Example> {
        val starts = listOf("") + particles.filter { example.reading.startsWith(it) && example.value.startsWith(it) }
        val ends = listOf("") + particles.filter { example.reading.endsWith(it) && example.value.endsWith(it) }
        val words = mutableListOf<Example>()
        for (start in starts) for (end in ends) {
            if (start.isEmpty() && end.isEmpty()) continue
            if (start.length + end.length >= minOf(example.reading.length, example.value.length)) continue
            val reading = example.reading.substring(start.length, example.reading.length - end.length)
            val value = example.value.substring(start.length, example.value.length - end.length)
            // Strip particles only around a clear compound, not okurigana such as 泳いで/のり子.
            if (reading.length >= 2 && value.length >= 2 && value.all { it in '\u4e00'..'\u9fff' || it in '\u30a1'..'\u30fa' || it == '々' || it == 'ー' }) words.add(Example(reading, value))
        }
        return words.sortedBy { it.reading.length } + example
    }
}
