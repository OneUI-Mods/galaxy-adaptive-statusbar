package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real held/reversed One UI gesture; screenshots remain in the target's private cache. */
@RunWith(AndroidJUnit4.class)
public class MotionGestureTest {
  @Test
  public void heldAndReversedWindowKeepsLiveBarAndCapture() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    var status = context.getSharedPreferences("status", 0);
    assertTrue(context.getSharedPreferences("config", 0).getBoolean("enabled", false));
    assertTrue(context.getSharedPreferences("config", 0).getBoolean("motion", true));
    var automation = instrumentation.getUiAutomation();
    var display = context.getResources().getDisplayMetrics();
    float x = display.widthPixels / 2f;
    float y = display.heightPixels - 8;
    long down = 0;
    try (var screen = ActivityScenario.launch(MainActivity.class)) {
      screen.onActivity(
          a -> a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      SystemClock.sleep(1000);
      save(automation.takeScreenshot(), new File(context.getCacheDir(), "gesture-full.png"));
      down = SystemClock.uptimeMillis();
      inject(down, MotionEvent.ACTION_DOWN, x, y);
      for (int i = 1; i <= 16; i++) {
        y = display.heightPixels - 8 - i * 24;
        inject(down, MotionEvent.ACTION_MOVE, x, y);
        SystemClock.sleep(16);
      }
      SystemClock.sleep(2200);
      save(automation.takeScreenshot(), new File(context.getCacheDir(), "gesture-held.png"));
      String held = status.getString("text", "");
      assertFalse(held, held.contains("Stopped after consecutive errors"));
      assertTrue(held, held.contains("Capture OK"));
      assertTrue(held, held.contains("palette=WINDOW"));
      assertFalse(held, held.contains("color spans=0"));
      for (int i = 1; i <= 12; i++) {
        y = display.heightPixels - 392 + i * 24;
        inject(down, MotionEvent.ACTION_MOVE, x, y);
        SystemClock.sleep(16);
      }
      SystemClock.sleep(700);
      save(automation.takeScreenshot(), new File(context.getCacheDir(), "gesture-reversed.png"));
      inject(down, MotionEvent.ACTION_UP, x, y);
      down = 0;
      SystemClock.sleep(1000);
      save(automation.takeScreenshot(), new File(context.getCacheDir(), "gesture-settled.png"));
      assertFalse(
          status.getString("text", ""),
          status.getString("text", "").contains("Stopped after consecutive errors"));
    } finally {
      if (down != 0) inject(down, MotionEvent.ACTION_CANCEL, x, y);
    }
  }

  private static void inject(long down, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue(
          InstrumentationRegistry.getInstrumentation()
              .getUiAutomation()
              .injectInputEvent(event, true));
    } finally {
      event.recycle();
    }
  }

  private static void save(Bitmap bitmap, File file) throws Exception {
    assertNotNull(bitmap);
    try (FileOutputStream out = new FileOutputStream(file)) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      bitmap.recycle();
    }
  }
}
