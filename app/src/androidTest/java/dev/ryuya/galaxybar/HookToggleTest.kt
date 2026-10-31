package dev.ryuya.galaxybar

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.WindowManager.LayoutParams
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.function.Predicate
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/* Integration test: requires the supported phone, an unlocked screen, and the module loaded. */
@RunWith(AndroidJUnit4::class)
open class HookToggleTest {
    @Test
    open fun settingsSwitchUnhooksWithoutWaitingForScreenUnlock() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val prefs = context.getSharedPreferences("config", 0)
        val status = context.getSharedPreferences("status", 0)
        val hadEnabled = prefs.contains("enabled")
        val enabled = prefs.getBoolean("enabled", false)
        try {
            var onAt = System.currentTimeMillis()
            prefs.edit().putBoolean("enabled", true).commit()
            HookToggleTest.awaitStatus(
                status,
                onAt,
                Predicate { s: String? -> s!!.contains("Color hooks: 11;") },
            )
            val offAt = System.currentTimeMillis()
            ActivityScenario.launch(MainActivity::class.java).use { settings ->
                settings.onActivity({ activity ->
                    val master: com.google.android.material.materialswitch.MaterialSwitch =
                        activity.findViewById(R.id.module_enabled)
                    assertTrue(master.isChecked())
                    master.performClick()
                    assertFalse(master.isChecked())
                })
            }
            HookToggleTest.assertStopped(status, offAt)
            for (i in 0..7) {
                prefs.edit().putBoolean("enabled", true).commit()
                SystemClock.sleep(15)
                prefs.edit().putBoolean("enabled", false).commit()
                SystemClock.sleep(15)
            }
            HookToggleTest.assertStopped(status, System.currentTimeMillis())
            onAt = System.currentTimeMillis()
            prefs.edit().putBoolean("enabled", true).commit()
            val resumed =
                HookToggleTest.awaitStatus(
                    status,
                    onAt,
                    Predicate { s: String? -> s!!.contains("Color hooks: 11;") },
                )
            assertTrue(resumed.contains("Battery: synthetic draw OK"))
            assertTrue(resumed.endsWith("Last error: "))
        } finally {
            val edit = prefs.edit()
            if (hadEnabled) edit.putBoolean("enabled", enabled) else edit.remove("enabled")
            edit.commit()
        }
    }

    @Test
    open fun disablingDuringFadeUnhooksAndRejectsOldFramesAfterRapidToggles() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val prefs = context.getSharedPreferences("config", 0)
        val status = context.getSharedPreferences("status", 0)
        val original = prefs.getAll()
        assertFalse(context.getSharedPreferences("guard", 0).getBoolean("blocked", false))
        prefs.edit().putInt("fade_ms", 500).putInt("hz", 30).putBoolean("enabled", true).commit()
        val pattern = Intent(context, PatternActivity::class.java).putExtra("pattern", 4)
        try {
            ActivityScenario.launch<android.app.Activity>(pattern).use { screen ->
                screen.onActivity({ activity ->
                    activity.getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
                })
                val active =
                    HookToggleTest.awaitStatus(
                        status,
                        0,
                        Predicate { s: String? ->
                            s!!.contains("Capture OK") && s!!.contains("fade callbacks: 1")
                        },
                    )
                assertFalse(active.contains("Color hooks: 0;"))
                assertFalse(active.contains("applied views: 0"))

                // Keep the moving background visible while sending the switch's preference change.
                // The other test exercises the actual Material switch separately.
                var offAt = System.currentTimeMillis()
                prefs.edit().putBoolean("enabled", false).commit()
                HookToggleTest.assertStopped(status, offAt)

                // Re-enable without restarting SystemUI, then race toggles against queued capture
                // work.
                prefs.edit().putBoolean("enabled", true).commit()
                HookToggleTest.awaitStatus(
                    status,
                    System.currentTimeMillis(),
                    Predicate { s: String? ->
                        s!!.contains("Capture OK") && !s!!.contains("Color hooks: 0;")
                    },
                )
                for (i in 0..7) {
                    prefs.edit().putBoolean("enabled", false).commit()
                    SystemClock.sleep(15)
                    prefs.edit().putBoolean("enabled", true).commit()
                    SystemClock.sleep(15)
                }
                offAt = System.currentTimeMillis()
                prefs.edit().putBoolean("enabled", false).commit()
                HookToggleTest.assertStopped(status, offAt)

                val onAt = System.currentTimeMillis()
                prefs.edit().putBoolean("enabled", true).commit()
                val resumed =
                    HookToggleTest.awaitStatus(
                        status,
                        onAt,
                        Predicate { s: String? ->
                            s!!.contains("Capture OK") && s!!.contains("fade callbacks: 1")
                        },
                    )
                assertFalse(resumed.contains("Color hooks: 0;"))
                assertTrue(resumed.contains("Battery: synthetic draw OK"))
                assertTrue(resumed.endsWith("Last error: "))
            }
        } finally {
            val edit = prefs.edit()
            for (key in arrayOf<String>("enabled", "fade_ms", "hz")) {
                val value = original.get(key)
                if (value is Boolean) edit.putBoolean(key, value)
                else if (value is Int) edit.putInt(key, value) else edit.remove(key)
            }
            edit.commit()
        }
    }

    companion object {
        @JvmStatic
        private fun assertStopped(status: SharedPreferences, after: Long) {
            val stopped = Predicate { s: String? ->
                s!!.contains("module disabled") &&
                    s!!.contains(
                        "Color hooks: 0; fade callbacks: 0; fade targets: 0; applied views: 0"
                    )
            }
            val first = HookToggleTest.awaitStatus(status, after, stopped)
            val firstTime = status.getLong("time", 0)
            val second = HookToggleTest.awaitStatus(status, firstTime + 1, stopped)
            assertEquals(
                "Capture must stop while OFF",
                HookToggleTest.frame(first),
                HookToggleTest.frame(second),
            )
            assertTrue(second.endsWith("Last error: "))
        }

        @JvmStatic
        private fun frame(status: String): String? {
            return status.substring(status.indexOf("Frame "), status.indexOf("; capture+analysis"))
        }

        @JvmStatic
        private fun awaitStatus(
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
