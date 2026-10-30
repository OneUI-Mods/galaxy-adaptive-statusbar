package dev.ryuya.galaxybar

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager.LayoutParams
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayList
import kotlin.jvm.JvmStatic
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/* Real SystemUI tint updates must not replace an active adaptive palette, even for one frame. */
@RunWith(AndroidJUnit4::class)
open class TintContinuityTest {
    @Test
    open fun nativeLightStatusRequestsKeepAdaptiveIconsAndOffRestoresLatestNativeTint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.getTargetContext()
        val prefs = context.getSharedPreferences("config", 0)
        val status = context.getSharedPreferences("status", 0)
        val original = prefs.getAll()
        prefs
            .edit()
            .putBoolean("enabled", true)
            .putBoolean("motion", false)
            .putInt("fade_ms", 0)
            .putInt("hz", 5)
            .commit()
        val intent = Intent(context, PatternActivity::class.java).putExtra("pattern", 1)
        try {
            ActivityScenario.launch<android.app.Activity>(intent).use { screen ->
                screen.onActivity({ activity ->
                    activity.getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
                    WindowCompat.getInsetsController(
                            activity.getWindow(),
                            activity.getWindow().getDecorView(),
                        )
                        .setAppearanceLightStatusBars(false)
                })
                var deadline: Long = SystemClock.uptimeMillis() + 15000
                while (!status.getString("text", "")!!.contains("Capture OK")) {
                    assertTrue(
                        "Adaptive capture did not start",
                        SystemClock.uptimeMillis() < deadline,
                    )
                    SystemClock.sleep(50)
                }
                SystemClock.sleep(800)
                val baseline = instrumentation.getUiAutomation().takeScreenshot()
                assertNotNull(baseline)
                val width = baseline.getWidth()
                val barHeight =
                    context
                        .getResources()
                        .getDimensionPixelSize(
                            context
                                .getResources()
                                .getIdentifier("status_bar_height", "dimen", "android")
                        )
                val pixels = ArrayList<Int?>()
                val clockPixels = ArrayList<Int?>()
                // Right-hand status icons; exclude the battery's changing digits and the
                // clock/chip.
                for (y in 0 until barHeight) {
                    for (x in 0 until width * 20 / 100) {
                        if (TintContinuityTest.bright(baseline.getPixel(x, y)))
                            clockPixels.add(y * width + x)
                    }
                    for (x in width * 67 / 100 until width * 91 / 100) {
                        if (TintContinuityTest.bright(baseline.getPixel(x, y)))
                            pixels.add(y * width + x)
                    }
                }
                baseline.recycle()
                assertTrue("No stable white status icons found", pixels.size > 80)
                assertTrue("No stable white clock found", clockPixels.size > 80)
                var minimum: Double = 1.0
                var minimumClock: Double = 1.0
                for (request in 0..3) {
                    val light: Boolean = request % 2 == 0
                    screen.onActivity({ activity ->
                        WindowCompat.getInsetsController(
                                activity.getWindow(),
                                activity.getWindow().getDecorView(),
                            )
                            .setAppearanceLightStatusBars(light)
                    })
                    for (frame in 0..7) {
                        val shot = instrumentation.getUiAutomation().takeScreenshot()
                        assertNotNull(shot)
                        minimum = min(minimum, TintContinuityTest.retention(shot, pixels, width))
                        minimumClock =
                            min(
                                minimumClock,
                                TintContinuityTest.retention(shot, clockPixels, width),
                            )
                        shot.recycle()
                        SystemClock.sleep(16)
                    }
                }
                assertTrue(
                    "Native tint flashed through adaptive icons: minimum retention=" + minimum,
                    minimum > .8,
                )
                assertTrue(
                    "Native tint flashed through the adaptive clock: " + minimumClock,
                    minimumClock > .8,
                )
                screen.onActivity({ activity ->
                    WindowCompat.getInsetsController(
                            activity.getWindow(),
                            activity.getWindow().getDecorView(),
                        )
                        .setAppearanceLightStatusBars(true)
                })
                SystemClock.sleep(500)
                val off = System.currentTimeMillis()
                prefs.edit().putBoolean("enabled", false).commit()
                deadline = SystemClock.uptimeMillis() + 15000
                while (
                    status.getLong("time", 0) < off ||
                        !status.getString("text", "")!!.contains("Color hooks: 0;")
                ) {
                    assertTrue("OFF did not finish", SystemClock.uptimeMillis() < deadline)
                    SystemClock.sleep(50)
                }
                val nativeShot = instrumentation.getUiAutomation().takeScreenshot()
                assertNotNull(nativeShot)
                val nativeRetention = TintContinuityTest.retention(nativeShot, pixels, width)
                nativeShot.recycle()
                assertTrue(
                    "OFF must restore the latest native dark icons, retention=" + nativeRetention,
                    nativeRetention < .2,
                )
            }
        } finally {
            val edit = prefs.edit()
            for (key in arrayOf<String>("enabled", "motion", "fade_ms", "hz")) {
                val value = original.get(key)
                if (value is Boolean) edit.putBoolean(key, value)
                else if (value is Int) edit.putInt(key, value) else edit.remove(key)
            }
            edit.commit()
        }
    }

    companion object {
        @JvmStatic
        private fun retention(bitmap: Bitmap, pixels: ArrayList<Int?>, width: Int): Double {
            var retained: Int = 0
            for (p in pixels) if (
                TintContinuityTest.bright(bitmap.getPixel(p!! % width, p / width))
            )
                retained++
            return retained / pixels.size.toDouble()
        }

        @JvmStatic
        private fun bright(pixel: Int): Boolean {
            return ((pixel shr 16) and 255) > 160 &&
                ((pixel shr 8) and 255) > 160 &&
                (pixel and 255) > 160
        }
    }
}
