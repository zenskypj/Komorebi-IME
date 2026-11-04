package me.zssu.ime.ime

/** A manually chosen reading prefix survives conversion and appending to that reading. */
internal class ReadingBoundary {
    private var prefix: String? = null

    fun moved(reading: String, beforeCodePoints: Int, afterCodePoints: Int) {
        if (beforeCodePoints != afterCodePoints) record(reading, afterCodePoints)
    }

    fun record(reading: String, cursorCodePoints: Int) {
        val count = reading.codePointCount(0, reading.length)
        prefix = if (cursorCodePoints in 1..count)
            reading.substring(0, reading.offsetByCodePoints(0, cursorCodePoints)) else null
    }

    fun update(reading: String) {
        if (prefix?.let { !reading.startsWith(it) } == true) prefix = null
    }

    fun target(reading: String): Int? {
        update(reading)
        return prefix?.let { it.codePointCount(0, it.length) }
    }

    fun clear() { prefix = null }

    companion object {
        /** Work in reading code points, never in the length of the displayed kanji. */
        fun <T> restore(initial: T, target: Int, readingLength: Int, length: (T) -> Int?, step: (T, Boolean) -> T?): T? {
            if (target !in 1..readingLength) return initial
            var current = initial
            repeat(readingLength) {
                val before = length(current) ?: return current
                if (before == target) return current
                val next = step(current, before < target) ?: return null
                val after = length(next) ?: return next
                current = next
                // Native boundary constraints must not create an unbounded command loop.
                if (after == before || kotlin.math.abs((after - target).toLong()) >= kotlin.math.abs((before - target).toLong())) return current
            }
            return current
        }
    }
}
