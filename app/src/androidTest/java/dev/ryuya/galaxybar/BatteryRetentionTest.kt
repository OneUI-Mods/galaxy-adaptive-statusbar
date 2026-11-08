package dev.ryuya.galaxybar

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a fake renderer contract; does not load or hook SystemUI. */
@RunWith(AndroidJUnit4::class)
class BatteryRetentionTest {
    private class Battery : Drawable() {
        @JvmField val textPaint = Paint()
        @JvmField val warningTextPaint = Paint()
        @JvmField val batteryLevelPaint = Paint()
        @JvmField val batteryLevelBackgroundPaint = Paint()
        @JvmField val batteryOuterPaint = Paint()
        @JvmField val roundedRectPaint = Paint()
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun state(drawable: Drawable): Any =
        Class.forName(BatteryAdapter::class.java.name + "\$State")
            .getDeclaredConstructor(Drawable::class.java).apply { isAccessible = true }
            .newInstance(drawable)

    private fun cached(): Pair<WeakHashMap<Drawable, Any>, WeakReference<Drawable>> {
        val drawable = Battery()
        return WeakHashMap<Drawable, Any>().apply { put(drawable, state(drawable)) } to WeakReference(drawable)
    }

    @Test
    fun cachedPaintStateDoesNotKeepAnObsoleteDrawableAlive() {
        val (cache, owner) = cached()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (owner.get() != null && System.nanoTime() < deadline) {
            Runtime.getRuntime().gc() // Emulator-only test; production adds no GC calls.
            Thread.sleep(20)
        }
        assertNull(owner.get())
        assertTrue(cache.isEmpty())
    }

    @Test
    fun livePaintMappingStillCopiesWithoutMutatingNativePaint() {
        val drawable = Battery()
        drawable.textPaint.color = Color.RED
        val state = state(drawable)
        state.javaClass.getDeclaredField("percentColor").apply { isAccessible = true }.setInt(state, Color.WHITE)
        val replacement = state.javaClass.getDeclaredMethod("replacement", Paint::class.java).apply { isAccessible = true }
        val paint = replacement.invoke(state, drawable.textPaint) as Paint
        assertNotSame(drawable.textPaint, paint)
        assertEquals(Color.WHITE, paint.color)
        assertEquals(Color.RED, drawable.textPaint.color)
    }
}
