package me.zssu.ime.mozc

import java.text.Normalizer

/** Name evidence can add candidates, never replace the user's authoritative rows. */
internal object NameParts {
    private val spaces = Regex("[\\s　]+")
    private val kana = Regex("[ぁ-ゖー]+")
    private val inferPos = setOf("人名", "名詞", "固有名詞")

    fun normalize(reading: String): String = Normalizer.normalize(reading, Normalizer.Form.NFKC)
        .map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString("")

    fun full(entry: UserDictionary.Entry): UserDictionary.Entry {
        val parts = explicit(entry) ?: return entry
        return entry.copy(reading = parts.joinToString("") { it.reading }, word = parts.joinToString("") { it.word })
    }

    private fun explicit(entry: UserDictionary.Entry): List<UserDictionary.Entry>? {
        if (entry.pos != "人名") return null
        val readings = normalize(entry.reading).split(spaces)
        val words = entry.word.split(spaces)
        if (readings.size != 2 || words.size != 2 || readings.any { !kana.matches(it) } || words.any { it.isEmpty() }) return null
        return listOf(UserDictionary.Entry(readings[0], words[0], "姓"), UserDictionary.Entry(readings[1], words[1], "名"))
    }

    fun expand(entries: List<UserDictionary.Entry>, evidence: (Set<String>) -> Sequence<UserDictionary.Entry>): List<UserDictionary.Entry> {
        val names = entries.filter { it.pos in inferPos && it.word.length in 1..32 }
        if (names.isEmpty()) return entries
        val wanted = names.flatMapTo(mutableSetOf()) { entry ->
            listOf(entry.word) + (1 until entry.word.length).flatMap { listOf(entry.word.substring(0, it), entry.word.substring(it)) }
        }
        val registeredParts = entries.asSequence().filter { it.pos == "姓" || it.pos == "名" }
            .map { UserDictionary.Entry(normalize(it.reading), it.word, it.pos) }
        val lookup = (evidence(wanted) + registeredParts).filter { it.word in wanted }
            .distinct().groupBy { it.word to it.pos }
        val full = entries.map { entry ->
            val original = full(entry)
            if (entry.pos != "名詞" && entry.pos != "固有名詞") original
            else {
                val reading = normalize(entry.reading)
                val label = listOf("姓", "名").singleOrNull { pos ->
                    lookup[entry.word to pos].orEmpty().any { it.reading == reading }
                }
                if (label == null) original else original.copy(pos = label)
            }
        }
        val seen = full.mapTo(hashSetOf()) { normalize(it.reading) to it.word }
        val parts = names.flatMap { entry ->
            explicit(entry) ?: run {
                val reading = normalize(entry.reading)
                if (!kana.matches(reading)) emptyList()
                else (1 until entry.word.length).flatMap { index ->
                    val left = entry.word.substring(0, index)
                    val right = entry.word.substring(index)
                    lookup[left to "姓"].orEmpty().flatMap { family ->
                        lookup[right to "名"].orEmpty().filter { given ->
                            family.reading.length + given.reading.length == reading.length &&
                                reading.startsWith(family.reading) && reading.endsWith(given.reading)
                        }.map { listOf(family, it) }
                    }
                }.distinct().singleOrNull().orEmpty()
            }
        }
        return full + parts.filter { seen.add(normalize(it.reading) to it.word) }
    }
}
