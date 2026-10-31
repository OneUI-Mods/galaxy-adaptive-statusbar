package dev.ryuya.galaxybar

import android.app.ApplicationExitInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.ArrayList
import java.util.List
import java.util.Properties
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

open class CrashLoopStateTest {
    @Test
    @Throws(Exception::class)
    open fun thirdAbnormalExitAcrossProcessesLatchesProtection() {
        var state = CrashLoopStateTest.fresh()
        val reasons =
            intArrayOf(
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
            )
        for (i in 0..2) {
            state.arm(10 + i, (1000 + i * 1000).toLong())
            state = CrashLoopStateTest.roundTrip(state)
            state.observe(
                (2000 + i * 1000).toLong(),
                1,
                "",
                List.of<CrashLoopState.Exit?>(
                    CrashLoopState.Exit(10 + i, (1500 + i * 1000).toLong(), reasons[i], 0)
                ),
            )
            assertEquals(i == 2, state.blocked)
        }
        assertEquals("crash_loop", state.cause)
        val blocked: CrashLoopState? = state
        assertThrows(IllegalStateException::class.java, { blocked!!.arm(99, 6000) })
    }

    @Test
    open fun manualReloadsAreNotCrashesAndDoNotTriggerUnknownFallback() {
        val s = CrashLoopStateTest.fresh()
        for (i in 0..9) {
            s.arm(20 + i, (1000 + i * 100).toLong())
            s.observe(
                (1100 + i * 100).toLong(),
                1,
                "",
                List.of<CrashLoopState.Exit?>(
                    CrashLoopState.Exit(
                        20 + i,
                        (1050 + i * 100).toLong(),
                        ApplicationExitInfo.REASON_SIGNALED,
                        15,
                    )
                ),
            )
        }
        assertFalse(s.blocked)
        assertTrue(s.crashes.isEmpty())
        assertTrue(s.launches.isEmpty())
    }

    @Test
    open fun normalSystemKillsAreExcludedAndFatalReasonsAreIncluded() {
        for (reason in
            intArrayOf(
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_PACKAGE_UPDATED,
                ApplicationExitInfo.REASON_OTHER,
            )) assertFalse(CrashLoopState.abnormal(reason, 0))
        assertFalse(CrashLoopState.abnormal(ApplicationExitInfo.REASON_SIGNALED, 9))
        assertFalse(CrashLoopState.abnormal(ApplicationExitInfo.REASON_SIGNALED, 15))
        assertTrue(CrashLoopState.abnormal(ApplicationExitInfo.REASON_SIGNALED, 6))
        assertTrue(CrashLoopState.abnormal(ApplicationExitInfo.REASON_SIGNALED, 11))
        assertTrue(CrashLoopState.abnormal(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, 0))
        assertTrue(CrashLoopState.abnormal(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, 0))
    }

    @Test
    open fun oldCrashesExpireAndHistoryDoesNotCountTwice() {
        val s = CrashLoopStateTest.fresh()
        s.arm(1, 1000)
        val exit = CrashLoopState.Exit(1, 1500, ApplicationExitInfo.REASON_CRASH, 0)
        s.observe(2000, 1, "", List.of<CrashLoopState.Exit?>(exit))
        s.observe(2500, 1, "", List.of<CrashLoopState.Exit?>(exit))
        assertEquals(1, s.crashes.size)
        s.observe(121501, 1, "", List.of<CrashLoopState.Exit?>(exit))
        assertTrue(s.crashes.isEmpty())
        assertFalse(s.blocked)
    }

    @Test
    open fun crashAfterLongHealthyRunStillCounts() {
        val s = CrashLoopStateTest.fresh()
        s.arm(1, 1000)
        s.observe(
            1000000,
            1,
            "",
            List.of<CrashLoopState.Exit?>(
                CrashLoopState.Exit(1, 999900, ApplicationExitInfo.REASON_CRASH, 0)
            ),
        )
        assertEquals(1, s.crashes.size)
    }

    @Test
    @Throws(Exception::class)
    open fun latchSurvivesPersistenceAndReboot() {
        var s = CrashLoopStateTest.fresh()
        s.blocked = true
        s.cause = "crash_loop"
        s.crashes.add(1200L)
        s = CrashLoopStateTest.roundTrip(s)
        s.observe(100000, 2, "", java.util.List.of<CrashLoopState.Exit?>())
        assertTrue(s.blocked)
        assertEquals("crash_loop", s.cause)
    }

    @Test
    @Throws(Exception::class)
    open fun onlyNewExplicitResetTokenClearsTheLatch() {
        var s = CrashLoopStateTest.fresh()
        s.blocked = true
        s.launches.put(1, 1000L)
        s.crashes.add(1500L)
        val old = CrashLoopState.Exit(1, 1500, ApplicationExitInfo.REASON_CRASH, 0)
        s.observe(2000, 1, "user-reset", List.of<CrashLoopState.Exit?>(old))
        assertFalse(s.blocked)
        assertTrue(s.crashes.isEmpty())
        s.arm(2, 2000)
        s = CrashLoopStateTest.roundTrip(s)
        s.observe(
            3000,
            1,
            "user-reset",
            List.of<CrashLoopState.Exit?>(
                CrashLoopState.Exit(2, 2500, ApplicationExitInfo.REASON_CRASH, 0)
            ),
        )
        assertEquals(1, s.crashes.size)
    }

    @Test
    open fun unrelatedProcessesAndReusedPidHistoryAreIgnored() {
        val s = CrashLoopStateTest.fresh()
        s.arm(20, 1000)
        s.observe(
            2000,
            1,
            "",
            List.of<CrashLoopState.Exit?>(
                CrashLoopState.Exit(55, 1500, ApplicationExitInfo.REASON_CRASH, 0),
                CrashLoopState.Exit(20, 500, ApplicationExitInfo.REASON_CRASH, 0),
            ),
        )
        assertTrue(s.crashes.isEmpty())
        assertTrue(s.launches.containsKey(20))
    }

    @Test
    open fun missingExitReasonsDoNotTurnNormalReloadsIntoCrashes() {
        val s = CrashLoopStateTest.fresh()
        for (i in 1..3) {
            s.observe((i * 1000).toLong(), 1, "", java.util.List.of<CrashLoopState.Exit?>())
            s.arm(i, (i * 1000).toLong())
        }
        s.observe(4000, 1, "", java.util.List.of<CrashLoopState.Exit?>())
        assertFalse(s.blocked)
        assertTrue(s.crashes.isEmpty())
    }

    @Test
    @Throws(Exception::class)
    open fun delayedManualExitHistoryDoesNotLatchProtection() {
        var s = CrashLoopStateTest.fresh()
        val history = ArrayList<CrashLoopState.Exit?>()
        for (i in 1..6) {
            s.arm(i, (1000 * i).toLong())
            s = CrashLoopStateTest.roundTrip(s)
            // The previous termination may not have reached ActivityManager yet.
            s.observe((1000 * i + 100).toLong(), 1, "", java.util.List.of<CrashLoopState.Exit?>())
            assertFalse(s.blocked)
            history.add(
                CrashLoopState.Exit(
                    i,
                    (1000 * i + 50).toLong(),
                    ApplicationExitInfo.REASON_SIGNALED,
                    15,
                )
            )
        }
        s.observe(8000, 1, "", history)
        assertFalse(s.blocked)
        assertTrue(s.launches.isEmpty())
    }

    @Test
    @Throws(Exception::class)
    open fun delayedConfirmedCrashesStillStopAtThree() {
        var s = CrashLoopStateTest.fresh()
        val history = ArrayList<CrashLoopState.Exit?>()
        for (i in 1..3) {
            s.arm(i, (1000 * i).toLong())
            s = CrashLoopStateTest.roundTrip(s)
            s.observe((1000 * i + 100).toLong(), 1, "", java.util.List.of<CrashLoopState.Exit?>())
            history.add(
                CrashLoopState.Exit(
                    i,
                    (1000 * i + 50).toLong(),
                    ApplicationExitInfo.REASON_CRASH,
                    0,
                )
            )
        }
        s.observe(5000, 1, "", history)
        assertTrue(s.blocked)
        assertEquals("crash_loop", s.cause)
    }

    @Test
    @Throws(Exception::class)
    open fun crashesWhileFeatureIsDisabledDoNotUseItsBudget() {
        var s = CrashLoopStateTest.fresh()
        for (i in 1..6) {
            s.arm(i, (1000 * i).toLong())
            s.disarm(i) // All feature hooks have been removed before this host exits.
            s = CrashLoopStateTest.roundTrip(s)
            s.observe(
                (1000 * i + 100).toLong(),
                1,
                "",
                List.of<CrashLoopState.Exit?>(
                    CrashLoopState.Exit(
                        i,
                        (1000 * i + 50).toLong(),
                        ApplicationExitInfo.REASON_CRASH,
                        0,
                    )
                ),
            )
        }
        assertFalse(s.blocked)
        assertTrue(s.crashes.isEmpty())
        s.arm(99, 10000)
        s.observe(
            11000,
            1,
            "",
            List.of<CrashLoopState.Exit?>(
                CrashLoopState.Exit(99, 10500, ApplicationExitInfo.REASON_CRASH, 0)
            ),
        )
        assertEquals(1, s.crashes.size)
    }

    @Test
    @Throws(Exception::class)
    open fun resettingProtectionPreservesTheLastStopForDiagnosis() {
        var s = CrashLoopStateTest.fresh()
        s.stop("hook_initialization", 1500)
        s = CrashLoopStateTest.roundTrip(s)
        s.observe(2000, 1, "new-user-reset", java.util.List.of<CrashLoopState.Exit?>())
        assertFalse(s.blocked)
        assertEquals("", s.cause)
        assertEquals("hook_initialization", s.lastStopCause)
        assertEquals(1500, s.lastStopAt)
    }

    @Test
    open fun legacyStopRemainsLatchedUntilExplicitReset() {
        val old = CrashLoopStateTest.fresh().encode()
        old.remove("last_stop_at")
        old.remove("last_stop_cause")
        old.setProperty("blocked", "true")
        old.setProperty("cause", "unknown_rapid_restarts")
        val s = CrashLoopState.decode(old)
        s.observe(5000, 1, "", java.util.List.of<CrashLoopState.Exit?>())
        assertTrue(s.blocked)
        s.observe(6000, 1, "explicit-reset", java.util.List.of<CrashLoopState.Exit?>())
        assertFalse(s.blocked)
    }

    @Test
    open fun clockRollbackClearsCountsWithoutClearingALatch() {
        val s = CrashLoopStateTest.fresh()
        s.crashes.add(1200L)
        s.observe(2000, 1, "", java.util.List.of<CrashLoopState.Exit?>())
        s.observe(1000, 1, "", java.util.List.of<CrashLoopState.Exit?>())
        assertTrue(s.crashes.isEmpty())
        s.blocked = true
        s.observe(500, 1, "", java.util.List.of<CrashLoopState.Exit?>())
        assertTrue(s.blocked)
    }

    @Test
    open fun damagedStateIsRejectedInsteadOfSilentlyReset() {
        assertThrows(IllegalArgumentException::class.java, { CrashLoopState.decode(Properties()) })
        val p = CrashLoopStateTest.fresh().encode()
        p.setProperty("blocked", "invalid")
        assertThrows(IllegalArgumentException::class.java, { CrashLoopState.decode(p) })
    }

    @Test
    @Throws(Exception::class)
    open fun hostProcessesKeepIndependentCrashBudgets() {
        val system = CrashLoopStateTest.fresh()
        var launcher = CrashLoopStateTest.fresh()
        for (host in List.of<CrashLoopState?>(system, launcher)) {
            for (i in 0..1) {
                host.arm(10 + i, (1000 + i * 1000).toLong())
                host.observe(
                    (2000 + i * 1000).toLong(),
                    1,
                    "",
                    List.of<CrashLoopState.Exit?>(
                        CrashLoopState.Exit(
                            10 + i,
                            (1500 + i * 1000).toLong(),
                            ApplicationExitInfo.REASON_CRASH,
                            0,
                        )
                    ),
                )
            }
        }
        launcher = CrashLoopStateTest.roundTrip(launcher)
        launcher.arm(12, 3000)
        launcher.observe(
            4000,
            1,
            "",
            List.of<CrashLoopState.Exit?>(
                CrashLoopState.Exit(12, 3500, ApplicationExitInfo.REASON_CRASH_NATIVE, 0)
            ),
        )
        assertTrue(launcher.blocked)
        assertFalse(system.blocked)
        assertEquals(2, system.crashes.size)
    }

    @Test
    @Throws(Exception::class)
    open fun hookFailureLatchRequiresANewResetEvenAfterRestartAndReboot() {
        var host = CrashLoopStateTest.fresh()
        host.blocked = true
        host.cause = "hook_initialization"
        host = CrashLoopStateTest.roundTrip(host)
        host.observe(5000, 1, "", java.util.List.of<CrashLoopState.Exit?>())
        assertTrue(host.blocked)
        host.observe(6000, 2, "", java.util.List.of<CrashLoopState.Exit?>())
        assertTrue(host.blocked)
        assertEquals("hook_initialization", host.cause)
        host.observe(7000, 2, "explicit-reset", java.util.List.of<CrashLoopState.Exit?>())
        assertFalse(host.blocked)
    }

    companion object {
        @JvmStatic
        private fun fresh(): CrashLoopState {
            val s = CrashLoopState()
            s.observe(1000, 1, "", java.util.List.of<CrashLoopState.Exit?>())
            return s
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun roundTrip(s: CrashLoopState): CrashLoopState {
            val out = ByteArrayOutputStream()
            s.encode().store(out, "test")
            val p = Properties()
            p.load(ByteArrayInputStream(out.toByteArray()))
            return CrashLoopState.decode(p)
        }
    }
}
