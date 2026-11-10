package me.zssu.ime.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    private fun v(s: String) = requireNotNull(ReleaseVersion.parse(s))
    @Test fun `rc series uses numeric ordering and final release wins`() {
        assertTrue(v("v0.3.0-rc.10-dev") > v("0.3.0-rc.9-dev"))
        assertTrue(v("0.3.0-rc.57") > v("0.3.0-rc.57-dev"))
        assertTrue(v("v0.3.0") > v("0.3.0-rc.999-dev"))
        assertTrue(v("0.3.1-rc.1") > v("0.3.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
    }
    @Test fun `standard prereleases and build metadata are handled safely`() {
        assertTrue(v("1.0.0-rc.2") > v("1.0.0-beta.11"))
        assertEquals(v("1.0.0+4"), v("v1.0.0+5"))
        assertNull(ReleaseVersion.parse("latest"))
        assertNull(ReleaseVersion.parse("0.3.0-rc..1"))
        assertNull(ReleaseVersion.parse("9".repeat(1000) + ".1.0"))
    }
    @Test fun `selects published newest version regardless of release flag`() {
        val json = """[
            {"tag_name":"v0.3.0-rc.57-dev","published_at":"2026-01-01","prerelease":false},
            {"tag_name":"v0.3.0","published_at":"2026-01-02","prerelease":false},
            {"tag_name":"v0.4.0-rc.2","published_at":"2026-01-03","prerelease":true},
            {"tag_name":"v99.0.0","draft":true,"published_at":"2026-01-04"},
            {"tag_name":"not-a-version","published_at":"2026-01-04"}
        ]"""
        assertEquals(ReleaseSelector.Releases("v0.4.0-rc.2", "v0.3.0"), ReleaseSelector.select(json))
        assertEquals(ReleaseSelector.Releases("v0.3.0", "v0.3.0"), ReleaseSelector.select("""[{"tag_name":"v0.3.0-rc.57-dev","published_at":"x"},{"tag_name":"v0.3.0","published_at":"x"}]"""))
    }
}
