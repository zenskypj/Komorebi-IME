package me.zssu.ime.keyboard

internal data class VideoPlaybackRange(val startMs: Int, val endMs: Int) {
    fun isFull(durationMs: Int) = startMs == 0 && endMs == durationMs

    companion object {
        fun resolve(startFraction: Float, endFraction: Float, durationMs: Int): VideoPlaybackRange {
            val duration = durationMs.coerceAtLeast(1)
            val minimum = minOf(500, duration)
            val start = (duration * startFraction.coerceIn(0f, 1f)).toInt().coerceIn(0, duration - minimum)
            val end = (duration * endFraction.coerceIn(0f, 1f)).toInt().coerceIn(start + minimum, duration)
            return VideoPlaybackRange(start, end)
        }
    }
}
