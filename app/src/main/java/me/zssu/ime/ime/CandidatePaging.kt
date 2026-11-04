package me.zssu.ime.ime

/** Conversion advances only inside the six candidates currently presented as a page. */
internal object CandidatePaging {
    const val PAGE_SIZE = 6
    const val MAX_CANDIDATES = 120
    fun pageStart(count: Int, focused: Int): Int = if (count <= 0) 0 else focused.coerceIn(0, count - 1) / PAGE_SIZE * PAGE_SIZE
    fun pageEnd(count: Int, focused: Int): Int = (pageStart(count, focused) + PAGE_SIZE).coerceAtMost(count.coerceAtLeast(0))
    fun next(count: Int, focused: Int): Int? {
        if (count <= 0) return null
        if (focused !in 0 until count) return 0
        val start = pageStart(count, focused)
        return if (focused + 1 < pageEnd(count, focused)) focused + 1 else start
    }
    fun adjacentPage(count: Int, focused: Int, delta: Int): Int? {
        val target = pageStart(count, focused) + if (delta < 0) -PAGE_SIZE else PAGE_SIZE
        return target.takeIf { it in 0 until count }
    }
    fun focusedId(window: List<Pair<Int, Int>>, globalIndex: Int): Int? = window.firstOrNull { it.first == globalIndex }?.second
}
