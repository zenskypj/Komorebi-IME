package me.zssu.ime.keyboard

/** Retains sub-step movement so small drags accumulate without jitter around a boundary. */
internal class TrackpadMotion(private val stepPx: Float, startX: Float) {
    private var anchor = startX
    init { require(stepPx > 0 && stepPx.isFinite()) }
    fun move(x: Float): Int {
        if (!x.isFinite()) return 0
        val steps = ((x - anchor) / stepPx).toInt().coerceIn(-8, 8)
        anchor += steps * stepPx
        return steps
    }
}
