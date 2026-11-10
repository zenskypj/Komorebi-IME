package me.zssu.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun `dictionary search spans the conversion category`() {
        val results = SettingsCatalog.search("辞書")
        val ids = results.map { it.id }
        assertTrue("engine_dict" in ids)
        assertTrue("user_dictionary" in ids)
        assertTrue("meaning_dictionary" in ids)
        assertFalse("input_method" in ids)
    }

    @Test
    fun `search is case insensitive and includes keywords`() {
        assertTrue(SettingsCatalog.search("mozc").any { it.id == "engine_dict" })
        assertTrue(SettingsCatalog.search("バックアップ").any { it.id == "backup" })
    }

    @Test
    fun `backup is a direct destination from the data section and search`() {
        val backup = SettingsCatalog.search("バックアップ").single()
        assertEquals(SettingsGroup.DATA, backup.group)
        assertEquals(SettingsDestination.BACKUP, backup.destination)
        assertEquals(backup, SettingsCatalog.entries.single { it.id == backup.id })
    }

    @Test
    fun `keyboard setup actions are searchable`() {
        assertEquals(
            setOf(SettingsDestination.ENABLE_KEYBOARD, SettingsDestination.PICK_KEYBOARD),
            SettingsCatalog.search("初期設定").map { it.destination }.toSet(),
        )
    }
    @Test
    fun `theme and layout presets lead to the dedicated dress-up screen`() {
        for (term in listOf("きせかえ", "テーマ", "配列", "仕切り線")) {
            assertEquals(listOf(SettingsDestination.DRESS_UP), SettingsCatalog.search(term).map { it.destination })
        }
    }
}
