package me.zssu.ime.ime

import me.zssu.ime.settings.ImeSettings.ConversionEngine

internal object KarukanFallbackPolicy {
    fun shouldUse(mode: ConversionEngine, ready: Boolean, permitted: Boolean, aiCandidateAvailable: Boolean): Boolean =
        ready && permitted && when (mode) {
            ConversionEngine.MOZC -> false
            ConversionEngine.KARUKAN -> true
            ConversionEngine.AUTO -> !aiCandidateAvailable
        }
}
