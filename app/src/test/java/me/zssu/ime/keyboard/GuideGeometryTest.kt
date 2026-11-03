package me.zssu.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideGeometryTest {
    @Test fun neighboringCellsFollowTheActualKeyPitch() {
        assertEquals(GuideGeometry.Offset(0f, 0f), GuideGeometry.offset(FlickDirection.CENTER, 96f, 76f, 2f))
        assertEquals(GuideGeometry.Offset(-100f, 0f), GuideGeometry.offset(FlickDirection.LEFT, 96f, 76f, 2f))
        assertEquals(GuideGeometry.Offset(100f, 0f), GuideGeometry.offset(FlickDirection.RIGHT, 96f, 76f, 2f))
        assertEquals(GuideGeometry.Offset(0f, -80f), GuideGeometry.offset(FlickDirection.UP, 96f, 76f, 2f))
        assertEquals(GuideGeometry.Offset(0f, 80f), GuideGeometry.offset(FlickDirection.DOWN, 96f, 76f, 2f))
    }
}
