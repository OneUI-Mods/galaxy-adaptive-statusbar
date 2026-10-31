package dev.ryuya.galaxybar

import android.content.Intent
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.SystemClock
import android.view.WindowManager.LayoutParams
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.util.function.Predicate
import java.util.regex.Pattern
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/* Requires the supported, unlocked phone and both motion hook hosts loaded. */
@RunWith(AndroidJUnit4::class)
open class MotionLifecycleTest {
    @Test
    @Throws(Exception::class)
    open fun overviewKeepsLiveColorsAndOffRemovesTheWindowAndOpacityCallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val prefs = context.getSharedPreferences("config", 0)
        val status = context.getSharedPreferences("status", 0)
        val original = prefs.getAll()
        assertFalse(context.getSharedPreferences("guard", 0).getBoolean("blocked", false))
        assertFalse(context.getSharedPreferences("guard_launcher", 0).getBoolean("blocked", false))
        prefs.edit().putBoolean("enabled", true).putBoolean("motion", true).commit()
        val intent = Intent(context, PatternActivity::class.java).putExtra("pattern", 3)
        try {
            ActivityScenario.launch<android.app.Activity>(intent).use { screen ->
                screen.onActivity({ activity ->
                    activity.getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
                })
                MotionLifecycleTest.await(
                    status,
                    System.currentTimeMillis(),
                    Predicate { s: String? -> s!!.contains("Capture OK") },
                )
                MotionLifecycleTest.shell("input keyevent 187")
                val overview =
                    MotionLifecycleTest.await(
                        status,
                        System.currentTimeMillis(),
                        Predicate { s: String? ->
                            s!!.contains("Motion: overview") &&
                                s!!.contains("Capture OK") &&
                                s!!.contains("palette=WINDOW") &&
                                s!!.contains("moving opacity=0.0;") &&
                                s!!.contains("opacity callbacks=0;")
                        },
                    )
                assertFalse(overview.contains("color spans=0"))
                assertTrue(overview.endsWith("Last error: "))
                val off = System.currentTimeMillis()
                prefs.edit().putBoolean("enabled", false).commit()
                val stopped =
                    MotionLifecycleTest.await(
                        status,
                        off,
                        Predicate { s: String? ->
                            s!!.contains(
                                "Color hooks: 0; fade callbacks: 0; fade targets: 0; applied views: 0"
                            ) &&
                                s!!.contains("Motion: disabled") &&
                                s!!.contains("opacity callbacks=0")
                        },
                    )
                assertTrue(stopped.endsWith("Last error: "))
                val windows = MotionLifecycleTest.shell("dumpsys window windows")
                assertFalse(
                    "Motion overlay must be removed",
                    Pattern.compile("(?m)^\\s*Window #\\d+ Window\\{[^\\n]*GalaxyBar transition")
                        .matcher(windows)
                        .find(),
                )
            }
        } finally {
            val edit = prefs.edit()
            for (key in arrayOf<String>("enabled", "motion")) {
                if (original.get(key) is Boolean) edit.putBoolean(key, original.get(key) as Boolean)
                else edit.remove(key)
            }
            edit.commit()
        }
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        private fun shell(command: String?): String? {
            val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation()
            AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
                return String(input.readAllBytes(), StandardCharsets.UTF_8)
            }
        }

        @JvmStatic
        private fun await(
            status: SharedPreferences,
            after: Long,
            expected: Predicate<String?>,
        ): String {
            val deadline: Long = SystemClock.elapsedRealtime() + 15000
            var latest: String = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                latest = status.getString("text", "")!!
                if (status.getLong("time", 0) >= after && expected.test(latest)) return latest
                SystemClock.sleep(50)
            }
            throw AssertionError("SystemUI state did not arrive: " + latest)
        }
    }
}
