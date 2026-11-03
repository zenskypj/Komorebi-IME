package me.zssu.ime.ime

/**
 * Insets applied inside the IME content view.
 *
 * Modern Android and vendor IME windows disagree on whether the input view is pre-positioned above
 * the navigation/keyboard-switcher area. The *intersecting* insets are the per-device authority:
 * keep the panel background edge-to-edge, but pad interactive content away from reported overlap.
 *
 * Ignoring-visibility/stable insets must not be used here. On API 24–34 they can describe a
 * navigation bar that the window manager has already placed outside the IME window, which would
 * count the same space twice. API 35+ edge-to-edge windows report the actual overlap directly.
 */
internal object KeyboardInsetPolicy {
    data class Padding(
        val left: Int,
        val right: Int,
        val bottom: Int,
    )

    fun contentPadding(
        left: Int,
        right: Int,
        bottom: Int,
        navigationVisible: Boolean,
        previous: Padding = Padding(0, 0, 0),
    ): Padding = Padding(
        left = resolveEdge(left, navigationVisible, previous.left),
        right = resolveEdge(right, navigationVisible, previous.right),
        bottom = resolveEdge(bottom, navigationVisible, previous.bottom),
    )

    /** Visible overlap with the physical bottom navigation area, in screen pixels. */
    fun bottomOverlap(viewBottom: Int, displayBottom: Int, navigationHeight: Int): Int {
        if (viewBottom <= 0 || displayBottom <= 0 || navigationHeight <= 0) return 0
        return (viewBottom - (displayBottom - navigationHeight)).coerceIn(0, navigationHeight)
    }

    /**
     * A hidden navigation bar can produce one transient all-zero dispatch while returning from a
     * settings Activity. Retain the last intersection for that frame. Once the bar is visible, a
     * reported zero is meaningful: the old window manager already kept it outside our window.
     */
    private fun resolveEdge(reported: Int, navigationVisible: Boolean, previous: Int): Int {
        val safeReported = reported.coerceAtLeast(0)
        return if (safeReported > 0 || navigationVisible) safeReported else previous.coerceAtLeast(0)
    }
}
