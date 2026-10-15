package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Integration test: requires the supported phone, an unlocked screen, and the module loaded. */
@RunWith(AndroidJUnit4.class)
public class HookToggleTest {
  @Test
  public void settingsSwitchUnhooksWithoutWaitingForScreenUnlock() {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("config", 0);
    SharedPreferences status = context.getSharedPreferences("status", 0);
    boolean hadEnabled = prefs.contains("enabled"), enabled = prefs.getBoolean("enabled", false);
    try {
      long onAt = System.currentTimeMillis();
      prefs.edit().putBoolean("enabled", true).commit();
      awaitStatus(status, onAt, s -> s.contains("Color hooks: 11;"));
      long offAt = System.currentTimeMillis();
      try (var settings = ActivityScenario.launch(MainActivity.class)) {
        settings.onActivity(
            activity -> {
              com.google.android.material.materialswitch.MaterialSwitch master =
                  activity.findViewById(R.id.module_enabled);
              assertTrue(master.isChecked());
              master.performClick();
              assertFalse(master.isChecked());
            });
      }
      assertStopped(status, offAt);
      for (int i = 0; i < 8; i++) {
        prefs.edit().putBoolean("enabled", true).commit();
        SystemClock.sleep(15);
        prefs.edit().putBoolean("enabled", false).commit();
        SystemClock.sleep(15);
      }
      assertStopped(status, System.currentTimeMillis());
      onAt = System.currentTimeMillis();
      prefs.edit().putBoolean("enabled", true).commit();
      String resumed = awaitStatus(status, onAt, s -> s.contains("Color hooks: 11;"));
      assertTrue(resumed.contains("Battery: synthetic draw OK"));
      assertTrue(resumed.endsWith("Last error: "));
    } finally {
      var edit = prefs.edit();
      if (hadEnabled) edit.putBoolean("enabled", enabled);
      else edit.remove("enabled");
      edit.commit();
    }
  }

  @Test
  public void disablingDuringFadeUnhooksAndRejectsOldFramesAfterRapidToggles() {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("config", 0);
    SharedPreferences status = context.getSharedPreferences("status", 0);
    Map<String, ?> original = prefs.getAll();
    assertFalse(context.getSharedPreferences("guard", 0).getBoolean("blocked", false));
    prefs.edit().putInt("fade_ms", 500).putInt("hz", 30).putBoolean("enabled", true).commit();
    Intent pattern = new Intent(context, PatternActivity.class).putExtra("pattern", 4);
    try (var screen = ActivityScenario.launch(pattern)) {
      screen.onActivity(
          activity ->
              activity
                  .getWindow()
                  .addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      String active =
          awaitStatus(status, 0, s -> s.contains("Capture OK") && s.contains("fade callbacks: 1"));
      assertFalse(active.contains("Color hooks: 0;"));
      assertFalse(active.contains("applied views: 0"));

      // Keep the moving background visible while sending the switch's preference change.
      // The other test exercises the actual Material switch separately.
      long offAt = System.currentTimeMillis();
      prefs.edit().putBoolean("enabled", false).commit();
      assertStopped(status, offAt);

      // Re-enable without restarting SystemUI, then race toggles against queued capture work.
      prefs.edit().putBoolean("enabled", true).commit();
      awaitStatus(
          status,
          System.currentTimeMillis(),
          s -> s.contains("Capture OK") && !s.contains("Color hooks: 0;"));
      for (int i = 0; i < 8; i++) {
        prefs.edit().putBoolean("enabled", false).commit();
        SystemClock.sleep(15);
        prefs.edit().putBoolean("enabled", true).commit();
        SystemClock.sleep(15);
      }
      offAt = System.currentTimeMillis();
      prefs.edit().putBoolean("enabled", false).commit();
      assertStopped(status, offAt);

      long onAt = System.currentTimeMillis();
      prefs.edit().putBoolean("enabled", true).commit();
      String resumed =
          awaitStatus(
              status, onAt, s -> s.contains("Capture OK") && s.contains("fade callbacks: 1"));
      assertFalse(resumed.contains("Color hooks: 0;"));
      assertTrue(resumed.contains("Battery: synthetic draw OK"));
      assertTrue(resumed.endsWith("Last error: "));
    } finally {
      SharedPreferences.Editor edit = prefs.edit();
      for (String key : new String[] {"enabled", "fade_ms", "hz"}) {
        Object value = original.get(key);
        if (value instanceof Boolean b) edit.putBoolean(key, b);
        else if (value instanceof Integer i) edit.putInt(key, i);
        else edit.remove(key);
      }
      edit.commit();
    }
  }

  private static void assertStopped(SharedPreferences status, long after) {
    Predicate<String> stopped =
        s ->
            s.contains("module disabled")
                && s.contains(
                    "Color hooks: 0; fade callbacks: 0; fade targets: 0; applied views: 0");
    String first = awaitStatus(status, after, stopped);
    long firstTime = status.getLong("time", 0);
    String second = awaitStatus(status, firstTime + 1, stopped);
    assertEquals("Capture must stop while OFF", frame(first), frame(second));
    assertTrue(second.endsWith("Last error: "));
  }

  private static String frame(String status) {
    return status.substring(status.indexOf("Frame "), status.indexOf("; capture+analysis"));
  }

  private static String awaitStatus(
      SharedPreferences status, long after, Predicate<String> expected) {
    long deadline = SystemClock.elapsedRealtime() + 15000;
    String latest = "";
    while (SystemClock.elapsedRealtime() < deadline) {
      latest = status.getString("text", "");
      if (status.getLong("time", 0) >= after && expected.test(latest)) return latest;
      SystemClock.sleep(50);
    }
    throw new AssertionError("SystemUI state did not arrive: " + latest);
  }
}
