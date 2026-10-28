package com.aicomics.motionstudio.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEngineTest {
    @Test
    fun zoomInStartsAtOneAndEndsLarger() {
        val start = MotionEngine.evaluate(MotionPreset.ZOOM_IN, 0f)
        val end = MotionEngine.evaluate(MotionPreset.ZOOM_IN, 1f)
        assertEquals(1f, start.scale, 0.0001f)
        assertTrue(end.scale > start.scale)
    }

    @Test
    fun progressIsClamped() {
        val below = MotionEngine.evaluate(MotionPreset.PAN_RIGHT, -1f)
        val start = MotionEngine.evaluate(MotionPreset.PAN_RIGHT, 0f)
        assertEquals(start.translateX, below.translateX, 0.0001f)
    }

    @Test
    fun staticMotionDoesNotMoveCamera() {
        val transform = MotionEngine.evaluate(MotionPreset.STATIC, 0.75f)
        assertEquals(1f, transform.scale, 0.0001f)
        assertEquals(0f, transform.translateX, 0.0001f)
        assertEquals(0f, transform.translateY, 0.0001f)
    }
}
