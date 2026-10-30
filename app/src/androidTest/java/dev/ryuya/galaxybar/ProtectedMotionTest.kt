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

/*
 * Protected pixels remain unavailable; expected rejection must pause motion and recover unaided.
 */
@RunWith(AndroidJUnit4::class)
open class ProtectedMotionTest {
    @Test
    open fun protectedContentPausesBothAndClearWindowRecoversWithoutToggling() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val prefs = context.getSharedPreferences("config", 0)
        val status = context.getSharedPreferences("status", 0)
        val original = prefs.getAll()
        prefs.edit().putBoolean("enabled", true).putBoolean("motion", true).commit()
        val intent = Intent(context, PatternActivity::class.java).putExtra("pattern", 1)
        try {
            ActivityScenario.launch<android.app.Activity>(intent).use { screen ->
                screen.onActivity({ a -> a.getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON) })
                ProtectedMotionTest.await(
                    status,
                    System.currentTimeMillis(),
                    Predicate { s: String? -> s!!.contains("Capture OK") },
                )
                val blockedAt = System.currentTimeMillis()
                screen.onActivity({ a -> a.getWindow().addFlags(LayoutParams.FLAG_SECURE) })
                val blocked =
                    ProtectedMotionTest.await(
                        status,
                        blockedAt,
                        Predicate { s: String? ->
                            s!!.contains("Paused: protected content") &&
                                s!!.contains("opacity callbacks=0") &&
                                s!!.contains("applied views: 0")
                        },
                    )
                assertFalse(blocked, blocked.contains("Stopped after consecutive errors"))
                assertTrue(prefs.getBoolean("enabled", false))
                assertTrue(prefs.getBoolean("motion", false))
                SystemClock.sleep(2200)
                assertFalse(
                    status.getString("text", "")!!.contains("Stopped after consecutive errors")
                )
                val clearAt = System.currentTimeMillis()
                screen.onActivity({ a -> a.getWindow().clearFlags(LayoutParams.FLAG_SECURE) })
                val recovered =
                    ProtectedMotionTest.await(
                        status,
                        clearAt,
                        Predicate { s: String? ->
                            s!!.contains("Capture OK") &&
                                s!!.contains("Motion: waiting for One UI Home") &&
                                !s!!.contains("applied views: 0")
                        },
                    )
                assertTrue(recovered, recovered.endsWith("Last error: "))
            }
        } finally {
            val edit = prefs.edit()
            for (key in arrayOf<String>("enabled", "motion")) {
                val value = original.get(key)
                if (value is Boolean) edit.putBoolean(key, value) else edit.remove(key)
            }
            edit.commit()
        }
    }

    companion object {
        @JvmStatic
        private fun await(
            status: SharedPreferences,
            after: Long,
            expected: Predicate<String?>,
        ): String {
            val until: Long = SystemClock.uptimeMillis() + 15000
            var latest: String = ""
            while (SystemClock.uptimeMillis() < until) {
                latest = status.getString("text", "")!!
                if (status.getLong("time", 0) >= after && expected.test(latest)) return latest
                SystemClock.sleep(50)
            }
            throw AssertionError(latest)
        }
    }
}
