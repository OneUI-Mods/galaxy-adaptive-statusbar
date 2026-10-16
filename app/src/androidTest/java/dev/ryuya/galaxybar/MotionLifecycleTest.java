package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Requires the supported, unlocked phone and both motion hook hosts loaded. */
@RunWith(AndroidJUnit4.class)
public class MotionLifecycleTest {
  @Test
  public void overviewKeepsLiveColorsAndOffRemovesTheWindowAndOpacityCallbacks() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("config", 0);
    SharedPreferences status = context.getSharedPreferences("status", 0);
    Map<String, ?> original = prefs.getAll();
    assertFalse(context.getSharedPreferences("guard", 0).getBoolean("blocked", false));
    assertFalse(context.getSharedPreferences("guard_launcher", 0).getBoolean("blocked", false));
    prefs.edit().putBoolean("enabled", true).putBoolean("motion", true).commit();
    Intent intent = new Intent(context, PatternActivity.class).putExtra("pattern", 3);
    try (var screen = ActivityScenario.launch(intent)) {
      screen.onActivity(
          activity ->
              activity
                  .getWindow()
                  .addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      await(status, System.currentTimeMillis(), s -> s.contains("Capture OK"));
      shell("input keyevent 187");
      String overview =
          await(
              status,
              System.currentTimeMillis(),
              s ->
                  s.contains("Motion: overview")
                      && s.contains("Capture OK")
                      && s.contains("palette=WINDOW"));
      assertFalse(overview.contains("color spans=0"));
      assertTrue(overview.endsWith("Last error: "));
      long off = System.currentTimeMillis();
      prefs.edit().putBoolean("enabled", false).commit();
      String stopped =
          await(
              status,
              off,
              s ->
                  s.contains("Color hooks: 0; fade callbacks: 0; fade targets: 0; applied views: 0")
                      && s.contains("Motion: disabled")
                      && s.contains("opacity callbacks=0"));
      assertTrue(stopped.endsWith("Last error: "));
      String windows = shell("dumpsys window windows");
      assertFalse(
          "Motion overlay must be removed",
          Pattern.compile("(?m)^\\s*Window #\\d+ Window\\{[^\\n]*GalaxyBar transition")
              .matcher(windows)
              .find());
    } finally {
      var edit = prefs.edit();
      for (String key : new String[] {"enabled", "motion"}) {
        if (original.get(key) instanceof Boolean value) edit.putBoolean(key, value);
        else edit.remove(key);
      }
      edit.commit();
    }
  }

  private static String shell(String command) throws Exception {
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    try (var input =
        new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String await(SharedPreferences status, long after, Predicate<String> expected) {
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
