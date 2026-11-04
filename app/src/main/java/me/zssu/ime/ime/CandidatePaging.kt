package me.zssu.ime.ime

/** Pack measured candidate widths into the user's row count, then navigate only that page. */
internal object CandidatePaging {
    const val MAX_CANDIDATES = 120
    data class Page(val rows: List<List<Int>>) {
        val indices: List<Int> get() = rows.flatten()
    }
    fun pages(widths: List<Int>, availableWidth: Int, rowCount: Int): List<Page> {
        val width = availableWidth.coerceAtLeast(1)
        val result = mutableListOf<Page>()
        val rows = mutableListOf<List<Int>>()
        var row = mutableListOf<Int>()
        var used = 0
        fun finishRow() {
            if (row.isEmpty()) return
            rows.add(row.toList()); row = mutableListOf(); used = 0
            if (rows.size == rowCount.coerceIn(1, 2)) {
                result.add(Page(rows.toList())); rows.clear()
            }
        }
        widths.forEachIndexed { index, requested ->
            val itemWidth = requested.coerceIn(1, width)
            if (used + itemWidth > width) finishRow()
            row.add(index); used += itemWidth
        }
        finishRow()
        if (rows.isNotEmpty()) result.add(Page(rows.toList()))
        return result
    }
    fun nextVisible(available: List<Int>, visible: List<Int>, focusedId: Int?): Int? {
        val page = visible.distinct().filter { it in available }
        if (page.isEmpty()) return null
        val position = page.indexOf(focusedId)
        return page[(position + 1) % page.size]
    }
    fun focusedId(window: List<Pair<Int, Int>>, globalIndex: Int): Int? = window.firstOrNull { it.first == globalIndex }?.second
}
