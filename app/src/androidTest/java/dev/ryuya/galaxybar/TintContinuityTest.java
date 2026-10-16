package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.core.view.WindowCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real SystemUI tint updates must not replace an active adaptive palette, even for one frame. */
@RunWith(AndroidJUnit4.class)
public class TintContinuityTest {
  @Test
  public void nativeLightStatusRequestsKeepAdaptiveIconsAndOffRestoresLatestNativeTint() {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    var prefs = context.getSharedPreferences("config", 0);
    var status = context.getSharedPreferences("status", 0);
    Map<String, ?> original = prefs.getAll();
    prefs
        .edit()
        .putBoolean("enabled", true)
        .putBoolean("motion", false)
        .putInt("fade_ms", 0)
        .putInt("hz", 5)
        .commit();
    Intent intent = new Intent(context, PatternActivity.class).putExtra("pattern", 1);
    try (var screen = ActivityScenario.launch(intent)) {
      screen.onActivity(
          activity -> {
            activity
                .getWindow()
                .addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            WindowCompat.getInsetsController(
                    activity.getWindow(), activity.getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
          });
      long deadline = SystemClock.uptimeMillis() + 15000;
      while (!status.getString("text", "").contains("Capture OK")) {
        assertTrue("Adaptive capture did not start", SystemClock.uptimeMillis() < deadline);
        SystemClock.sleep(50);
      }
      SystemClock.sleep(800);
      Bitmap baseline = instrumentation.getUiAutomation().takeScreenshot();
      assertNotNull(baseline);
      int width = baseline.getWidth();
      int barHeight =
          context
              .getResources()
              .getDimensionPixelSize(
                  context.getResources().getIdentifier("status_bar_height", "dimen", "android"));
      ArrayList<Integer> pixels = new ArrayList<>();
      ArrayList<Integer> clockPixels = new ArrayList<>();
      // Right-hand status icons; exclude the battery's changing digits and the clock/chip.
      for (int y = 0; y < barHeight; y++) {
        for (int x = 0; x < width * 20 / 100; x++) {
          if (bright(baseline.getPixel(x, y))) clockPixels.add(y * width + x);
        }
        for (int x = width * 67 / 100; x < width * 91 / 100; x++) {
          if (bright(baseline.getPixel(x, y))) pixels.add(y * width + x);
        }
      }
      baseline.recycle();
      assertTrue("No stable white status icons found", pixels.size() > 80);
      assertTrue("No stable white clock found", clockPixels.size() > 80);
      double minimum = 1;
      double minimumClock = 1;
      for (int request = 0; request < 4; request++) {
        final boolean light = request % 2 == 0;
        screen.onActivity(
            activity ->
                WindowCompat.getInsetsController(
                        activity.getWindow(), activity.getWindow().getDecorView())
                    .setAppearanceLightStatusBars(light));
        for (int frame = 0; frame < 8; frame++) {
          Bitmap shot = instrumentation.getUiAutomation().takeScreenshot();
          assertNotNull(shot);
          minimum = Math.min(minimum, retention(shot, pixels, width));
          minimumClock = Math.min(minimumClock, retention(shot, clockPixels, width));
          shot.recycle();
          SystemClock.sleep(16);
        }
      }
      assertTrue(
          "Native tint flashed through adaptive icons: minimum retention=" + minimum, minimum > .8);
      assertTrue(
          "Native tint flashed through the adaptive clock: " + minimumClock, minimumClock > .8);
      screen.onActivity(
          activity ->
              WindowCompat.getInsetsController(
                      activity.getWindow(), activity.getWindow().getDecorView())
                  .setAppearanceLightStatusBars(true));
      SystemClock.sleep(500);
      long off = System.currentTimeMillis();
      prefs.edit().putBoolean("enabled", false).commit();
      deadline = SystemClock.uptimeMillis() + 15000;
      while (status.getLong("time", 0) < off
          || !status.getString("text", "").contains("Color hooks: 0;")) {
        assertTrue("OFF did not finish", SystemClock.uptimeMillis() < deadline);
        SystemClock.sleep(50);
      }
      Bitmap nativeShot = instrumentation.getUiAutomation().takeScreenshot();
      assertNotNull(nativeShot);
      double nativeRetention = retention(nativeShot, pixels, width);
      nativeShot.recycle();
      assertTrue(
          "OFF must restore the latest native dark icons, retention=" + nativeRetention,
          nativeRetention < .2);
    } finally {
      var edit = prefs.edit();
      for (String key : new String[] {"enabled", "motion", "fade_ms", "hz"}) {
        Object value = original.get(key);
        if (value instanceof Boolean b) edit.putBoolean(key, b);
        else if (value instanceof Integer i) edit.putInt(key, i);
        else edit.remove(key);
      }
      edit.commit();
    }
  }

  private static double retention(Bitmap bitmap, ArrayList<Integer> pixels, int width) {
    int retained = 0;
    for (int p : pixels) if (bright(bitmap.getPixel(p % width, p / width))) retained++;
    return retained / (double) pixels.size();
  }

  private static boolean bright(int pixel) {
    return ((pixel >> 16) & 255) > 160 && ((pixel >> 8) & 255) > 160 && (pixel & 255) > 160;
  }
}
