package me.zssu.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardInsetPolicyTest {

    @Test
    fun `bottom OS control area is reserved from keyboard content`() {
        assertEquals(
            KeyboardInsetPolicy.Padding(left = 0, right = 0, bottom = 84),
            KeyboardInsetPolicy.contentPadding(
                left = 0,
                right = 0,
                bottom = 84,
                navigationVisible = true,
            ),
        )
    }

    @Test
    fun `first frame reserves only actual navigation overlap`() {
        assertEquals(24, KeyboardInsetPolicy.bottomOverlap(viewBottom = 1000, displayBottom = 1000, navigationHeight = 24))
        assertEquals(0, KeyboardInsetPolicy.bottomOverlap(viewBottom = 976, displayBottom = 1000, navigationHeight = 24))
        assertEquals(12, KeyboardInsetPolicy.bottomOverlap(viewBottom = 988, displayBottom = 1000, navigationHeight = 24))
        assertEquals(0, KeyboardInsetPolicy.bottomOverlap(viewBottom = 1000, displayBottom = 1000, navigationHeight = 0))
    }

    @Test
    fun `horizontal safety insets remain in landscape`() {
        assertEquals(
            KeyboardInsetPolicy.Padding(left = 48, right = 12, bottom = 20),
            KeyboardInsetPolicy.contentPadding(
                left = 48,
                right = 12,
                bottom = 20,
                navigationVisible = true,
            ),
        )
    }

    @Test
    fun `invalid negative insets cannot create negative padding`() {
        assertEquals(
            KeyboardInsetPolicy.Padding(left = 0, right = 0, bottom = 0),
            KeyboardInsetPolicy.contentPadding(
                left = -1,
                right = -20,
                bottom = -3,
                navigationVisible = true,
            ),
        )
    }

    @Test
    fun `pre edge to edge window clears already reserved navigation space`() {
        assertEquals(
            KeyboardInsetPolicy.Padding(left = 0, right = 0, bottom = 0),
            KeyboardInsetPolicy.contentPadding(
                left = 0,
                right = 0,
                bottom = 0,
                navigationVisible = true,
                previous = KeyboardInsetPolicy.Padding(0, 0, 84),
            ),
        )
    }

    @Test
    fun `transient hidden navigation dispatch retains last safe intersection`() {
        assertEquals(
            KeyboardInsetPolicy.Padding(left = 8, right = 12, bottom = 84),
            KeyboardInsetPolicy.contentPadding(
                left = 0,
                right = 0,
                bottom = 0,
                navigationVisible = false,
                previous = KeyboardInsetPolicy.Padding(8, 12, 84),
            ),
        )
    }
}
