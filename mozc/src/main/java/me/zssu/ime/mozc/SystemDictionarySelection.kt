package me.zssu.ime.mozc

/**
 * Chooses which catalog dataset satisfies a set of selected source dictionaries.
 *
 * A catalog entry may bundle several sources (for example an "all" dataset that
 * carries every source). A selection is satisfied by the smallest entry that
 * contains every selected source, preferring fewer sources and then a smaller
 * download. A single selected source therefore stays on its own pack, while a
 * multi-selection uses a prebuilt combined dataset.
 */
internal object SystemDictionarySelection {
    fun resolve(sources: Set<String>, packs: List<SystemDictionaryManager.Pack>): SystemDictionaryManager.Pack? {
        if (sources.isEmpty()) return null
        return packs
            .filter { pack -> sources.all { it in covered(pack) } }
            .minWithOrNull(compareBy({ covered(it).size }, { it.dataBytes }))
    }

    private fun covered(pack: SystemDictionaryManager.Pack): Set<String> =
        (if (pack.sourceIds.isEmpty()) listOf(pack.id) else pack.sourceIds).toSet()
}
