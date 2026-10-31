package dev.ryuya.galaxybar

import android.content.res.Resources.NotFoundException
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.view.View
import android.view.ViewGroup
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Reflect
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

/*
 * Uses native Android 17 battery drawing without changing Compose state or composable arguments.
 */
object NativeBatteryView {
    private val drawing: ThreadLocal<View?> = ThreadLocal<View?>()
    @Volatile private var observation: String = "waiting for native draw"
    private var failed: Boolean = false
    @Volatile private var effectState: String = "native"
    private var drawSize: Method? = null
    private val entries: MutableMap<View?, NativeBatteryView.Entry?> =
        WeakHashMap<View?, NativeBatteryView.Entry?>()

    @JvmStatic
    fun geometry(view: View?): NativeBatteryView.Geometry? {
        val entry = NativeBatteryView.entries.get(view)
        return if (entry == null || entry.snapshot == null) null else entry!!.snapshot!!.geometry
    }

    @JvmStatic
    fun drawingArea(view: View, bounds: Rect, geometry: BatteryAdapter.Geometry): Rect {
        val sx: Float = bounds.width() / view.getWidth().toFloat()
        val sy: Float = bounds.height() / view.getHeight().toFloat()
        val width: Float = geometry.width * sx
        val height: Float = geometry.height * sy
        val result = Rect()
        RectF(
                bounds.exactCenterX() - width / 2,
                bounds.exactCenterY() - height / 2,
                bounds.exactCenterX() + width / 2,
                bounds.exactCenterY() + height / 2,
            )
            .roundOut(result)
        return result
    }

    @JvmStatic
    fun apply(view: View?, body: Int, text: Int) {
        val entry = NativeBatteryView.entries.get(view)
        if (entry == null || entry.snapshot == null || NativeBatteryView.failed) return
        if (entry.active && entry.body == body && entry.text == text) return
        entry.active = true
        entry.body = body
        entry.text = text
        NativeBatteryView.effectState =
            "adaptive=" + Integer.toHexString(body) + "/" + Integer.toHexString(text)
        val owner = entry.owner.get()
        if (owner != null) owner.invalidate()
    }

    @JvmStatic
    fun restore(view: View?) {
        val entry = NativeBatteryView.entries.get(view)
        if (entry == null || !entry.active) return
        entry.active = false
        NativeBatteryView.effectState = "native"
        // The effect belongs only to our nested RenderNode, never to a native View/RenderNode.
        if (entry.layer != null) entry!!.layer!!.setRenderEffect(null)
        val owner = entry.owner.get()
        if (owner != null) owner.invalidate()
    }

    @JvmStatic
    fun supports(view: View): Boolean {
        val patternPrefix0 =
            Build.VERSION.SDK_INT != 37 ||
                view.javaClass.getName() != "androidx.compose.ui.platform.ComposeView"
        val parent = if (!patternPrefix0) view.getParent() as? ViewGroup else null
        if (patternPrefix0 || parent == null) return false
        var nativeSibling: Boolean = false
        for (i in 0 until parent.getChildCount()) {
            val sibling = parent.getChildAt(i)
            if (
                sibling.javaClass.getName() == "com.android.systemui.battery.BatteryMeterView" &&
                    NativeBatteryView.resource(sibling) == "battery"
            )
                nativeSibling = true
        }
        return StatusBarElements.isDedicatedComposeBattery(
            Build.VERSION.SDK_INT,
            view.javaClass.getName(),
            NativeBatteryView.resource(parent)!!,
            nativeSibling,
        )
    }

    @JvmStatic
    private fun resource(view: View): String? {
        try {
            return view.getResources().getResourceEntryName(view.getId())
        } catch (ignored: NotFoundException) {
            return ""
        }
    }

    @JvmStatic
    fun installObservation(loader: ClassLoader?) {
        try {
            NativeBatteryView.installValidatedObservation(loader)
        } catch (error: Exception) {
            NativeBatteryView.failed = true
            NativeBatteryView.observation =
                "native draw observation unavailable: " + error.javaClass.getSimpleName()
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun installValidatedObservation(loader: ClassLoader?) {
        if (Build.VERSION.SDK_INT != 37) return
        val owner = Reflect.findClass("androidx.compose.ui.platform.AndroidComposeView", loader)
        val draw =
            Reflect.findClass(
                "com.android.systemui.battery.view.SamsungBatteryComposeViewKt$\$ExternalSyntheticLambda0",
                loader,
            )
        // Validate the read-only contracts before installing either callback.
        owner!!.getDeclaredMethod("dispatchDraw", Canvas::class.java)
        draw!!.getDeclaredMethod("invoke", Any::class.java)
        draw!!.getDeclaredField("f$1")
        draw!!.getDeclaredField("f$4")
        val scope = Reflect.findClass("androidx.compose.ui.graphics.drawscope.DrawScope", loader)
        for (method in scope!!.getMethods()) if (
            method.getName().startsWith("getSize-") &&
                method.getParameterCount() == 0 &&
                method.getReturnType() == Long::class.javaPrimitiveType
        )
            NativeBatteryView.drawSize = method
        if (NativeBatteryView.drawSize == null) throw NoSuchMethodException("DrawScope size")
        HookRegistry.lifecycle(
            owner,
            "dispatchDraw",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("battery-draw-previous", NativeBatteryView.drawing.get())
                    val child: View? = p.thisObject as View?
                    run {
                        val parent = child!!.getParent() as? View
                        if (parent != null && NativeBatteryView.supports(parent)) {
                            NativeBatteryView.drawing.set(parent)
                            val entry =
                                NativeBatteryView.entries.computeIfAbsent(parent) { ignored: View?
                                    ->
                                    NativeBatteryView.Entry()
                                }
                            entry!!.owner = WeakReference<View?>(child)
                            val destination: Canvas? = p!!.args!![0] as Canvas?
                            if (
                                entry!!.active &&
                                    !entry!!.recording &&
                                    !NativeBatteryView.failed &&
                                    destination!!.isHardwareAccelerated()
                            ) {
                                if (entry!!.layer == null)
                                    entry!!.layer = RenderNode("GalaxyBar native battery")
                                entry!!
                                    .layer!!
                                    .setPosition(0, 0, child!!.getWidth(), child!!.getHeight())
                                val recording = entry!!.layer!!.beginRecording()
                                entry!!.recording = true
                                p.setObjectExtra(
                                    "battery-render-session",
                                    NativeBatteryView.Session(entry, destination),
                                )
                                p!!.args!![0] = recording
                            }
                        } else NativeBatteryView.drawing.remove()
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        val session: NativeBatteryView.Session? =
                            p.getObjectExtra("battery-render-session") as NativeBatteryView.Session?
                        if (session == null) return
                        val entry = session.entry
                        try {
                            entry!!.layer!!.endRecording()
                            if (!p.hasThrowable()) {
                                try {
                                    if (entry!!.active && !NativeBatteryView.failed) {
                                        if (entry!!.palette == null)
                                            entry!!.palette = NativeBatteryPalette()
                                        entry!!
                                            .layer!!
                                            .setRenderEffect(
                                                entry!!
                                                    .palette!!
                                                    .effect(
                                                        entry!!.body,
                                                        entry!!.text,
                                                        entry!!.snapshot!!.body,
                                                        entry!!.snapshot!!.text,
                                                    )
                                            )
                                    } else entry!!.layer!!.setRenderEffect(null)
                                } catch (error: RuntimeException) {
                                    NativeBatteryView.disableEffect(
                                        "native battery effect unavailable: " +
                                            error.javaClass.getSimpleName()
                                    )
                                    entry!!.layer!!.setRenderEffect(null)
                                }
                                session.destination!!.drawRenderNode(entry!!.layer!!)
                            }
                        } finally {
                            entry!!.recording = false
                        }
                    } finally {
                        val previous: View? = p.getObjectExtra("battery-draw-previous") as View?
                        if (previous == null) NativeBatteryView.drawing.remove()
                        else NativeBatteryView.drawing.set(previous)
                    }
                }
            },
        )
        HookRegistry.lifecycle(
            draw,
            "invoke",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val view = NativeBatteryView.drawing.get()
                    if (view == null || NativeBatteryView.failed) return
                    try {
                        val layout = Reflect.getObjectField(p.thisObject!!, "f$1")
                        val paint: Paint? = Reflect.getObjectField(p.thisObject!!, "f$4") as Paint?
                        val size: Long = NativeBatteryView.drawSize!!.invoke(p!!.args!![0]) as Long
                        val context = Reflect.callMethod(p!!.args!![0]!!, "getDrawContext")
                        val composeCanvas = Reflect.callMethod(context!!, "getCanvas")
                        val canvas: Canvas? =
                            Reflect.getObjectField(composeCanvas!!, "internalCanvas") as Canvas?
                        val matrix = Matrix()
                        canvas!!.getMatrix(matrix)
                        val entry = NativeBatteryView.entries.get(view)
                        entry!!.snapshot =
                            NativeBatteryView.snapshot(
                                layout,
                                paint!!,
                                Reflect.getObjectField(p.thisObject!!, "f$3").toString(),
                            )
                        val position = IntArray(2)
                        view.getLocationOnScreen(position)
                        NativeBatteryView.observation =
                            ("view=" +
                                view.getWidth() +
                                "x" +
                                view.getHeight() +
                                " at " +
                                position[0] +
                                "," +
                                position[1] +
                                "; canvas=" +
                                java.lang.Float.intBitsToFloat((size shr 32).toInt()) +
                                "x" +
                                java.lang.Float.intBitsToFloat(size.toInt()) +
                                "; layout=" +
                                Reflect.getIntField(layout!!, "totalWidthPx") +
                                "x" +
                                Reflect.getIntField(layout!!, "totalHeightPx") +
                                "; frame=" +
                                Integer.toHexString(Reflect.getIntField(layout!!, "frameColor")) +
                                "; text=" +
                                Integer.toHexString(paint!!.getColor()) +
                                "; transform=" +
                                matrix.toShortString())
                    } catch (error: Throwable) {
                        NativeBatteryView.disableEffect(
                            "native draw observation unavailable: " +
                                error.javaClass.getSimpleName()
                        )
                    }
                }
            },
        )
    }

    @JvmStatic
    private fun disableEffect(reason: String) {
        NativeBatteryView.failed = true
        NativeBatteryView.observation = reason
        for (view in NativeBatteryView.entries.keys) NativeBatteryView.restore(view)
    }

    @JvmStatic
    private fun snapshot(layout: Any?, paint: Paint, value: String): NativeBatteryView.Snapshot? {
        val width = Reflect.getIntField(layout!!, "totalWidthPx")
        val height = Reflect.getIntField(layout!!, "totalHeightPx")
        val glyph = Rect()
        paint.getTextBounds(value, 0, value.length, glyph)
        var x = Reflect.getIntField(layout!!, "textOffsetX").toFloat()
        if (paint.getTextAlign() == Align.CENTER) x -= paint.measureText(value) / 2
        else if (paint.getTextAlign() == Align.RIGHT) x -= paint.measureText(value)
        val y = Reflect.getFloatField(layout!!, "textBaselineY")
        val level = Reflect.getIntField(layout!!, "displayLevel")
        val fill: Float =
            if (Reflect.getBooleanField(layout!!, "isFixedPercent")) .15f
            else if (level >= 96) 1f else if (level <= 4) 0f else level / 100f
        val geometry =
            BatteryAdapter.Geometry(
                null,
                width,
                height,
                RectF(x + glyph.left, y + glyph.top, x + glyph.right, y + glyph.bottom),
                fill,
                ((Reflect.getIntField(layout!!, "frameColor") ushr 24) *
                    (Reflect.getIntField(layout!!, "backgroundColor") ushr 24) + 127) / 255,
                !Reflect.getBooleanField(layout!!, "shouldShowBackground"),
                Reflect.getBooleanField(layout!!, "isRtl"),
            )
        return NativeBatteryView.Snapshot(
            NativeBatteryView.Geometry(
                geometry,
                Reflect.getIntField(layout!!, "gaugeColor"),
                Reflect.getIntField(layout!!, "backgroundColor"),
            ),
            Reflect.getIntField(layout!!, "frameColor"),
            paint.getColor(),
        )
    }

    @JvmStatic
    fun percentageColor(
        bitmap: Bitmap,
        crop: Rect,
        area: Rect,
        geometry: NativeBatteryView.Geometry,
        body: Int,
        previous: Int,
        algorithm: String?,
        hysteresis: Double,
    ): Int {
        val shape = geometry.shape
        val samples = DoubleArray(192)
        var count: Int = 0
        for (row in 0..11) for (column in 0..15) {
            val x: Float = shape!!.percent!!.left + (column + .5f) * shape!!.percent!!.width() / 16
            val y: Float = shape!!.percent!!.top + (row + .5f) * shape!!.percent!!.height() / 12
            val sx =
                max(
                    0,
                    min(
                        bitmap.getWidth() - 1,
                        (((area.left + x / shape!!.width * area.width() - crop.left) / 2)).toInt(),
                    ),
                )
            val sy =
                max(
                    0,
                    min(
                        bitmap.getHeight() - 1,
                        (((area.top + y / shape!!.height * area.height() - crop.top) / 2)).toInt(),
                    ),
                )
            var pixel = bitmap.getPixel(sx, sy)
            if (!shape!!.backgroundOff) {
                val filled: Boolean =
                    if (shape!!.rtl) x >= shape!!.width * (1 - shape!!.fill)
                    else x <= shape!!.width * shape!!.fill
                val source: Int = if (filled) geometry.gauge else geometry.background
                val overlay: Int = if (NativeBatteryView.isNeutral(source)) body else source
                pixel =
                    Contrast.over(
                        pixel,
                        overlay,
                        if (filled) source ushr 24 else shape!!.backgroundAlpha,
                    )
            }
            samples[count++] = Contrast.luminance(pixel)
        }
        return Contrast.choose(samples, previous, algorithm!!, hysteresis)
    }

    @JvmStatic
    private fun isNeutral(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return max(max(r, g), b) - min(min(r, g), b) <= 17
    }

    @JvmStatic
    fun health(): String? {
        return if (Build.VERSION.SDK_INT == 37)
            "\nNative battery: " +
                NativeBatteryView.observation +
                "; " +
                NativeBatteryView.effectState
        else ""
    }

    private class Entry {
        @JvmField var owner: WeakReference<View?> = WeakReference<View?>(null)
        @JvmField var snapshot: NativeBatteryView.Snapshot? = null
        @JvmField var palette: NativeBatteryPalette? = null
        @JvmField var layer: RenderNode? = null
        @JvmField var active: Boolean = false
        @JvmField var recording: Boolean = false
        @JvmField var body: Int = 0
        @JvmField var text: Int = 0
    }

    @JvmRecord
    data class Geometry(val shape: BatteryAdapter.Geometry?, val gauge: Int, val background: Int) {
        override fun toString(): String =
            "Geometry[shape=$shape, gauge=$gauge, background=$background]"
    }

    @JvmRecord
    private data class Snapshot(
        val geometry: NativeBatteryView.Geometry?,
        val body: Int,
        val text: Int,
    ) {
        override fun toString(): String = "Snapshot[geometry=$geometry, body=$body, text=$text]"
    }

    @JvmRecord
    private data class Session(val entry: NativeBatteryView.Entry?, val destination: Canvas?) {
        override fun toString(): String = "Session[entry=$entry, destination=$destination]"
    }
}
