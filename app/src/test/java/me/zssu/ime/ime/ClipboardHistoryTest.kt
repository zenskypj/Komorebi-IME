package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardHistoryTest {

    @Test
    fun newestEntryComesFirstAndDuplicateMovesToFront() {
        val history = ClipboardHistory()

        history.add("one")
        history.add("two")
        history.add("one")

        assertEquals(listOf("one", "two"), history.items())
    }

    @Test
    fun keepsOnlyConfiguredNumberOfEntries() {
        val history = ClipboardHistory(limit = 2)

        history.add("one")
        history.add("two")
        history.add("three")

        assertEquals(listOf("three", "two"), history.items())
    }

    @Test
    fun preservesClipboardWhitespaceButIgnoresBlankEntries() {
        val history = ClipboardHistory()

        history.add("  exact text\n")
        history.add(" \n\t ")

        assertEquals(listOf("  exact text\n"), history.items())
    }

    @Test
    fun expiredEntriesAreRemovedWithoutDiscardingRecentOnes() {
        var time = 1000L
        val history = ClipboardHistory(now = { time })
        history.add("old")
        time += 61_000
        history.add("new")
        assertEquals(listOf("new"), history.items(60_000))
        assertEquals(listOf("new"), history.items())
    }

    @Test
    fun clearRemovesEveryEntry() {
        val history = ClipboardHistory()
        history.add("one")
        history.add("two")

        history.clear()

        assertEquals(emptyList<String>(), history.items())
    }
}
