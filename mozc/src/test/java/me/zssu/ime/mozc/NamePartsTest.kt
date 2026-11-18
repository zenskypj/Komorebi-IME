package me.zssu.ime.mozc

import me.zssu.ime.mozc.UserDictionary.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class NamePartsTest {
    private val family = Entry("ふじよし", "藤吉", "姓")
    private val given = Entry("かりん", "夏鈴", "名")
    private val full = Entry("ふじよしかりん", "藤吉夏鈴")

    @Test fun retainsFullNameAndUpdatesDerivedCandidatesWithTheOriginal() {
        val evidence = sequenceOf(family, given)
        assertEquals(listOf(full, family, given), NameParts.expand(listOf(full)) { evidence })
        val edited = full.copy(reading = "ふじよしかしん")
        assertEquals(listOf(edited), NameParts.expand(listOf(edited)) { evidence })
        assertEquals(listOf(given), NameParts.expand(listOf(given.copy(pos = "名詞"))) { evidence })
    }

    @Test fun explicitBoundarySupportsUnlistedNameAndDoesNotDuplicateAnExistingPart() {
        val split = Entry("こまれ すずね", "小希 鈴音", "人名")
        val existing = Entry("すずね", "鈴音", "名")
        assertEquals(listOf(Entry("こまれすずね", "小希鈴音", "人名"), existing, Entry("こまれ", "小希", "姓")),
            NameParts.expand(listOf(split, existing)) { emptySequence() })
    }
}
