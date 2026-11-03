package me.zssu.ime.keyboard

/** Positions a neighbouring flick cell using the same pitch as the key layout. */
internal object GuideGeometry {
    data class Offset(val x: Float, val y: Float)
    fun offset(direction: FlickDirection, keyWidth: Float, keyHeight: Float, gap: Float): Offset {
        val pitchX = keyWidth + 2f * gap.coerceAtLeast(0f)
        val pitchY = keyHeight + 2f * gap.coerceAtLeast(0f)
        return when (direction) {
            FlickDirection.LEFT -> Offset(-pitchX, 0f)
            FlickDirection.RIGHT -> Offset(pitchX, 0f)
            FlickDirection.UP -> Offset(0f, -pitchY)
            FlickDirection.DOWN -> Offset(0f, pitchY)
            FlickDirection.CENTER -> Offset(0f, 0f)
        }
    }
}
