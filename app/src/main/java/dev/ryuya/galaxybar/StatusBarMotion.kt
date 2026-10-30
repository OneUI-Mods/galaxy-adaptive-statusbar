package dev.ryuya.galaxybar

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Path.Direction
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageView
import android.widget.TextView
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.util.ArrayList
import java.util.List
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BinaryOperator
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log
import kotlin.math.round

/* SystemUI owns both the real bar and a short-lived, non-interactive rendering of that bar. */
class StatusBarMotion(root: View, main: Handler, motionChanged: Consumer<Boolean?>) {
    private val root: View
    private val main: Handler
    private val motionChanged: Consumer<Boolean?>
    private val launcherUid: Int
    private val pending: AtomicReference<MotionFrame?> = AtomicReference<MotionFrame?>()
    private val posted: AtomicBoolean = AtomicBoolean()
    private val drain: Runnable = Runnable { this.drain() }
    private val watchdog: Runnable = Runnable { finish("timeout") }
    private val track: MotionTrack = MotionTrack()
    private val terminal: Runnable = Runnable { this.complete() }
    private val context: Context
    private val windows: WindowManager
    private var overlay: StatusBarMotion.Overlay? = null

    @JvmRecord
    private data class Leaf(
        val view: View?,
        val transform: Matrix?,
        val bounds: Rect?,
        val clip: Rect?,
    ) {
        override fun toString(): String =
            "Leaf[view=$view, transform=$transform, bounds=$bounds, clip=$clip]"
    }

    private var leaves: MutableList<StatusBarMotion.Leaf?>? =
        java.util.List.of<StatusBarMotion.Leaf?>()
    private val origin: IntArray = IntArray(2)
    private var timing: MotionTiming? = null
    private var opacityScheduled: Boolean = false
    @Volatile private var colorSource: MotionPalette.Source? = MotionPalette.Source.SCREEN
    private val opacityFrame: FrameCallback = FrameCallback { frameTime: Long ->
        this.animateOpacity(frameTime)
    }
    private var originalAlpha: Float = 0f
    @Volatile private var active: Boolean = false
    private var enabled: Boolean = false
    private var closed: Boolean = false
    private var failed: Boolean = false
    private var updates: Long = 0
    private var handoffs: Long = 0
    private var completed: Long = 0
    @Volatile private var draws: Long = 0
    @Volatile private var colorSpans: Int = 0
    @Volatile private var textPalette: Int = 0
    private var reason: String = "waiting for One UI Home"
    private val endpoint: IBinder =
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return false
                if (Binder.getCallingUid() != launcherUid)
                    throw SecurityException("One UI Home required")
                val frame = MotionFrame.read(data!!)
                pending.accumulateAndGet(
                    frame,
                    BinaryOperator { a: MotionFrame?, b: MotionFrame -> MotionTrack.newer(a, b) },
                )
                if (posted.compareAndSet(false, true)) main.post(drain)
                return true
            }
        }

    init {
        this.root = root
        this.main = main
        this.motionChanged = motionChanged
        val windowType =
            Reflect.getStaticIntField(LayoutParams::class.java, "TYPE_STATUS_BAR_SUB_PANEL")
        context = root.getContext().createWindowContext(root.getDisplay(), windowType, null)
        launcherUid = context.getPackageManager().getPackageUid("com.sec.android.app.launcher", 0)
        windows = context.getSystemService<WindowManager?>(WindowManager::class.java)
    }

    fun registration(): Bundle {
        val bundle = Bundle()
        bundle.putBinder("motion_endpoint", endpoint)
        return bundle
    }

    fun active(): Boolean {
        return active
    }

    fun configure(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        failed = false
        if (!enabled) cancel("disabled") else reason = "waiting for One UI Home"
    }

    private fun complete() {
        if (!track.endDue(SystemClock.uptimeMillis())) return
        if (timing != null && timing!!.running(SystemClock.uptimeMillis())) {
            main.postDelayed(terminal, 16)
        } else finish("finished")
    }

    private fun drain() {
        val frame = pending.getAndSet(null)
        posted.set(false)
        if (closed || frame == null) return
        try {
            update(frame)
        } catch (error: Throwable) {
            finish("unavailable")
            failed = true
            Hooks.log("GalaxyBar: motion disabled: " + error)
            HookEntry.stopHooks(error)
        }
    }

    private fun update(frame: MotionFrame) {
        val now = SystemClock.uptimeMillis()
        val width = root.getResources().getDisplayMetrics().widthPixels
        val height = root.getResources().getDisplayMetrics().heightPixels
        val change = track.accept(frame, width, height, now)
        if (change == MotionTrack.Change.IGNORE) return
        if (change == MotionTrack.Change.END) {
            main.removeCallbacks(watchdog)
            main.removeCallbacks(terminal)
            main.postDelayed(terminal, MotionTrack.END_GRACE_MS.toLong())
            return
        }
        if (
            !enabled ||
                failed ||
                !ValueAnimator.areAnimatorsEnabled() ||
                !context
                    .getSystemService<PowerManager?>(PowerManager::class.java)
                    .isInteractive() ||
                context
                    .getSystemService<KeyguardManager?>(KeyguardManager::class.java)
                    .isKeyguardLocked() ||
                !root.isAttachedToWindow() ||
                (!root.isShown() && leaves!!.isEmpty()) ||
                root.getDisplay() == null ||
                root.getDisplay().getDisplayId() != 0 ||
                root.getDisplay().getRotation() != Surface.ROTATION_0
        ) {
            cancel("paused")
            return
        }
        main.removeCallbacks(terminal)
        if (!active || change == MotionTrack.Change.START) {
            // A replacement for the same task preserves the drawing and fade. A different
            // task must get fresh view placements, even if its animation overlaps in time.
            if (active) releaseDrawing()
            originalAlpha = root.getAlpha()
            timing = MotionTiming(ValueAnimator.getDurationScale())
            active = true
            colorSource = MotionPalette.Source.FROZEN
            motionChanged.accept(true)
            if (leaves!!.isEmpty()) collectLeaves()
            if (leaves!!.isEmpty()) {
                cancel("no visible status bar content")
                return
            }
            Hooks.log("GalaxyBar: motion begin " + (if (frame.opening) "open" else "close"))
        }
        updates++
        val opacity =
            timing!!.update(
                frame.opening,
                frame.width / width,
                frame.remainingMs,
                now,
                frame.overview,
                frame.interactive,
            )
        if (timing!!.handedOff() && overlay == null) {
            overlay = Overlay()
            val p =
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                    Reflect.getStaticIntField(
                        LayoutParams::class.java,
                        "TYPE_STATUS_BAR_SUB_PANEL",
                    ),
                    (LayoutParams.FLAG_NOT_FOCUSABLE or
                        LayoutParams.FLAG_NOT_TOUCHABLE or
                        LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        LayoutParams.FLAG_HARDWARE_ACCELERATED),
                    PixelFormat.TRANSLUCENT,
                )
            p.gravity = Gravity.TOP or Gravity.LEFT
            p.setFitInsetsTypes(0)
            p.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            p.setTitle("GalaxyBar transition")
            p.packageName = "com.android.systemui"
            // This is SystemUI's own visual-only surface, like its charging ripple. Give it
            // no input channel and the same trusted-overlay classification as native effects.
            Reflect.callMethod(p, "setTrustedOverlay")
            Reflect.setIntField(
                p,
                "inputFeatures",
                Reflect.getIntField(p, "inputFeatures") or
                    Reflect.getStaticIntField(
                        LayoutParams::class.java,
                        "INPUT_FEATURE_NO_INPUT_CHANNEL",
                    ),
            )
            windows.addView(overlay, p)
            handoffs++
            Hooks.log(
                "GalaxyBar: motion handoff x=" + frame.x + " y=" + frame.y + " w=" + frame.width
            )
        }
        applyOpacity(opacity!!, frame)
        scheduleOpacity()
        reason = if (frame.overview) "overview" else if (frame.opening) "opening" else "closing"
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 220)
    }

    private fun applyOpacity(opacity: MotionTiming.Opacity, frame: MotionFrame) {
        if (overlay == null) return
        overlay!!.opacity = opacity.moving * frame.alpha
        overlay!!.invalidate()
        root.setAlpha(originalAlpha * opacity.fixed)
        val next = MotionPalette.choose(timing!!.handedOff(), timing!!.openingLeg(), opacity)
        if (next != colorSource) {
            colorSource = next
            motionChanged.accept(true)
        }
    }

    private fun scheduleOpacity() {
        if (
            !opacityScheduled &&
                active &&
                overlay != null &&
                timing!!.running(SystemClock.uptimeMillis())
        ) {
            opacityScheduled = true
            Choreographer.getInstance().postFrameCallback(opacityFrame)
        }
    }

    private fun animateOpacity(frameTime: Long) {
        opacityScheduled = false
        if (!active || overlay == null || timing == null || track.current() == null) return
        try {
            applyOpacity(timing!!.opacity(SystemClock.uptimeMillis())!!, track.current()!!)
            scheduleOpacity()
        } catch (error: Throwable) {
            cancel("fade unavailable")
            HookEntry.stopHooks(error)
        }
    }

    fun prepare(now: Long) {
        if (!enabled || active || closed || failed || root.getAlpha() < .99f || !root.isShown())
            return
        collectLeaves()
    }

    private fun collectLeaves() {
        root.getLocationOnScreen(origin)
        val next = ArrayList<StatusBarMotion.Leaf?>()
        collectLeaf(root, next)
        leaves = List.copyOf<StatusBarMotion.Leaf?>(next)
    }

    private fun collectLeaf(view: View, result: MutableList<StatusBarMotion.Leaf?>) {
        if (
            !view.isShown() || view.getAlpha() <= 0 || view.getWidth() <= 0 || view.getHeight() <= 0
        )
            return
        val name = view.javaClass.getName()
        var resource: String = ""
        try {
            resource = view.getResources().getResourceEntryName(view.getId())
        } catch (ignored: Exception) {}
        val capsule = StatusBarElements.isCompoundIndicator(name, resource)
        if (
            capsule ||
                NativeBatteryView.supports(view) ||
                ModernTintAdapter.supports(view) ||
                view is TextView ||
                view is ImageView ||
                (view !is ViewGroup)
        ) {
            val clip = Rect()
            if (!view.getGlobalVisibleRect(clip)) return
            val transform = Matrix()
            view.transformMatrixToGlobal(transform)
            val bounds = RectF(0f, 0f, view.getWidth().toFloat(), view.getHeight().toFloat())
            transform.mapRect(bounds)
            val area = Rect()
            bounds.roundOut(area)
            transform.postTranslate(-origin[0].toFloat(), -origin[1].toFloat())
            clip.offset(-origin[0], -origin[1])
            result.add(StatusBarMotion.Leaf(view, transform, area, clip))
        } else {
            val group: ViewGroup? = view as ViewGroup?
            for (i in 0 until group!!.getChildCount()) collectLeaf(group!!.getChildAt(i), result)
        }
    }

    fun sourceViews(): MutableList<View?> {
        val result = ArrayList<View?>(leaves!!.size)
        for (leaf in leaves!!) if (leaf!!.view!!.isAttachedToWindow()) result.add(leaf!!.view)
        return result
    }

    fun nativeArea(view: View?): Rect? {
        for (leaf in leaves!!) if (leaf!!.view === view) return Rect(leaf!!.bounds)
        return null
    }

    fun mappedArea(area: Rect): Rect? {
        val f = track.current()
        if (!active || f == null) return Rect(area)
        val bounds =
            MotionGeometry.map(
                f,
                root.getWidth().toFloat(),
                origin[0].toFloat(),
                origin[1].toFloat(),
                area.left.toFloat(),
                area.top.toFloat(),
                area.right.toFloat(),
                area.bottom.toFloat(),
            )
        return Rect(
            (floor(bounds!!.left)).toInt(),
            (floor(bounds!!.top)).toInt(),
            (ceil(bounds!!.right)).toInt(),
            (ceil(bounds!!.bottom)).toInt(),
        )
    }

    fun colorSource(): MotionPalette.Source? {
        return colorSource
    }

    fun sampleCrop(): Rect? {
        if (!active || track.current() == null) return null
        if (colorSource == MotionPalette.Source.SCREEN) {
            return Rect(
                origin[0],
                origin[1],
                origin[0] + root.getWidth(),
                origin[1] + root.getHeight(),
            )
        }
        val crop =
            mappedArea(
                Rect(
                    origin[0],
                    origin[1],
                    origin[0] + root.getWidth(),
                    origin[1] + root.getHeight(),
                )
            )
        val f = track.current()
        val window =
            Rect(
                (floor(f!!.x)).toInt(),
                (floor(f!!.y)).toInt(),
                (ceil(f!!.x + f!!.width)).toInt(),
                (ceil(f!!.y + f!!.height)).toInt(),
            )
        if (!crop!!.intersect(window)) return null
        if (
            !crop!!.intersect(
                0,
                0,
                root.getResources().getDisplayMetrics().widthPixels,
                root.getResources().getDisplayMetrics().heightPixels,
            )
        )
            return null
        return crop
    }

    fun overlaySurface(): SurfaceControl? {
        if (overlay == null) return null
        val viewRoot = Reflect.callMethod(overlay!!, "getViewRootImpl")
        if (viewRoot == null) return null
        return Reflect.callMethod(viewRoot, "getSurfaceControl") as SurfaceControl?
    }

    fun waitingForSurface(): Boolean {
        if (overlay == null) return false
        val surface = overlaySurface()
        return surface == null || !surface.isValid()
    }

    fun colorsChanged() {
        if (overlay != null) overlay!!.invalidate()
    }

    fun colorState(spans: Int, palette: Int) {
        colorSpans = spans
        textPalette = palette
    }

    fun cancel(reason: String) {
        val waiting = pending.getAndSet(null)
        if (waiting != null)
            track.accept(
                waiting,
                root.getResources().getDisplayMetrics().widthPixels,
                root.getResources().getDisplayMetrics().heightPixels,
                SystemClock.uptimeMillis(),
            )
        finish(reason)
        leaves = java.util.List.of<StatusBarMotion.Leaf?>()
    }

    private fun finish(reason: String) {
        if (active)
            Hooks.log(
                ("GalaxyBar: motion restore " +
                    reason +
                    " overview=" +
                    (track.current() != null && track.current()!!.overview))
            )
        main.removeCallbacks(watchdog)
        main.removeCallbacks(terminal)
        track.clear()
        releaseDrawing()
        this.reason = reason
    }

    private fun releaseDrawing() {
        Choreographer.getInstance().removeFrameCallback(opacityFrame)
        opacityScheduled = false
        val changed: Boolean = active
        if (active) {
            root.setAlpha(originalAlpha)
            completed++
        }
        active = false
        colorSource = MotionPalette.Source.SCREEN
        if (overlay != null) {
            try {
                windows.removeViewImmediate(overlay)
            } catch (error: Throwable) {
                Hooks.log("GalaxyBar: motion cleanup: " + error)
            } finally {
                overlay = null
            }
        }
        // The final native bar shows the same app/home background. Reject old geometry work,
        // but retain the current color interpolation and contrast history across this handoff.
        if (changed) motionChanged.accept(false)
    }

    fun status(): String? {
        return ("Motion: " +
            reason +
            "; frames=" +
            updates +
            "; handoffs=" +
            handoffs +
            "; restored=" +
            completed +
            "; live draws=" +
            draws +
            "; opacity callbacks=" +
            (if (opacityScheduled) 1 else 0) +
            "; moving opacity=" +
            (if (overlay == null) 0f else overlay!!.opacity) +
            "; palette=" +
            colorSource +
            "; color spans=" +
            colorSpans +
            "; text palette=" +
            textPalette)
    }

    fun close() {
        closed = true
        cancel("closed")
        main.removeCallbacks(drain)
    }

    private inner class Overlay : View(context) {
        @JvmField var opacity: Float = 0f
        private val clip: Path = Path()

        init {
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        }

        override fun onDraw(canvas: Canvas) {
            try {
                drawBar(canvas)
            } catch (error: Throwable) {
                failed = true
                main.post(
                    Runnable {
                        cancel("drawing unavailable")
                        HookEntry.stopHooks(error)
                    }
                )
                Hooks.log("GalaxyBar: motion draw: " + error)
            }
        }

        fun drawBar(canvas: Canvas) {
            check(canvas.isHardwareAccelerated()) { "Hardware canvas required" }
            if (leaves!!.isEmpty() || track.current() == null || opacity <= 0) return
            draws++
            val f = track.current()
            val scale: Float = f!!.width / root.getWidth()
            val save = canvas.save()
            clip.reset()
            clip.addRoundRect(
                f!!.x,
                f!!.y,
                f!!.x + f!!.width,
                f!!.y + f!!.height,
                f!!.radius,
                f!!.radius,
                Direction.CW,
            )
            canvas.clipPath(clip)
            canvas.translate(f!!.x, f!!.y)
            canvas.scale(scale, scale)
            val layer =
                canvas.saveLayerAlpha(
                    0f,
                    0f,
                    root.getWidth().toFloat(),
                    root.getHeight().toFloat(),
                    Math.round(255 * opacity),
                )
            // Draw the live source content directly. Parent visibility/alpha belongs to the fixed
            // bar; glyph spans, icon tint, battery paint, and their ongoing fades remain live.
            for (leaf in leaves!!) {
                if (!leaf!!.view!!.isAttachedToWindow()) continue
                val child = canvas.save()
                canvas.clipRect(leaf!!.clip!!)
                canvas.concat(leaf!!.transform)
                leaf!!.view!!.draw(canvas)
                canvas.restoreToCount(child)
            }
            canvas.restoreToCount(layer)
            canvas.restoreToCount(save)
        }
    }
}
