package dev.ryuya.galaxybar;

import static android.app.ApplicationExitInfo.*;
import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class CrashLoopStateTest {
  private static CrashLoopState fresh() {
    CrashLoopState s = new CrashLoopState();
    s.observe(1000, 1, "", List.of());
    return s;
  }

  private static CrashLoopState roundTrip(CrashLoopState s) throws Exception {
    var out = new ByteArrayOutputStream();
    s.encode().store(out, "test");
    var p = new Properties();
    p.load(new ByteArrayInputStream(out.toByteArray()));
    return CrashLoopState.decode(p);
  }

  @Test
  public void thirdAbnormalExitAcrossProcessesLatchesProtection() throws Exception {
    var state = fresh();
    int[] reasons = {REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR};
    for (int i = 0; i < 3; i++) {
      state.arm(10 + i, 1000 + i * 1000);
      state = roundTrip(state);
      state.observe(
          2000 + i * 1000,
          1,
          "",
          List.of(new CrashLoopState.Exit(10 + i, 1500 + i * 1000, reasons[i], 0)));
      assertEquals(i == 2, state.blocked);
    }
    assertEquals("crash_loop", state.cause);
    CrashLoopState blocked = state;
    assertThrows(IllegalStateException.class, () -> blocked.arm(99, 6000));
  }

  @Test
  public void manualReloadsAreNotCrashesAndDoNotTriggerUnknownFallback() {
    var s = fresh();
    for (int i = 0; i < 10; i++) {
      s.arm(20 + i, 1000 + i * 100);
      s.observe(
          1100 + i * 100,
          1,
          "",
          List.of(new CrashLoopState.Exit(20 + i, 1050 + i * 100, REASON_SIGNALED, 15)));
    }
    assertFalse(s.blocked);
    assertTrue(s.crashes.isEmpty());
    assertTrue(s.launches.isEmpty());
  }

  @Test
  public void normalSystemKillsAreExcludedAndFatalReasonsAreIncluded() {
    for (int reason :
        new int[] {REASON_LOW_MEMORY, REASON_USER_REQUESTED, REASON_PACKAGE_UPDATED, REASON_OTHER})
      assertFalse(CrashLoopState.abnormal(reason, 0));
    assertFalse(CrashLoopState.abnormal(REASON_SIGNALED, 9));
    assertFalse(CrashLoopState.abnormal(REASON_SIGNALED, 15));
    assertTrue(CrashLoopState.abnormal(REASON_SIGNALED, 6));
    assertTrue(CrashLoopState.abnormal(REASON_SIGNALED, 11));
    assertTrue(CrashLoopState.abnormal(REASON_INITIALIZATION_FAILURE, 0));
    assertTrue(CrashLoopState.abnormal(REASON_EXCESSIVE_RESOURCE_USAGE, 0));
  }

  @Test
  public void oldCrashesExpireAndHistoryDoesNotCountTwice() {
    var s = fresh();
    s.arm(1, 1000);
    var exit = new CrashLoopState.Exit(1, 1500, REASON_CRASH, 0);
    s.observe(2000, 1, "", List.of(exit));
    s.observe(2500, 1, "", List.of(exit));
    assertEquals(1, s.crashes.size());
    s.observe(121501, 1, "", List.of(exit));
    assertTrue(s.crashes.isEmpty());
    assertFalse(s.blocked);
  }

  @Test
  public void crashAfterLongHealthyRunStillCounts() {
    var s = fresh();
    s.arm(1, 1000);
    s.observe(1_000_000, 1, "", List.of(new CrashLoopState.Exit(1, 999_900, REASON_CRASH, 0)));
    assertEquals(1, s.crashes.size());
  }

  @Test
  public void latchSurvivesPersistenceAndReboot() throws Exception {
    var s = fresh();
    s.blocked = true;
    s.cause = "crash_loop";
    s.crashes.add(1200L);
    s = roundTrip(s);
    s.observe(100_000, 2, "", List.of());
    assertTrue(s.blocked);
    assertEquals("crash_loop", s.cause);
  }

  @Test
  public void onlyNewExplicitResetTokenClearsTheLatch() throws Exception {
    var s = fresh();
    s.blocked = true;
    s.launches.put(1, 1000L);
    s.crashes.add(1500L);
    var old = new CrashLoopState.Exit(1, 1500, REASON_CRASH, 0);
    s.observe(2000, 1, "user-reset", List.of(old));
    assertFalse(s.blocked);
    assertTrue(s.crashes.isEmpty());
    s.arm(2, 2000);
    s = roundTrip(s);
    s.observe(3000, 1, "user-reset", List.of(new CrashLoopState.Exit(2, 2500, REASON_CRASH, 0)));
    assertEquals(1, s.crashes.size());
  }

  @Test
  public void unrelatedProcessesAndReusedPidHistoryAreIgnored() {
    var s = fresh();
    s.arm(20, 1000);
    s.observe(
        2000,
        1,
        "",
        List.of(
            new CrashLoopState.Exit(55, 1500, REASON_CRASH, 0),
            new CrashLoopState.Exit(20, 500, REASON_CRASH, 0)));
    assertTrue(s.crashes.isEmpty());
    assertTrue(s.launches.containsKey(20));
  }

  @Test
  public void missingExitReasonsDoNotTurnNormalReloadsIntoCrashes() {
    var s = fresh();
    for (int i = 1; i <= 3; i++) {
      s.observe(i * 1000, 1, "", List.of());
      s.arm(i, i * 1000);
    }
    s.observe(4000, 1, "", List.of());
    assertFalse(s.blocked);
    assertTrue(s.crashes.isEmpty());
  }

  @Test
  public void delayedManualExitHistoryDoesNotLatchProtection() throws Exception {
    var s = fresh();
    var history = new ArrayList<CrashLoopState.Exit>();
    for (int i = 1; i <= 6; i++) {
      s.arm(i, 1000 * i);
      s = roundTrip(s);
      // The previous termination may not have reached ActivityManager yet.
      s.observe(1000 * i + 100, 1, "", List.of());
      assertFalse(s.blocked);
      history.add(new CrashLoopState.Exit(i, 1000 * i + 50, REASON_SIGNALED, 15));
    }
    s.observe(8000, 1, "", history);
    assertFalse(s.blocked);
    assertTrue(s.launches.isEmpty());
  }

  @Test
  public void delayedConfirmedCrashesStillStopAtThree() throws Exception {
    var s = fresh();
    var history = new ArrayList<CrashLoopState.Exit>();
    for (int i = 1; i <= 3; i++) {
      s.arm(i, 1000 * i);
      s = roundTrip(s);
      s.observe(1000 * i + 100, 1, "", List.of());
      history.add(new CrashLoopState.Exit(i, 1000 * i + 50, REASON_CRASH, 0));
    }
    s.observe(5000, 1, "", history);
    assertTrue(s.blocked);
    assertEquals("crash_loop", s.cause);
  }

  @Test
  public void crashesWhileFeatureIsDisabledDoNotUseItsBudget() throws Exception {
    var s = fresh();
    for (int i = 1; i <= 6; i++) {
      s.arm(i, 1000 * i);
      s.disarm(i); // All feature hooks have been removed before this host exits.
      s = roundTrip(s);
      s.observe(1000 * i + 100, 1, "",
          List.of(new CrashLoopState.Exit(i, 1000 * i + 50, REASON_CRASH, 0)));
    }
    assertFalse(s.blocked);
    assertTrue(s.crashes.isEmpty());
    s.arm(99, 10000);
    s.observe(11000, 1, "", List.of(new CrashLoopState.Exit(99, 10500, REASON_CRASH, 0)));
    assertEquals(1, s.crashes.size());
  }

  @Test
  public void resettingProtectionPreservesTheLastStopForDiagnosis() throws Exception {
    var s = fresh();
    s.stop("hook_initialization", 1500);
    s = roundTrip(s);
    s.observe(2000, 1, "new-user-reset", List.of());
    assertFalse(s.blocked);
    assertEquals("", s.cause);
    assertEquals("hook_initialization", s.lastStopCause);
    assertEquals(1500, s.lastStopAt);
  }

  @Test
  public void legacyStopRemainsLatchedUntilExplicitReset() {
    Properties old = fresh().encode();
    old.remove("last_stop_at");
    old.remove("last_stop_cause");
    old.setProperty("blocked", "true");
    old.setProperty("cause", "unknown_rapid_restarts");
    var s = CrashLoopState.decode(old);
    s.observe(5000, 1, "", List.of());
    assertTrue(s.blocked);
    s.observe(6000, 1, "explicit-reset", List.of());
    assertFalse(s.blocked);
  }

  @Test
  public void clockRollbackClearsCountsWithoutClearingALatch() {
    var s = fresh();
    s.crashes.add(1200L);
    s.observe(2000, 1, "", List.of());
    s.observe(1000, 1, "", List.of());
    assertTrue(s.crashes.isEmpty());
    s.blocked = true;
    s.observe(500, 1, "", List.of());
    assertTrue(s.blocked);
  }

  @Test
  public void damagedStateIsRejectedInsteadOfSilentlyReset() {
    assertThrows(IllegalArgumentException.class, () -> CrashLoopState.decode(new Properties()));
    var p = fresh().encode();
    p.setProperty("blocked", "invalid");
    assertThrows(IllegalArgumentException.class, () -> CrashLoopState.decode(p));
  }

  @Test
  public void hostProcessesKeepIndependentCrashBudgets() throws Exception {
    var system = fresh();
    var launcher = fresh();
    for (var host : List.of(system, launcher)) {
      for (int i = 0; i < 2; i++) {
        host.arm(10 + i, 1000 + i * 1000);
        host.observe(
            2000 + i * 1000,
            1,
            "",
            List.of(new CrashLoopState.Exit(10 + i, 1500 + i * 1000, REASON_CRASH, 0)));
      }
    }
    launcher = roundTrip(launcher);
    launcher.arm(12, 3000);
    launcher.observe(
        4000, 1, "", List.of(new CrashLoopState.Exit(12, 3500, REASON_CRASH_NATIVE, 0)));
    assertTrue(launcher.blocked);
    assertFalse(system.blocked);
    assertEquals(2, system.crashes.size());
  }

  @Test
  public void hookFailureLatchRequiresANewResetEvenAfterRestartAndReboot() throws Exception {
    var host = fresh();
    host.blocked = true;
    host.cause = "hook_initialization";
    host = roundTrip(host);
    host.observe(5000, 1, "", List.of());
    assertTrue(host.blocked);
    host.observe(6000, 2, "", List.of());
    assertTrue(host.blocked);
    assertEquals("hook_initialization", host.cause);
    host.observe(7000, 2, "explicit-reset", List.of());
    assertFalse(host.blocked);
  }
}
