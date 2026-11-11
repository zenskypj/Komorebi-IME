package me.zssu.ime.ime

/** Pack measured candidate widths into pages while retaining every candidate. */
internal object CandidatePaging {
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
    fun nextCandidate(available: List<Int>, ordered: List<Int>, focusedId: Int?): Int? {
        val valid = available.toHashSet()
        val order = ordered.distinct().filter { it in valid }.toMutableList()
        val included = order.toHashSet()
        available.forEach { if (included.add(it)) order.add(it) }
        if (order.isEmpty()) return null
        val position = order.indexOf(focusedId)
        return order[(position + 1) % order.size]
    }
    fun focusedId(window: List<Pair<Int, Int>>, globalIndex: Int): Int? = window.firstOrNull { it.first == globalIndex }?.second
}
