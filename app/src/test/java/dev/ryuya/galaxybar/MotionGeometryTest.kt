package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

/* Checks the coordinates actually used to choose each moving character's contrast. */
open class MotionGeometryTest {
    private fun frame(x: Float, y: Float, width: Float): MotionFrame? {
        return MotionFrame(1, 100, true, 42, true, true, x, y, width, 2000f, 1f, -1f, 20f, false)
    }

    @Test
    open fun eachGlyphUsesItsPositionInsideTheScaledWindow() {
        val a = MotionGeometry.map(frame(100f, 200f, 720f)!!, 1440f, 0f, 0f, 50f, 20f, 80f, 60f)
        assertEquals(MotionGeometry.Bounds(125f, 210f, 140f, 230f), a)
        val b = MotionGeometry.map(frame(100f, 200f, 720f)!!, 1440f, 0f, 0f, 90f, 20f, 120f, 60f)
        assertEquals(MotionGeometry.Bounds(145f, 210f, 160f, 230f), b)
    }

    @Test
    open fun rootOffsetsAreRemovedBeforeTheWindowTransform() {
        val a = MotionGeometry.map(frame(100f, 200f, 720f)!!, 1440f, 10f, 30f, 60f, 50f, 90f, 90f)
        assertEquals(MotionGeometry.Bounds(125f, 210f, 140f, 230f), a)
    }

    @Test
    open fun reverseAndOvershootUseOnlyTheCurrentNativeGeometry() {
        val widths =
            floatArrayOf(
                (1440).toFloat(),
                (1200).toFloat(),
                (900).toFloat(),
                (1100).toFloat(),
                (1500).toFloat(),
                (1000).toFloat(),
                (1440).toFloat(),
            )
        for (width in widths) {
            val a = MotionGeometry.map(frame(0f, 0f, width)!!, 1440f, 0f, 0f, 720f, 0f, 800f, 100f)
            assertEquals((width / 2).toFloat(), (a!!.left).toFloat(), (.0001f).toFloat())
        }
    }

    @Test
    open fun contrastCanDifferPerCharacterAndChangeDuringMotion() {
        val first = MotionGeometry.map(frame(100f, 200f, 720f)!!, 1440f, 0f, 0f, 50f, 20f, 80f, 60f)
        val second =
            MotionGeometry.map(frame(100f, 200f, 720f)!!, 1440f, 0f, 0f, 90f, 20f, 120f, 60f)
        assertNotEquals(contrast(first!!), contrast(second!!))
        val moved = MotionGeometry.map(frame(140f, 200f, 720f)!!, 1440f, 0f, 0f, 50f, 20f, 80f, 60f)
        assertNotEquals(contrast(first!!), contrast(moved!!))
    }

    private fun contrast(glyph: MotionGeometry.Bounds): Int {
        val background = (if ((glyph.left + glyph.right) / 2 < 142) 0 else 1).toDouble()
        return Contrast.choose(doubleArrayOf(background), 0, "robust", .15)
    }
}
