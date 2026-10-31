package dev.ryuya.galaxybar

import android.app.LocaleManager
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/* Catches runtime Material layout errors that compilation and Lint cannot detect. */
@RunWith(AndroidJUnit4::class)
open class ScreenStartupTest {
    @Test
    open fun crashProtectionDisablesMasterAndRequiresExplicitRecovery() {
        checkProtection("guard")
    }

    @Test
    open fun launcherProtectionAlsoDisablesMasterAndRequiresExplicitRecovery() {
        checkProtection("guard_launcher")
    }

    private fun checkProtection(guardName: String?) {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val guard = context.getSharedPreferences(guardName, 0)
        val prefs = context.getSharedPreferences("config", 0)
        val had = guard.contains("blocked")
        val blocked = guard.getBoolean("blocked", false)
        val wasEnabled = prefs.getBoolean("enabled", false)
        guard.edit().putBoolean("blocked", true).commit()
        prefs.edit().putBoolean("enabled", true).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                screen.onActivity({ activity ->
                    val master: com.google.android.material.materialswitch.MaterialSwitch =
                        activity.findViewById(R.id.module_enabled)
                    val reset: com.google.android.material.button.MaterialButton =
                        activity.findViewById(R.id.guard_reset)
                    assertFalse(master.isEnabled())
                    assertFalse(master.isChecked())
                    master.setChecked(true)
                    assertFalse(master.isChecked())
                    assertFalse(prefs.getBoolean("enabled", true))
                    assertTrue(reset.isEnabled())
                    val fake = Bundle()
                    fake.putBoolean("blocked", false)
                    assertThrows(
                        SecurityException::class.java,
                        {
                            activity
                                .getContentResolver()
                                .call(ConfigProvider.URI!!, "guard_status", null, fake)
                        },
                    )
                })
            }
        } finally {
            val edit = guard.edit()
            if (had) edit.putBoolean("blocked", blocked) else edit.remove("blocked")
            edit.commit()
            prefs.edit().putBoolean("enabled", wasEnabled).commit()
        }
    }

    @Test
    open fun fadeDurationSurvivesRecreationAndReachesProvider() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val prefs = context.getSharedPreferences("config", 0)
        val existed = prefs.contains("fade_ms")
        val original = prefs.getInt("fade_ms", 80)
        prefs.edit().putInt("fade_ms", 80).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                screen.onActivity({ activity ->
                    (activity.findViewById(R.id.settings_tabs) as TabLayout).getTabAt(1)!!.select()
                    val slider: com.google.android.material.slider.Slider =
                        activity.findViewById(R.id.fade_duration_slider)
                    assertNotNull(slider)
                    assertEquals(80f, slider.getValue(), 0f)
                    slider.requestFocus()
                    slider.onKeyDown(
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent(
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                        ),
                    )
                    assertEquals(90, prefs.getInt("fade_ms", -1))
                })
                screen.recreate()
                screen.onActivity({ activity ->
                    (activity.findViewById(R.id.settings_tabs) as TabLayout).getTabAt(1)!!.select()
                    val slider: com.google.android.material.slider.Slider =
                        activity.findViewById(R.id.fade_duration_slider)
                    assertEquals(90f, slider.getValue(), 0f)
                    slider.requestFocus()
                    for (i in 0..8) slider.onKeyDown(
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent(
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                        ),
                    )
                    assertEquals(0, prefs.getInt("fade_ms", -1))
                    assertEquals(
                        0,
                        activity
                            .getContentResolver()
                            .call(ConfigProvider.URI!!, "config", null, null)!!
                            .getInt("fade_ms", -1),
                    )
                    val label: android.widget.TextView =
                        activity.findViewById(R.id.fade_duration_value)
                    assertEquals(
                        activity.getString(R.string.fade_duration_value, 0),
                        label.getText().toString(),
                    )
                })
            }
        } finally {
            val editor = prefs.edit()
            if (existed) editor.putInt("fade_ms", original) else editor.remove("fade_ms")
            editor.commit()
        }
    }

    @Test
    open fun materialSettingsLaunchAndRespectSystemBars() {
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(300)
            screen.onActivity({ activity ->
                val decor: ViewGroup? = activity.getWindow().getDecorView() as ViewGroup?
                val toolbar =
                    ScreenStartupTest.find<MaterialToolbar>(decor, MaterialToolbar::class.java)
                assertNotNull(toolbar)
                assertEquals(
                    4,
                    ScreenStartupTest.count(decor, MaterialAutoCompleteTextView::class.java),
                )
                val insets = ViewCompat.getRootWindowInsets(decor!!)
                assertNotNull(insets)
                val location = IntArray(2)
                toolbar!!.getLocationOnScreen(location)
                assertTrue(
                    "Toolbar overlaps status bar",
                    location[1] >= insets!!.getInsets(Type.statusBars()).top,
                )
            })
        }
    }

    @Test
    open fun allLocalizedScreensLaunch() {
        val manager =
            InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getSystemService(LocaleManager::class.java)
        val original = manager.getApplicationLocales()
        try {
            for (tag in arrayOf<String>("ja", "en", "es", "ko", "zh-Hans")) {
                manager.setApplicationLocales(LocaleList.forLanguageTags(tag))
                ActivityScenario.launch(MainActivity::class.java).use { screen ->
                    screen.onActivity({ activity -> assertFalse(activity.isFinishing()) })
                }
                ActivityScenario.launch(PatternActivity::class.java).use { screen ->
                    screen.onActivity({ activity -> assertFalse(activity.isFinishing()) })
                }
                ActivityScenario.launch(AboutActivity::class.java).use { screen ->
                    screen.onActivity({ activity -> assertFalse(activity.isFinishing()) })
                }
            }
        } finally {
            manager.setApplicationLocales(original)
        }
    }

    companion object {
        @JvmStatic
        private fun <T> find(view: View?, type: Class<T>): T? {
            if (type.isInstance(view)) return type.cast(view)
            if (view is ViewGroup)
                for (i in 0 until view.getChildCount()) {
                    val result = ScreenStartupTest.find<T>(view.getChildAt(i), type)
                    if (result != null) return result
                }
            return null
        }

        @JvmStatic
        private fun count(view: View?, type: Class<*>): Int {
            var n: Int = if (type.isInstance(view)) 1 else 0
            if (view is ViewGroup)
                for (i in 0 until view.getChildCount()) n +=
                    ScreenStartupTest.count(view.getChildAt(i), type)
            return n
        }
    }
}
