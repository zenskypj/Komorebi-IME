package me.zssu.ime.ime

import me.zssu.ime.settings.ImeSettings.ConversionEngine
import org.junit.Assert.*
import org.junit.Test

class KarukanFallbackPolicyTest {
    @Test fun automaticFallbackCoversDisabledOrMissingAiCandidates() {
        assertTrue(KarukanFallbackPolicy.shouldUse(ConversionEngine.AUTO, true, true, false))
        assertFalse(KarukanFallbackPolicy.shouldUse(ConversionEngine.AUTO, true, true, true))
    }
    @Test fun explicitKarukanWorksAlongsideAi() {
        assertTrue(KarukanFallbackPolicy.shouldUse(ConversionEngine.KARUKAN, true, true, true))
    }
    @Test fun privacyAndModelAvailabilityAlwaysWin() {
        for (mode in ConversionEngine.entries) {
            assertFalse(KarukanFallbackPolicy.shouldUse(mode, true, false, false))
            assertFalse(KarukanFallbackPolicy.shouldUse(mode, false, true, false))
        }
    }
    @Test fun mozcOnlyIsAnExplicitOptOut() {
        assertFalse(KarukanFallbackPolicy.shouldUse(ConversionEngine.MOZC, true, true, false))
    }
}
