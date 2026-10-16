package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Protected pixels remain unavailable; expected rejection must pause motion and recover unaided.
 */
@RunWith(AndroidJUnit4.class)
public class ProtectedMotionTest {
  @Test
  public void protectedContentPausesBothAndClearWindowRecoversWithoutToggling() {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var prefs = context.getSharedPreferences("config", 0);
    var status = context.getSharedPreferences("status", 0);
    Map<String, ?> original = prefs.getAll();
    prefs.edit().putBoolean("enabled", true).putBoolean("motion", true).commit();
    Intent intent = new Intent(context, PatternActivity.class).putExtra("pattern", 1);
    try (var screen = ActivityScenario.launch(intent)) {
      screen.onActivity(
          a -> a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      await(status, System.currentTimeMillis(), s -> s.contains("Capture OK"));
      long blockedAt = System.currentTimeMillis();
      screen.onActivity(a -> a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
      String blocked =
          await(
              status,
              blockedAt,
              s ->
                  s.contains("Paused: protected content")
                      && s.contains("opacity callbacks=0")
                      && s.contains("applied views: 0"));
      assertFalse(blocked, blocked.contains("Stopped after consecutive errors"));
      assertTrue(prefs.getBoolean("enabled", false));
      assertTrue(prefs.getBoolean("motion", false));
      SystemClock.sleep(2200);
      assertFalse(status.getString("text", "").contains("Stopped after consecutive errors"));
      long clearAt = System.currentTimeMillis();
      screen.onActivity(a -> a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
      String recovered =
          await(
              status,
              clearAt,
              s ->
                  s.contains("Capture OK")
                      && s.contains("Motion: waiting for One UI Home")
                      && !s.contains("applied views: 0"));
      assertTrue(recovered, recovered.endsWith("Last error: "));
    } finally {
      var edit = prefs.edit();
      for (String key : new String[] {"enabled", "motion"}) {
        Object value = original.get(key);
        if (value instanceof Boolean b) edit.putBoolean(key, b);
        else edit.remove(key);
      }
      edit.commit();
    }
  }

  private static String await(SharedPreferences status, long after, Predicate<String> expected) {
    long until = SystemClock.uptimeMillis() + 15000;
    String latest = "";
    while (SystemClock.uptimeMillis() < until) {
      latest = status.getString("text", "");
      if (status.getLong("time", 0) >= after && expected.test(latest)) return latest;
      SystemClock.sleep(50);
    }
    throw new AssertionError(latest);
  }
}
