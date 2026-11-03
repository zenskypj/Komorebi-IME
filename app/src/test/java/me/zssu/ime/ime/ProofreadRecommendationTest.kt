package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ProofreadRecommendationTest {
    @Test fun largerModelRequiresBothMemoryAndStorage() {
        val mib = 1024L * 1024
        assertEquals(1, ProofreadRecommendation.select(3500 * mib, 1601 * mib))
        assertEquals(0, ProofreadRecommendation.select(3499 * mib, 2000 * mib))
        assertEquals(0, ProofreadRecommendation.select(4000 * mib, 1600 * mib))
    }
}
