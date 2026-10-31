package dev.ryuya.galaxybar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff.Mode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.widget.ImageView
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.lang.Boolean as JavaBoolean
import java.text.NumberFormat
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min

/* Recolors only the verified Samsung renderer, preserving its native geometry and animations. */
object BatteryAdapter {
    private val active: MutableMap<Drawable?, BatteryAdapter.State?>? =
        Collections.synchronizedMap<Drawable?, BatteryAdapter.State?>(
            WeakHashMap<Drawable?, BatteryAdapter.State?>()
        )
    private val drawing: ThreadLocal<BatteryAdapter.State?> = ThreadLocal<BatteryAdapter.State?>()
    @Volatile private var type: Class<*>? = null
    @Volatile private var validated: Boolean = false
    @Volatile private var health: String = "not validated"

    @JvmStatic
    @Synchronized
    @Throws(Exception::class)
    fun install(loader: ClassLoader?) {
        val candidate =
            Reflect.findClass("com.android.systemui.battery.SamsungBatteryMeterDrawable", loader)
        // Boot-framework Canvas calls may have been inlined into Samsung's precompiled renderer.
        Hooks.deoptimize(candidate!!.getDeclaredMethod("draw", Canvas::class.java))
        HookRegistry.hook(
            candidate,
            "draw",
            Canvas::class.java,
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("galaxy.previousBattery", BatteryAdapter.drawing.get())
                    val state = BatteryAdapter.active!!.get(p.thisObject as Drawable?)
                    if (state == null) BatteryAdapter.drawing.remove()
                    else BatteryAdapter.drawing.set(state)
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val previous: BatteryAdapter.State? =
                        p.getObjectExtra("galaxy.previousBattery") as BatteryAdapter.State?
                    if (previous == null) BatteryAdapter.drawing.remove()
                    else BatteryAdapter.drawing.set(previous)
                }
            },
        )
        val replacePaint: HookCallback =
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val state = BatteryAdapter.drawing.get()
                    if (state != null) {
                        val index: Int = p.args!!.size - 1
                        p!!.args!![index] = state.replacement(p!!.args!![index] as Paint?)
                    }
                }
            }
        HookRegistry.hook(
            Canvas::class.java,
            "drawRect",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Paint::class.java,
            replacePaint,
        )
        HookRegistry.hook(
            Canvas::class.java,
            "drawRoundRect",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Paint::class.java,
            replacePaint,
        )
        HookRegistry.hook(
            Canvas::class.java,
            "drawText",
            String::class.java,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Paint::class.java,
            replacePaint,
        )
        val specialIcon: HookCallback =
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val state = BatteryAdapter.drawing.get()
                    if (state == null) return
                    val icon: Drawable? = p.thisObject as Drawable?
                    p.setObjectExtra("galaxy.oldFilter", icon!!.getColorFilter())
                    p.setObjectExtra("galaxy.batteryIcon", true)
                    icon!!.setColorFilter(
                        if (state.backgroundOff) state.bodyColor else state.percentColor,
                        Mode.SRC_IN,
                    )
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (JavaBoolean.TRUE == p.getObjectExtra("galaxy.batteryIcon"))
                        (p.thisObject as Drawable).setColorFilter(
                            p.getObjectExtra("galaxy.oldFilter") as ColorFilter?
                        )
                }
            }
        HookRegistry.hook(BitmapDrawable::class.java, "draw", Canvas::class.java, specialIcon)
        HookRegistry.hook(VectorDrawable::class.java, "draw", Canvas::class.java, specialIcon)
        BatteryAdapter.type = candidate
    }

    @JvmStatic /* A synthetic, off-screen draw checks actual hooked Canvas calls without a screen capture. */
    @Synchronized
    fun validate(context: Context?) {
        if (BatteryAdapter.validated || BatteryAdapter.type == null) return
        var drawable: Drawable? = null
        var bitmap: Bitmap? = null
        try {
            drawable = Reflect.newInstance(BatteryAdapter.type!!, context) as Drawable?
            val battery = Reflect.getObjectField(drawable!!, "batteryState")
            Reflect.setIntField(battery!!, "level", 100)
            Reflect.setBooleanField(drawable!!, "notShowBatteryBackgroundSetting", false)
            Reflect.callMethod(drawable!!, "resizeDrawable")
            val w = drawable!!.getIntrinsicWidth()
            val h = drawable!!.getIntrinsicHeight()
            check(!(w <= 0 || h <= 0 || w > 512 || h > 512)) { "battery size" }
            bitmap = Bitmap.createBitmap(w, h, Config.ARGB_8888)
            val state = BatteryAdapter.State(drawable!!)
            state.bodyColor = -0x10000
            state.percentColor = -0xff0100
            BatteryAdapter.active!!.put(drawable, state)
            drawable!!.setBounds(0, 0, w, h)
            drawable!!.draw(Canvas(bitmap))
            var red: Int = 0
            var green: Int = 0
            for (y in 0 until h) for (x in 0 until w) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == -0x10000) red++
                if (pixel == -0xff0100) green++
            }
            check(!(red < 20 || green < 5)) {
                "synthetic colors missing: body=" + red + " label=" + green
            }
            BatteryAdapter.health = "synthetic draw OK"
        } catch (error: Throwable) {
            BatteryAdapter.health = "disabled: " + error
            BatteryAdapter.type = null
        } finally {
            if (drawable != null) BatteryAdapter.active!!.remove(drawable)
            if (bitmap != null) bitmap.recycle()
        }
        BatteryAdapter.validated = true
        Hooks.log("GalaxyBar: battery adapter " + BatteryAdapter.health)
    }

    @JvmStatic
    fun health(): String {
        return BatteryAdapter.health
    }

    @JvmStatic
    @Synchronized
    fun reset() {
        BatteryAdapter.active!!.clear()
        BatteryAdapter.drawing.remove()
        BatteryAdapter.type = null
        BatteryAdapter.validated = false
        BatteryAdapter.health = "color hooks disabled"
    }

    @JvmStatic
    fun supports(view: ImageView): Boolean {
        return BatteryAdapter.validated &&
            BatteryAdapter.type != null &&
            BatteryAdapter.type!!.isInstance(view.getDrawable())
    }

    @JvmStatic
    private fun paint(drawable: Drawable?, field: String?): Paint {
        return Reflect.getObjectField(drawable!!, field!!) as Paint
    }

    @JvmStatic /* Main-thread geometry snapshot; no pixels are captured or analyzed here. */
    fun geometry(view: ImageView): BatteryAdapter.Geometry? {
        val drawable = view.getDrawable()
        val width = drawable.getIntrinsicWidth()
        val height = drawable.getIntrinsicHeight()
        val level: Int = Reflect.callMethod(drawable, "getBatteryLevel") as Int
        val off = Reflect.getBooleanField(drawable, "notShowBatteryBackgroundSetting")
        val adjusted = Reflect.getBooleanField(drawable, "enableBaseLineAdjustment")
        val textPaint = Paint(BatteryAdapter.paint(drawable, "textPaint"))
        textPaint.setTextSize(
            Reflect.getFloatField(
                drawable,
                if (off) "fontSizeWithoutBackground"
                else if (adjusted) "fontSizeRounded" else "fontSize",
            )
        )
        val context: Context? = Reflect.getObjectField(drawable, "context") as Context?
        val value =
            NumberFormat.getInstance(
                    context!!.getResources().getConfiguration().getLocales().get(0)
                )
                .format(level.toLong())
        val bounds = Rect()
        textPaint.getTextBounds(value, 0, value.length, bounds)
        val origin: Float = Reflect.callMethod(drawable, "getTextOriginX", bounds) as Float
        val baseline: Float =
            if (adjusted) bounds.height() / 2f + height / 2f - 1
            else
                bounds.height() / 2f + height / 2f -
                    bounds.bottom -
                    (if (bounds.bottom == 0) 1 else 0)
        val left: Float = origin - (if (off) 0f else textPaint.measureText(value) / 2f)
        val percent =
            RectF(
                left + bounds.left,
                baseline + bounds.top,
                left + bounds.right,
                baseline + bounds.bottom,
            )
        val fraction: Float =
            if (level >= 96) 1f else if (level <= 4) 0f else if (level < 16) .15f else level / 100f
        val alpha = BatteryAdapter.paint(drawable, "batteryLevelBackgroundPaint")!!.getAlpha()
        return BatteryAdapter.Geometry(
            drawable,
            width,
            height,
            percent,
            fraction,
            alpha,
            off,
            Reflect.callMethod(drawable, "isRtl") as Boolean,
        )
    }

    @JvmStatic /* Worker-only: percentage contrast includes the battery fill that will be drawn beneath it. */
    fun percentageColor(
        bitmap: Bitmap,
        crop: Rect,
        area: Rect,
        geometry: BatteryAdapter.Geometry,
        body: Int,
        previous: Int,
        algorithm: String?,
        hysteresis: Double,
    ): Int {
        val glyph = geometry.percent
        val values = DoubleArray(256)
        var count: Int = 0
        for (row in 0..11) for (column in 0..15) {
            val x: Float = glyph!!.left + (column + .5f) * glyph!!.width() / 16
            val y: Float = glyph!!.top + (row + .5f) * glyph!!.height() / 12
            val sampleX =
                max(
                    0,
                    min(
                        bitmap.getWidth() - 1,
                        (((area.left + x / geometry.width * area.width() - crop.left) / 2)).toInt(),
                    ),
                )
            val sampleY =
                max(
                    0,
                    min(
                        bitmap.getHeight() - 1,
                        (((area.top + y / geometry.height * area.height() - crop.top) / 2)).toInt(),
                    ),
                )
            var pixel = bitmap.getPixel(sampleX, sampleY)
            if (!geometry.backgroundOff) {
                val filled: Boolean =
                    if (geometry.rtl) x >= geometry.width * (1 - geometry.fill)
                    else x <= geometry.width * geometry.fill
                pixel = Contrast.over(pixel, body, if (filled) 255 else geometry.backgroundAlpha)
            }
            values[count++] = Contrast.luminance(pixel)
        }
        return Contrast.choose(values.copyOf(count), previous, algorithm!!, hysteresis)
    }

    @JvmStatic
    fun apply(geometry: BatteryAdapter.Geometry, body: Int, percentage: Int) {
        val state =
            BatteryAdapter.active!!.computeIfAbsent(geometry.drawable) { drawable: Drawable? ->
                State(drawable!!)
            }
        val changed: Boolean =
            state!!.bodyColor != body ||
                state!!.percentColor != percentage ||
                state!!.backgroundOff != geometry.backgroundOff
        state!!.bodyColor = body
        state!!.percentColor = percentage
        state!!.backgroundOff = geometry.backgroundOff
        if (changed) geometry.drawable!!.invalidateSelf()
    }

    @JvmStatic
    fun restore(drawable: Drawable) {
        if (BatteryAdapter.active!!.remove(drawable) != null) drawable.invalidateSelf()
    }

    @JvmRecord
    data class Geometry(
        val drawable: Drawable?,
        val width: Int,
        val height: Int,
        val percent: RectF?,
        val fill: Float,
        val backgroundAlpha: Int,
        val backgroundOff: Boolean,
        val rtl: Boolean,
    ) {
        override fun toString(): String =
            "Geometry[drawable=$drawable, width=$width, height=$height, percent=$percent, fill=$fill, backgroundAlpha=$backgroundAlpha, backgroundOff=$backgroundOff, rtl=$rtl]"
    }

    private class State(drawable: Drawable) {
        @JvmField val drawable: Drawable
        @JvmField val text: Paint
        @JvmField val warning: Paint
        @JvmField val fill: Paint
        @JvmField val background: Paint
        @JvmField val outline: Paint
        @JvmField val mask: Paint
        @JvmField val copies: MutableMap<Paint?, Paint?> = IdentityHashMap<Paint?, Paint?>()
        @JvmField var bodyColor: Int = 0
        @JvmField var percentColor: Int = 0
        @JvmField var backgroundOff: Boolean = false

        init {
            this.drawable = drawable
            text = BatteryAdapter.paint(drawable, "textPaint")
            warning = BatteryAdapter.paint(drawable, "warningTextPaint")
            fill = BatteryAdapter.paint(drawable, "batteryLevelPaint")
            background = BatteryAdapter.paint(drawable, "batteryLevelBackgroundPaint")
            outline = BatteryAdapter.paint(drawable, "batteryOuterPaint")
            mask = BatteryAdapter.paint(drawable, "roundedRectPaint")
        }

        fun replacement(source: Paint?): Paint? {
            if (source == null) return null
            val label: Boolean = source === text || source === warning
            if (
                !label &&
                    source !== fill &&
                    source !== background &&
                    source !== outline &&
                    source !== mask
            )
                return source
            val result = copies.computeIfAbsent(source) { ignored: Paint? -> Paint() }
            result!!.set(source)
            var color: Int = if (label) percentColor else bodyColor
            if (source === background || source === outline)
                color = (color and 0xffffff) or (source.getAlpha() shl 24)
            result!!.setColor(color)
            return result
        }
    }
}
