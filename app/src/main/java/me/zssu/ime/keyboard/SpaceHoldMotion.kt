package me.zssu.ime.keyboard

import kotlin.math.abs

/** Joystick-like QWERTY space hold. Never catches up missed repeats in a burst. */
internal class SpaceHoldMotion(private val originX: Float, private val density: Float) {
    private var direction = 0
    private var lastStep = 0L

    fun tick(x: Float, now: Long): Int {
        val distance = (x - originX) / density
        val nextDirection = when {
            !distance.isFinite() || abs(distance) <= 14f -> 0
            distance < 0 -> -1
            else -> 1
        }
        if (nextDirection != direction || now < lastStep) {
            direction = nextDirection
            lastStep = now
            return 0
        }
        if (direction == 0) { lastStep = now; return 0 }
        val acceleration = ((abs(distance) - 14f) / 86f).coerceIn(0f, 1f)
        val interval = (320f - acceleration * 190f).toLong()
        if (now - lastStep < interval) return 0
        lastStep = now
        return direction
    }
}
