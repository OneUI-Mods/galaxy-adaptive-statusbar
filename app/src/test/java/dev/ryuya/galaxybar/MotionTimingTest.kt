package dev.ryuya.galaxybar

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

open class MotionTimingTest {
    @Test
    open fun normalOpeningHandoffBeginsInTheLastQuarterSecond() {
        val t = MotionTiming()
        assertEquals(
            (1).toDouble(),
            (t.update(true, .6f, 300f, 100).fixed).toDouble(),
            (0).toDouble(),
        )
        assertFalse(t.handedOff())
        t.update(true, .75f, 280f, 120)
        assertFalse(t.handedOff())
        t.update(true, .7f, 250f, 150)
        assertTrue(t.handedOff())
        assertEquals((0).toDouble(), (t.opacity(350).fixed).toDouble(), (0).toDouble())
        assertEquals((1).toDouble(), (t.opacity(350).moving).toDouble(), (0).toDouble())
    }

    @Test
    open fun shortAnimationDoesNotDrawATinyStatusBarInsideAnIcon() {
        val t = MotionTiming()
        assertEquals(
            (0).toDouble(),
            (t.update(true, .1f, 100f, 100).moving).toDouble(),
            (0).toDouble(),
        )
        assertFalse(t.handedOff())
        t.update(true, .5f, 60f, 150)
        assertTrue(t.handedOff())
    }

    @Test
    open fun homeUpSpringWithoutKnownDurationUsesRealWindowGeometry() {
        val t = MotionTiming()
        t.update(true, .6f, -1f, 100)
        assertFalse(t.handedOff())
        t.update(true, .82f, -1f, 150)
        assertTrue(t.handedOff())
        t.update(true, 1.02f, -1f, 250)
        assertEquals((1).toDouble(), (t.opacity(350).moving).toDouble(), (0).toDouble())
    }

    @Test
    open fun closingFollowsTheWindowThenRevealsTheHomeBar() {
        val t = MotionTiming()
        t.update(false, 1f, -1f, 100)
        assertEquals(
            (1).toDouble(),
            (t.update(false, .9f, -1f, 150).moving).toDouble(),
            (0).toDouble(),
        )
        t.update(false, .5f, -1f, 220)
        t.update(false, .2f, -1f, 300)
        assertEquals((1).toDouble(), (t.opacity(450).fixed).toDouble(), (0).toDouble())
        assertEquals((0).toDouble(), (t.opacity(450).moving).toDouble(), (0).toDouble())
    }

    @Test
    open fun bouncingSpringDoesNotRestartItsFade() {
        val t = MotionTiming()
        t.update(true, .81f, -1f, 100)
        val before = t.update(true, .9f, -1f, 150)!!.moving
        assertTrue(before > 0 && before < 1)
        assertTrue(t.update(true, .79f, -1f, 160)!!.moving > before)
        assertEquals(
            (1).toDouble(),
            (t.update(true, 1f, -1f, 300).moving).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun reversalKeepsOpacityAtTheSameInstantAndReturnsTheHomeBar() {
        val t = MotionTiming()
        t.update(true, .9f, 200f, 100)
        val before = t.update(true, .9f, 160f, 160)!!.moving
        assertEquals(
            (before).toDouble(),
            (t.update(false, .9f, -1f, 160).moving).toDouble(),
            (0).toDouble(),
        )
        t.update(false, .5f, -1f, 180)
        t.update(false, .2f, -1f, 280)
        assertEquals((1).toDouble(), (t.opacity(450).fixed).toDouble(), (0).toDouble())
        assertEquals((0).toDouble(), (t.opacity(450).moving).toDouble(), (0).toDouble())
        assertEquals(
            (0).toDouble(),
            (t.update(true, .2f, -1f, 460).moving).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (1).toDouble(),
            (t.update(true, 1f, -1f, 650).moving).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun nonLinearReversalAndOvershootNeverJumpOrExtrapolateOpacity() {
        val t = MotionTiming()
        var previous = t.update(false, 1f, -1f, 100)
        val widths = floatArrayOf(.98f, .9f, .6f, .35f, .7f, .4f, .8f, 1.1f, .95f, .25f)
        for (i in widths.indices) {
            val value = t.update(i % 2 == 0, widths[i], -1f, (116 + 16 * i).toLong())
            assertTrue(Math.abs(value!!.moving - previous!!.moving) <= .45f)
            assertTrue(Math.abs(value!!.fixed - previous!!.fixed) <= .45f)
            assertTrue(value!!.moving >= 0 && value!!.moving <= 1)
            assertTrue(value!!.fixed >= 0 && value!!.fixed <= 1)
            previous = value
        }
    }

    @Test
    open fun fastCurveDoesNotWaitUntilTheWindowHasAlreadySettled() {
        val t = MotionTiming()
        t.update(true, .6f, 600f, 100)
        assertFalse(t.handedOff())
        t.update(true, .81f, 500f, 150)
        assertTrue(t.handedOff())
        assertEquals(
            (1).toDouble(),
            (t.update(true, .95f, 300f, 350).moving).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun closingStartsOnTheMovingWindowRatherThanFadingIntoItLate() {
        val value = MotionTiming().update(false, .99f, -1f, 100)
        assertEquals((0).toDouble(), (value.fixed).toDouble(), (0).toDouble())
        assertEquals((1).toDouble(), (value.moving).toDouble(), (0).toDouble())
    }

    @Test
    open fun overviewDisappearsAndReturnsWithoutFlashingTheHomeBar() {
        val t = MotionTiming()
        t.update(false, 1f, -1f, 100)
        t.update(false, .55f, -1f, 150, true)
        val card = t.opacity(350)
        assertEquals((0).toDouble(), (card.fixed).toDouble(), (0).toDouble())
        assertEquals((0).toDouble(), (card.moving).toDouble(), (0).toDouble())
        assertEquals(card, t.update(true, .6f, -1f, 350))
        assertEquals(card, t.opacity(550))
        t.update(true, 1f, -1f, 550)
        assertEquals((0).toDouble(), (t.opacity(750).fixed).toDouble(), (0).toDouble())
        assertEquals((1).toDouble(), (t.opacity(750).moving).toDouble(), (0).toDouble())
    }

    @Test
    open fun screenBarDisappearsBeforeTheMovingBarBecomesBright() {
        val t = MotionTiming()
        t.update(true, .85f, 200f, 100)
        val early = t.opacity(130)
        assertTrue(early!!.fixed < .35f)
        assertEquals((0).toDouble(), (early.moving).toDouble(), (0).toDouble())
        val middle = t.opacity(180)
        assertTrue(middle!!.fixed < .01f)
        assertTrue(middle!!.moving > 0 && middle!!.moving < .6f)
        assertTrue(middle!!.fixed + middle!!.moving < .6f)
    }

    @Test
    open fun stationaryGeometryStillFadesAtDisplayFramesAndThenStops() {
        val t = MotionTiming()
        t.update(true, .85f, 200f, 100)
        assertTrue(t.running(140))
        assertTrue(t.opacity(180)!!.moving > t.opacity(150)!!.moving)
        assertEquals((1).toDouble(), (t.opacity(300).moving).toDouble(), (0).toDouble())
        assertFalse(t.running(300))
    }

    @Test
    open fun repeatedNativeFramesNeverExtendTheHandoff() {
        val t = MotionTiming()
        var now: Long = 100
        while (now <= 300) {
            t.update(true, .85f, 200f, now)
            now += 8
        }
        assertEquals((0).toDouble(), (t.opacity(300).fixed).toDouble(), (0).toDouble())
        assertEquals((1).toDouble(), (t.opacity(300).moving).toDouble(), (0).toDouble())
        assertFalse(t.running(300))
    }

    @Test
    open fun samplingAtSixtyOrOneHundredTwentyHertzDoesNotChangeTheCurve() {
        val a = MotionTiming()
        val b = MotionTiming()
        a.update(true, .85f, 200f, 100)
        b.update(true, .85f, 200f, 100)
        run {
            var now: Long = 100
            while (now < 210) {
                a.opacity(now)
                now += 16
            }
        }
        run {
            var now: Long = 100
            while (now < 210) {
                b.opacity(now)
                now += 8
            }
        }
        assertEquals(a.opacity(210), b.opacity(210))
        a.update(false, .65f, -1f, 210)
        b.update(false, .65f, -1f, 210)
        run {
            var now: Long = 210
            while (now < 270) {
                a.opacity(now)
                now += 16
            }
        }
        var now: Long = 210
        while (now < 270) {
            b.opacity(now)
            now += 8
        }
        assertEquals(a.opacity(270), b.opacity(270))
    }

    @Test
    open fun systemDurationScaleAppliesToBothFadesAndStagger() {
        val normal = MotionTiming()
        val slow = MotionTiming(2f)
        normal.update(true, .85f, 200f, 100)
        slow.update(true, .85f, 200f, 100)
        assertEquals(normal.opacity(170), slow.opacity(240))
        val disabled = MotionTiming(0f)
        assertEquals(MotionTiming.Opacity(0f, 1f), disabled.update(true, .85f, 200f, 100))
        assertFalse(disabled.running(100))
    }

    @Test
    open fun newOverviewCardStartsInvisibleInsteadOfFlashingBright() {
        val t = MotionTiming()
        val first = t.update(false, .6f, -1f, 100, true)
        assertEquals((0).toDouble(), (first.fixed).toDouble(), (0).toDouble())
        assertEquals((0).toDouble(), (first.moving).toDouble(), (0).toDouble())
        assertFalse(t.running(100))
    }

    @Test
    open fun heldGestureDisappearsCompletelyBeforeTheHomeDestinationIsCommitted() {
        val t = MotionTiming()
        t.update(false, 1f, -1f, 100, false, true)
        t.update(false, .45f, -1f, 200, false, true)
        assertEquals((0).toDouble(), (t.opacity(400).fixed).toDouble(), (0).toDouble())
        assertEquals((0).toDouble(), (t.opacity(400).moving).toDouble(), (0).toDouble())
        assertEquals(t.opacity(400), t.update(false, .45f, -1f, 400))
        assertEquals((0).toDouble(), (t.opacity(600).moving).toDouble(), (0).toDouble())
        assertTrue(t.opacity(600)!!.fixed > .8f)
    }

    @Test
    open fun heldGestureReversesFromActualWidthWithoutAnOpacityJump() {
        val t = MotionTiming()
        t.update(false, 1f, -1f, 100, false, true)
        t.update(false, .6f, -1f, 150, false, true)
        val before = t.opacity(190)
        assertEquals(before, t.update(false, .99f, -1f, 190, false, true))
        assertTrue(t.opacity(300)!!.moving > before!!.moving)
    }

    @Test
    open fun modestShrinkHeldForSecondsCannotLeaveAGhostBar() {
        val t = MotionTiming()
        t.update(false, 1f, -1f, 100, false, true)
        t.update(false, .89f, -1f, 150, false, true)
        assertTrue(t.opacity(190)!!.moving > 0)
        var now: Long = 166
        while (now <= 5000) {
            t.update(false, .89f, -1f, now, false, true)
            if (now >= 290) {
                assertEquals((0).toDouble(), (t.opacity(now).moving).toDouble(), (0).toDouble())
                assertEquals((0).toDouble(), (t.opacity(now).fixed).toDouble(), (0).toDouble())
                assertFalse(t.running(now))
            }
            now += 16
        }
    }

    @Test
    open fun shrinkingAndExpandingCanReverseAFadeRepeatedlyWithoutJumping() {
        val t = MotionTiming()
        t.update(false, 1f, -1f, 100, false, true)
        t.update(false, .86f, -1f, 150, false, true)
        var before = t.opacity(200)
        assertEquals(before, t.update(false, .99f, -1f, 200, false, true))
        assertTrue(t.opacity(240)!!.moving > before!!.moving)
        before = t.opacity(240)
        assertEquals(before, t.update(false, .86f, -1f, 240, false, true))
        assertEquals((0).toDouble(), (t.opacity(400).moving).toDouble(), (0).toDouble())
        assertEquals(
            (0).toDouble(),
            (t.update(false, 1f, -1f, 400, false, true).moving).toDouble(),
            (0).toDouble(),
        )
        assertEquals((1).toDouble(), (t.opacity(550).moving).toDouble(), (0).toDouble())
        assertEquals((0).toDouble(), (t.opacity(550).fixed).toDouble(), (0).toDouble())
    }

    @Test
    open fun ordinaryCloseAlsoDisappearsWhenItsWindowShrinks() {
        val t = MotionTiming()
        t.update(false, 1f, -1f, 100)
        t.update(false, .86f, -1f, 150)
        assertEquals((0).toDouble(), (t.opacity(300).moving).toDouble(), (0).toDouble())
        assertEquals((0).toDouble(), (t.opacity(300).fixed).toDouble(), (0).toDouble())
        assertFalse(t.running(300))
    }

    @Test
    open fun anAlmostZeroTargetStillReachesExactlyZero() {
        val t = MotionTiming()
        t.update(false, 1f, -1f, 100, false, true)
        t.update(false, .9001f, -1f, 150, false, true)
        t.update(false, .89f, -1f, 300, false, true)
        assertEquals((0).toDouble(), (t.opacity(500).moving).toDouble(), (0).toDouble())
        assertFalse(t.running(500))
    }
}
