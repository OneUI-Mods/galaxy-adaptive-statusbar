package dev.ryuya.galaxybar

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager.LayoutParams
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/* Real held/reversed One UI gesture; screenshots remain in the target's private cache. */
@RunWith(AndroidJUnit4::class)
open class MotionGestureTest {
    @Test
    @Throws(Exception::class)
    open fun heldWindowFadesCompletelyAndReversalKeepsLiveCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.getTargetContext()
        val status = context.getSharedPreferences("status", 0)
        val prefs = context.getSharedPreferences("config", 0)
        assertTrue(prefs.getBoolean("enabled", false))
        assertTrue(prefs.getBoolean("motion", true))
        val nativeOnly =
            InstrumentationRegistry.getArguments().getString("native_bar", "false").toBoolean()
        val prefix: String? = if (nativeOnly) "native-gesture-" else "gesture-"
        val automation = instrumentation.getUiAutomation()
        val display = context.getResources().getDisplayMetrics()
        val x: Float = display.widthPixels / 2f
        var y: Float = (display.heightPixels - 8).toFloat()
        var down: Long = 0
        try {
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                if (nativeOnly) {
                    val off = System.currentTimeMillis()
                    prefs.edit().putBoolean("enabled", false).commit()
                    val until: Long = SystemClock.uptimeMillis() + 10000
                    while (
                        status.getLong("time", 0) < off ||
                            !status.getString("text", "")!!.contains("Color hooks: 0;")
                    ) {
                        assertTrue(SystemClock.uptimeMillis() < until)
                        SystemClock.sleep(50)
                    }
                }
                screen.onActivity({ a -> a.getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON) })
                SystemClock.sleep(1000)
                MotionGestureTest.save(
                    automation.takeScreenshot(),
                    File(context.getCacheDir(), prefix + "full.png"),
                )
                down = SystemClock.uptimeMillis()
                MotionGestureTest.inject(down, MotionEvent.ACTION_DOWN, x, y)
                for (i in 1..16) {
                    y = (display.heightPixels - 8).toFloat() - i * 24
                    MotionGestureTest.inject(down, MotionEvent.ACTION_MOVE, x, y)
                    SystemClock.sleep(16)
                }
                SystemClock.sleep(2200)
                MotionGestureTest.save(
                    automation.takeScreenshot(),
                    File(context.getCacheDir(), prefix + "held.png"),
                )
                val held = status.getString("text", "")
                assertFalse(held, held!!.contains("Stopped after consecutive errors"))
                if (nativeOnly) assertTrue(held, held!!.contains("Color hooks: 0;"))
                else {
                    assertTrue(held, held!!.contains("Capture OK"))
                    assertTrue(held, held!!.contains("palette=WINDOW"))
                    assertTrue(held, held!!.contains("moving opacity=0.0;"))
                    assertTrue(held, held!!.contains("opacity callbacks=0;"))
                    assertFalse(held, held!!.contains("color spans=0"))
                }
                for (i in 1..12) {
                    y = (display.heightPixels - 392 + i * 24).toFloat()
                    MotionGestureTest.inject(down, MotionEvent.ACTION_MOVE, x, y)
                    SystemClock.sleep(16)
                }
                SystemClock.sleep(700)
                MotionGestureTest.save(
                    automation.takeScreenshot(),
                    File(context.getCacheDir(), prefix + "reversed.png"),
                )
                MotionGestureTest.inject(down, MotionEvent.ACTION_UP, x, y)
                down = 0
                SystemClock.sleep(1000)
                MotionGestureTest.save(
                    automation.takeScreenshot(),
                    File(context.getCacheDir(), prefix + "settled.png"),
                )
                assertFalse(
                    status.getString("text", ""),
                    status.getString("text", "")!!.contains("Stopped after consecutive errors"),
                )
            }
        } finally {
            if (down != 0L) MotionGestureTest.inject(down, MotionEvent.ACTION_CANCEL, x, y)
            if (nativeOnly) prefs.edit().putBoolean("enabled", true).commit()
        }
    }

    companion object {
        @JvmStatic
        private fun inject(down: Long, action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            try {
                assertTrue(
                    InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation()
                        .injectInputEvent(event, true)
                )
            } finally {
                event.recycle()
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun save(bitmap: Bitmap, file: File?) {
            assertNotNull(bitmap)
            try {
                FileOutputStream(file).use { out ->
                    assertTrue(bitmap.compress(CompressFormat.PNG, 100, out))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
