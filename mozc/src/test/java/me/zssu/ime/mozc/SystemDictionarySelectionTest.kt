package me.zssu.ime.mozc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemDictionarySelectionTest {

    private fun pack(id: String, sourceIds: List<String> = emptyList(), bytes: Long = 1L) = SystemDictionaryManager.Pack(
        id = id, name = id, version = "v", license = "L", source = "S",
        url = "https://github.com/zenskypj/Komorebi-IME/releases/download/v/$id.data.gz",
        compressedBytes = bytes, compressedSha256 = "0", dataBytes = bytes, dataSha256 = "0",
        dataVersion = id, runtime = "r", notice = "n", sourceIds = sourceIds,
    )

    private val catalog = listOf(
        pack("neologd-main", listOf("neologd-main")),
        pack("neologd-variants", listOf("neologd-variants")),
        pack("skk-gpl", listOf("skk-gpl")),
        pack("skk-cc", listOf("skk-cc")),
        pack("neologd", listOf("neologd-main", "neologd-variants"), bytes = 5),
        pack("skk", listOf("skk-gpl", "skk-cc"), bytes = 5),
        pack("all", listOf("neologd-main", "neologd-variants", "skk-gpl", "skk-cc"), bytes = 9),
    )

    @Test
    fun emptySelectionResolvesToNothing() {
        assertNull(SystemDictionarySelection.resolve(emptySet(), catalog))
    }

    @Test
    fun singleSourceUsesItsOwnPack() {
        assertEquals("skk-cc", SystemDictionarySelection.resolve(setOf("skk-cc"), catalog)?.id)
    }

    @Test
    fun neologdPairUsesTheNeologdBundle() {
        assertEquals("neologd", SystemDictionarySelection.resolve(setOf("neologd-main", "neologd-variants"), catalog)?.id)
    }

    @Test
    fun skkPairUsesTheSkkBundle() {
        assertEquals("skk", SystemDictionarySelection.resolve(setOf("skk-gpl", "skk-cc"), catalog)?.id)
    }

    @Test
    fun crossGroupUsesTheAllBundle() {
        assertEquals("all", SystemDictionarySelection.resolve(setOf("neologd-main", "skk-gpl"), catalog)?.id)
    }

    @Test
    fun unknownCombinationResolvesToNothing() {
        assertNull(SystemDictionarySelection.resolve(setOf("neologd-main", "missing"), catalog))
    }

    @Test
    fun legacyPackWithoutSourceIdsMatchesItsOwnId() {
        assertEquals("legacy", SystemDictionarySelection.resolve(setOf("legacy"), listOf(pack("legacy")))?.id)
    }

    @Test
    fun prefersFewerSourcesThenSmallerDownload() {
        val candidates = listOf(
            pack("big", listOf("a", "b"), bytes = 100),
            pack("small", listOf("a", "b"), bytes = 10),
            pack("exact", listOf("a"), bytes = 50),
        )
        assertEquals("exact", SystemDictionarySelection.resolve(setOf("a"), candidates)?.id)
        assertEquals("small", SystemDictionarySelection.resolve(setOf("a", "b"), candidates)?.id)
    }
}
