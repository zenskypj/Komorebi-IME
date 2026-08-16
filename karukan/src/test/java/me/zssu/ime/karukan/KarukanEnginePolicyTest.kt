package me.zssu.ime.karukan

import org.junit.Assert.assertEquals
import org.junit.Test

class KarukanEnginePolicyTest {

    @Test
    fun `low ram devices use at most two workers`() {
        assertEquals(1, KarukanEngine.recommendedThreadCount(1, lowRamDevice = true))
        assertEquals(2, KarukanEngine.recommendedThreadCount(8, lowRamDevice = true))
    }

    @Test
    fun `normal devices leave a core free and cap workers`() {
        assertEquals(1, KarukanEngine.recommendedThreadCount(2, lowRamDevice = false))
        assertEquals(3, KarukanEngine.recommendedThreadCount(4, lowRamDevice = false))
        assertEquals(4, KarukanEngine.recommendedThreadCount(12, lowRamDevice = false))
    }
}
