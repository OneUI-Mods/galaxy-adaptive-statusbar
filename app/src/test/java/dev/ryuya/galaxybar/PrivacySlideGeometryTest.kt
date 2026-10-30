package dev.ryuya.galaxybar

import java.util.Arrays
import org.junit.Assert.*
import org.junit.Test

open class PrivacySlideGeometryTest {
    @Test
    open fun fullChipLeavesMeasuredGap() {
        val shift = PrivacySlideGeometry.shift(1397f, 1287f, 1407f, 12f, 1f, 1f)
        assertEquals((-122).toFloat(), (shift).toFloat(), (.01f).toFloat())
        assertEquals((12).toFloat(), (1287 - (1397 + shift)).toFloat(), (.01f).toFloat())
    }

    @Test
    open fun appearanceStartsWithoutJumpAndMovesMonotonically() {
        var previous: Float = 0f
        for (i in 0..100) {
            val current = PrivacySlideGeometry.shift(1397f, 1287f, 1407f, 12f, i / 100f, 1f)
            assertTrue(current <= previous + .001f)
            previous = current
        }
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.shift(1397f, 1287f, 1407f, 12f, 0f, 1f)).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun alphaAndNativeBoundsReturnIconsAsChipDisappears() {
        assertEquals(
            (-61).toFloat(),
            (PrivacySlideGeometry.shift(1397f, 1287f, 1407f, 12f, 1f, .5f)).toFloat(),
            (.01f).toFloat(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.shift(1397f, 1287f, 1407f, 12f, 1f, 0f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.shift(1397f, 1410f, 1430f, 12f, 1f, 1f)).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun invalidGeometryCannotWriteNaNTransforms() {
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.shift(1397f, Float.NaN, 1407f, 12f, 1f, 1f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.shift(1397f, 1407f, 1287f, 12f, 1f, 1f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.shift(1397f, 1287f, 1407f, Float.NaN, 1f, 1f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.shift(1397f, 1287f, 1407f, 12f, 1f, Float.NaN)).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun crowdedStatusBarFallsBackToNativeLayout() {
        assertTrue(PrivacySlideGeometry.fits(999f, -122f, 700f))
        assertFalse(PrivacySlideGeometry.fits(999f, -122f, 900f))
        assertFalse(PrivacySlideGeometry.fits(999f, -122f, Float.NaN))
    }

    @Test
    open fun everyOtherSamsungHideReasonIsPreserved() {
        val reasons = arrayOfNulls<Any>(8)
        Arrays.fill(reasons, false)
        reasons[1] = true
        assertFalse(PrivacySlideGeometry.otherHide(reasons))
        for (i in 0..7) if (i != 1) {
            reasons[i] = true
            assertTrue(PrivacySlideGeometry.otherHide(reasons))
            reasons[i] = false
        }
    }

    @Test
    open fun malformedNativeStateDeclinesTheOverride() {
        assertTrue(PrivacySlideGeometry.otherHide(null))
        assertTrue(PrivacySlideGeometry.otherHide(arrayOfNulls<Any>(7)))
        assertTrue(PrivacySlideGeometry.otherHide(arrayOfNulls<Any>(8)))
    }

    @Test
    open fun entranceSlidesTheWholeChipWithoutCuttingItsGlyphs() {
        assertEquals(
            (120).toDouble(),
            (PrivacySlideGeometry.entranceOffset(120f, 0f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (60).toDouble(),
            (PrivacySlideGeometry.entranceOffset(120f, .5f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.entranceOffset(120f, 1f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.entranceAlpha(0f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (1).toDouble(),
            (PrivacySlideGeometry.entranceAlpha(1f)).toDouble(),
            (0).toDouble(),
        )
        for (i in 0..100) {
            val progress: Float = i / 100f
            val shift = PrivacySlideGeometry.shift(1397f, 1287f, 1407f, 12f, progress, 1f)
            val chipLeft: Float = 1287 + PrivacySlideGeometry.entranceOffset(120f, progress)
            assertTrue(chipLeft >= 1397 + shift)
        }
    }

    @Test
    open fun persistentDotAddsTwoDpWithoutGrowingBeyondFiftyPercent() {
        assertEquals(
            (1.4f).toFloat(),
            (PrivacySlideGeometry.dotScale(15f, 3f)).toFloat(),
            (.001f).toFloat(),
        )
        assertEquals(
            (1.5f).toFloat(),
            (PrivacySlideGeometry.dotScale(3f, 3f)).toFloat(),
            (.001f).toFloat(),
        )
        assertEquals(
            (1).toDouble(),
            (PrivacySlideGeometry.dotScale(0f, 3f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (1).toDouble(),
            (PrivacySlideGeometry.dotScale(Float.NaN, 3f)).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun dotReservesASmallGapAndPreservesMeasuredSeparation() {
        assertEquals(
            (-12).toDouble(),
            (PrivacySlideGeometry.dotShift(1397f, 1415f, 12f, 1f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (-22).toDouble(),
            (PrivacySlideGeometry.dotShift(1397f, 1387f, 12f, 1f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (-6).toDouble(),
            (PrivacySlideGeometry.dotShift(1397f, 1415f, 12f, .5f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.dotShift(1397f, 1415f, 12f, 0f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.dotShift(1397f, Float.NaN, 12f, 1f)).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun handoverUsesOneReservationInsteadOfAddingBothOffsets() {
        assertEquals(
            (-122).toDouble(),
            (PrivacySlideGeometry.combinedShift(-122f, -12f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (-12).toDouble(),
            (PrivacySlideGeometry.combinedShift(-6f, -12f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (0).toDouble(),
            (PrivacySlideGeometry.combinedShift(0f, 0f)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (-12).toDouble(),
            (PrivacySlideGeometry.combinedShift(Float.NaN, -12f)).toDouble(),
            (0).toDouble(),
        )
    }
}
