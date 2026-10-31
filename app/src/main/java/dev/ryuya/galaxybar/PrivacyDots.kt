package dev.ryuya.galaxybar

import android.content.res.Resources.NotFoundException
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import android.widget.ImageView
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min

/** Enlarges only a settled round dot. Samsung keeps ownership of every View scale. */
object PrivacyDots {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val corners = ArrayList<WeakReference<View>>()
    @Volatile private var controller = WeakReference<Any>(null)
    @Volatile private var installed = false
    @Volatile private var enabled = false
    @Volatile private var permitted = false
    @Volatile private var reservedAlpha = 0f
    @Volatile private var active = false
    @Volatile private var displayedFactor = 1f
    private val pending = AtomicReference<DotSnapshot?>()
    private val posted = AtomicBoolean()
    // Each hierarchy is touched only by its own UI thread (ScreenDecorations on One UI 9).
    private val presentation = ThreadLocal.withInitial { Presentation() }
    private val observer = OnPreDrawListener {
        presentation.get().update()
        true
    }

    private data class DotSnapshot(val left: Float, val alpha: Float, val round: Boolean)

    fun status(): String =
        if (!installed) "unavailable"
        else if (!enabled) "off" else "ready; active=$active; drawScale=$displayedFactor"

    fun reservationAlpha() = reservedAlpha

    fun attach(root: View?) {
        // The main status-bar root and ScreenDecorations have different loopers. Never
        // install the dot observer on the status-bar root or invalidate it from this callback.
        refresh()
    }

    fun refresh() {
        val views =
            synchronized(lock) {
                corners.removeAll { it.get() == null }
                corners.mapNotNull { it.get() }
            }
        for (view in views) dispatch(view)
    }

    fun configure(value: Boolean) {
        enabled = value && installed
        if (!enabled) {
            permitted = false
            reservedAlpha = 0f
            onMain { PrivacySlide.releaseDot() }
        }
        refresh()
    }

    fun reset() {
        configure(false)
        installed = false
        synchronized(lock) { corners.clear() }
        controller.clear()
    }

    fun install(loader: ClassLoader?) {
        if (Build.VERSION.SDK_INT != 37 || installed) return
        try {
            val owner =
                Reflect.findClass(
                    "com.android.systemui.statusbar.events.PrivacyDotViewControllerImpl",
                    loader,
                )
            val state = Reflect.findClass("com.android.systemui.statusbar.events.ViewState", loader)
            owner.getDeclaredMethod(
                "initialize",
                View::class.java,
                View::class.java,
                View::class.java,
                View::class.java,
            )
            owner.getDeclaredMethod("showDotView", View::class.java)
            owner.getDeclaredField("currentViewState")
            owner.getDeclaredField("displayId")
            state.getDeclaredField("systemPrivacyEventLocationOnlyIsActive")
            state.getDeclaredField("designatedCorner")
            ImageView::class.java.getDeclaredMethod("onDraw", Canvas::class.java)
            val remember =
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        remember(p.thisObject ?: return, p.args)
                    }
                }
            HookRegistry.hook(
                owner,
                "initialize",
                View::class.java,
                View::class.java,
                View::class.java,
                View::class.java,
                remember,
            )
            HookRegistry.hook(owner, "showDotView", View::class.java, remember)
            HookRegistry.hook(
                ImageView::class.java,
                "onDraw",
                Canvas::class.java,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val visual = presentation.get().visual ?: return
                        if (!enabled || visual.dot !== p.thisObject || visual.factor <= 1.001f)
                            return
                        val canvas = p.args[0] as? Canvas ?: return
                        val save = canvas.save()
                        p.setObjectExtra("privacy-dot-canvas", save)
                        canvas.scale(
                            visual.factor,
                            visual.factor,
                            visual.dot.width / 2f,
                            visual.dot.height / 2f,
                        )
                    }

                    override fun afterHookedMethod(p: MethodHookParam) {
                        val save = p.getObjectExtra("privacy-dot-canvas") as? Int ?: return
                        (p.args[0] as Canvas).restoreToCount(save)
                    }
                },
            )
            installed = true
        } catch (error: Throwable) {
            Hooks.log("GalaxyBar: privacy dot contract unavailable: $error")
        }
    }

    private fun remember(owner: Any, views: Array<Any?>) {
        if (Reflect.getIntField(owner, "displayId") != 0) return
        controller = WeakReference(owner)
        for (view in views) {
            if (view !is View) continue
            synchronized(lock) {
                if (corners.none { it.get() === view }) corners.add(WeakReference(view))
            }
            dispatch(view)
        }
    }

    private fun dispatch(view: View) {
        // View.post also queues correctly before attachment; no ViewTreeObserver mutation
        // occurs on the caller's thread, including initialize/configure/reset.
        view.post {
            view.viewTreeObserver
                .takeIf { it.isAlive }
                ?.let {
                    it.removeOnPreDrawListener(observer)
                    if (enabled && installed) it.addOnPreDrawListener(observer)
                }
            if (enabled && installed) presentation.get().update() else presentation.get().restore()
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() === main.looper) action() else main.post { action() }
    }

    private fun publish(snapshot: DotSnapshot) {
        pending.set(snapshot)
        if (!posted.compareAndSet(false, true)) return
        main.post {
            posted.set(false)
            val latest = pending.getAndSet(null) ?: return@post
            try {
                val next =
                    enabled &&
                        latest.round &&
                        PrivacySlide.allowDot() &&
                        PrivacySlide.reserveDot(latest.left, latest.alpha)
                reservedAlpha = if (next) latest.alpha else 0f
                if (!next) PrivacySlide.releaseDot()
                if (permitted != next) {
                    permitted = next
                    refresh()
                }
            } catch (error: Throwable) {
                // This Runnable is outside the hook callback boundary. A reservation failure
                // must disable only this presentation, never escape into SystemUI's looper.
                enabled = false
                permitted = false
                reservedAlpha = 0f
                runCatching { PrivacySlide.releaseDot() }
                refresh()
                Hooks.log("GalaxyBar: privacy dot reservation stopped: $error")
            }
        }
    }

    private class Presentation {
        var visual: Visual? = null
        private val location = IntArray(2)

        fun update() {
            try {
                val owner = controller.get()
                if (!enabled || owner == null) {
                    restore()
                    return
                }
                val state = Reflect.getObjectField(owner, "currentViewState")
                val target =
                    if (state == null) null
                    else Reflect.getObjectField(state, "designatedCorner") as? View
                if (
                    state == null ||
                        Reflect.getBooleanField(state, "systemPrivacyEventLocationOnlyIsActive") ||
                        target == null ||
                        !target.isShown
                ) {
                    restore()
                    return
                }
                // A callback from a different root must not inspect this hierarchy on the
                // wrong looper. Native controllers can replace their windows during rotation.
                if (target.handler?.looper !== Looper.myLooper()) return
                val dot =
                    findDot(target)
                        ?: run {
                            restore()
                            return
                        }
                val wanted =
                    PrivacyDotScale.target(
                        min(dot.width, dot.height).toFloat(),
                        dot.resources.displayMetrics.density,
                        dot.scaleX,
                        dot.scaleY,
                    )
                val round = wanted > 1f
                if (visual?.dot !== dot) {
                    visual?.close()
                    visual = null
                }
                if (permitted && round) {
                    if (visual == null) visual = Visual(dot)
                    visual!!.update(wanted)
                } else {
                    visual?.close()
                    visual = null
                }
                active = visual != null
                displayedFactor = visual?.factor ?: 1f
                dot.getLocationOnScreen(location)
                val extra = dot.width * (displayedFactor - 1f) / 2f
                publish(
                    DotSnapshot(location[0] - extra, PrivacySlideGeometry.unit(target.alpha), round)
                )
            } catch (error: Throwable) {
                // This callback is already on the owner's looper: restore here, then notify
                // the main-thread reservation. A restore cannot fail on a foreign hierarchy.
                visual?.close()
                visual = null
                active = false
                displayedFactor = 1f
                enabled = false
                publish(DotSnapshot(0f, 0f, false))
                Hooks.log("GalaxyBar: privacy dot presentation stopped: $error")
            }
        }

        fun restore() {
            visual?.close()
            visual = null
            active = false
            displayedFactor = 1f
            publish(DotSnapshot(0f, 0f, false))
        }
    }

    private class Visual(val dot: View) : AutoCloseable {
        // Clip leases are local to the owner UI thread; the actual dot animation properties
        // are never read as a baseline or modified by this presentation.
        private val clipping = IconClipping.acquire(dot, dot.rootView)
        var factor = 1f
            private set

        private var lastTime = SystemClock.uptimeMillis()

        fun update(target: Float) {
            val now = SystemClock.uptimeMillis()
            factor = PrivacyDotScale.step(factor, target, (now - lastTime) / 1000f)
            lastTime = now
            if (abs(factor - target) > .001f) dot.postInvalidateOnAnimation()
        }

        override fun close() {
            factor = 1f
            clipping.close()
            dot.invalidate()
        }
    }

    private fun findDot(view: View): View? {
        try {
            if (view.resources.getResourceEntryName(view.id) == "privacy_dot") return view
        } catch (_: NotFoundException) {}
        if (view is ViewGroup)
            for (i in 0 until view.childCount) findDot(view.getChildAt(i))?.let {
                return it
            }
        return null
    }
}
