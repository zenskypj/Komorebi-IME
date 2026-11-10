package me.zssu.ime.settings

import me.zssu.ime.theme.KeyboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DressUpLivePreviewTest {
    @Test
    fun `draft is delivered only on change and can be discarded`() {
        val draft = DressUpLivePreview.Draft(KeyboardTheme.Default, null, null)
        val changes = mutableListOf<DressUpLivePreview.Draft>()
        try {
            DressUpLivePreview.clear()
            DressUpLivePreview.listener = { changes.add(it) }
            DressUpLivePreview.update(draft)
            DressUpLivePreview.update(draft)
            assertEquals(listOf(draft), changes)
            DressUpLivePreview.clear()
            assertNull(DressUpLivePreview.current)
        } finally {
            DressUpLivePreview.listener = null
            DressUpLivePreview.clear()
        }
    }
}
