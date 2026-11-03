package me.zssu.ime.ime

/** Index 0 is the small model; index 1 requires headroom for the model and runtime. */
internal object ProofreadRecommendation {
    fun select(availableMemory: Long, availableStorage: Long): Int =
        if (availableMemory >= 3500L * 1024 * 1024 && availableStorage > 1600L * 1024 * 1024) 1 else 0
}
