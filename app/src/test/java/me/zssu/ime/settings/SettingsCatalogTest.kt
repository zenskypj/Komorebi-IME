package me.zssu.ime.settings

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
    fun `data tab excludes the backup shortcut because BackupCard is embedded`() {
        val data = SettingsCatalog.forTab(SettingsTab.DATA)
        assertTrue(data.any { it.id == "meets_beam" })
        assertTrue(data.any { it.id == "app_profiles" })
        assertFalse(data.any { it.id == "backup" })
    }

    @Test
    fun `every non-home tab has at least one destination`() {
        SettingsTab.entries.filter { it != SettingsTab.HOME }.forEach { tab ->
            assertTrue("$tab should have entries", SettingsCatalog.forTab(tab).isNotEmpty())
        }
        assertTrue(SettingsCatalog.forTab(SettingsTab.HOME).isEmpty())
    }

    @Test
    fun `featured entries include the data shortcuts`() {
        val featured = SettingsCatalog.featured().map { it.id }
        assertTrue("backup" in featured)
        assertTrue("meets_beam" in featured)
        assertTrue("input_method" in featured)
    }
}
