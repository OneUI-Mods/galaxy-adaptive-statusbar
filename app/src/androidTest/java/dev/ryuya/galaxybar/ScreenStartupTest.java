package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.app.LocaleManager;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Catches runtime Material layout errors that compilation and Lint cannot detect. */
@RunWith(AndroidJUnit4.class)
public class ScreenStartupTest {
  @Test
  public void crashProtectionDisablesMasterAndRequiresExplicitRecovery() {
    checkProtection("guard");
  }

  @Test
  public void launcherProtectionAlsoDisablesMasterAndRequiresExplicitRecovery() {
    checkProtection("guard_launcher");
  }

  private void checkProtection(String guardName) {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var guard = context.getSharedPreferences(guardName, 0);
    var prefs = context.getSharedPreferences("config", 0);
    boolean had = guard.contains("blocked"), blocked = guard.getBoolean("blocked", false);
    boolean wasEnabled = prefs.getBoolean("enabled", false);
    guard.edit().putBoolean("blocked", true).commit();
    prefs.edit().putBoolean("enabled", true).commit();
    try (var screen = ActivityScenario.launch(MainActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screen.onActivity(
          activity -> {
            com.google.android.material.materialswitch.MaterialSwitch master =
                activity.findViewById(R.id.module_enabled);
            com.google.android.material.button.MaterialButton reset =
                activity.findViewById(R.id.guard_reset);
            assertFalse(master.isEnabled());
            assertFalse(master.isChecked());
            assertTrue(reset.isEnabled());
            android.os.Bundle fake = new android.os.Bundle();
            fake.putBoolean("blocked", false);
            assertThrows(
                SecurityException.class,
                () ->
                    activity
                        .getContentResolver()
                        .call(ConfigProvider.URI, "guard_status", null, fake));
          });
    } finally {
      var edit = guard.edit();
      if (had) edit.putBoolean("blocked", blocked);
      else edit.remove("blocked");
      edit.commit();
      prefs.edit().putBoolean("enabled", wasEnabled).commit();
    }
  }

  @Test
  public void fadeDurationSurvivesRecreationAndReachesProvider() {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var prefs = context.getSharedPreferences("config", 0);
    boolean existed = prefs.contains("fade_ms");
    int original = prefs.getInt("fade_ms", 80);
    prefs.edit().putInt("fade_ms", 80).commit();
    try (var screen = ActivityScenario.launch(MainActivity.class)) {
      screen.onActivity(
          activity -> {
            com.google.android.material.slider.Slider slider =
                activity.findViewById(R.id.fade_duration_slider);
            assertNotNull(slider);
            assertEquals(80f, slider.getValue(), 0f);
            slider.requestFocus();
            slider.onKeyDown(
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals(90, prefs.getInt("fade_ms", -1));
          });
      screen.recreate();
      screen.onActivity(
          activity -> {
            com.google.android.material.slider.Slider slider =
                activity.findViewById(R.id.fade_duration_slider);
            assertEquals(90f, slider.getValue(), 0f);
            slider.requestFocus();
            for (int i = 0; i < 9; i++)
              slider.onKeyDown(
                  android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                  new android.view.KeyEvent(
                      android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT));
            assertEquals(0, prefs.getInt("fade_ms", -1));
            assertEquals(
                0,
                activity
                    .getContentResolver()
                    .call(ConfigProvider.URI, "config", null, null)
                    .getInt("fade_ms", -1));
            android.widget.TextView label = activity.findViewById(R.id.fade_duration_value);
            assertEquals(
                activity.getString(R.string.fade_duration_value, 0), label.getText().toString());
          });
    } finally {
      var editor = prefs.edit();
      if (existed) editor.putInt("fade_ms", original);
      else editor.remove("fade_ms");
      editor.commit();
    }
  }

  @Test
  public void materialSettingsLaunchAndRespectSystemBars() {
    try (var screen = ActivityScenario.launch(MainActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      SystemClock.sleep(300);
      screen.onActivity(
          activity -> {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            MaterialToolbar toolbar = find(decor, MaterialToolbar.class);
            assertNotNull(toolbar);
            assertEquals(4, count(decor, MaterialAutoCompleteTextView.class));
            var insets = ViewCompat.getRootWindowInsets(decor);
            assertNotNull(insets);
            int[] location = new int[2];
            toolbar.getLocationOnScreen(location);
            assertTrue(
                "Toolbar overlaps status bar",
                location[1] >= insets.getInsets(WindowInsetsCompat.Type.statusBars()).top);
          });
    }
  }

  @Test
  public void allLocalizedScreensLaunch() {
    var manager =
        InstrumentationRegistry.getInstrumentation()
            .getTargetContext()
            .getSystemService(LocaleManager.class);
    LocaleList original = manager.getApplicationLocales();
    try {
      for (String tag : new String[] {"ja", "en", "es", "ko", "zh-Hans"}) {
        manager.setApplicationLocales(LocaleList.forLanguageTags(tag));
        try (var screen = ActivityScenario.launch(MainActivity.class)) {
          screen.onActivity(activity -> assertFalse(activity.isFinishing()));
        }
        try (var screen = ActivityScenario.launch(PatternActivity.class)) {
          screen.onActivity(activity -> assertFalse(activity.isFinishing()));
        }
        try (var screen = ActivityScenario.launch(AboutActivity.class)) {
          screen.onActivity(activity -> assertFalse(activity.isFinishing()));
        }
      }
    } finally {
      manager.setApplicationLocales(original);
    }
  }

  private static <T> T find(View view, Class<T> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        T result = find(group.getChildAt(i), type);
        if (result != null) return result;
      }
    return null;
  }

  private static int count(View view, Class<?> type) {
    int n = type.isInstance(view) ? 1 : 0;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) n += count(group.getChildAt(i), type);
    return n;
  }
}
