package dev.ryuya.galaxybar

import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

/** Keeps the native charging VI, including its battery crossfade and spring animation. */
internal object ChargingMotion {
    private const val BASE = "com.android.systemui.statusbar."
    private val main = Handler(Looper.getMainLooper())
    private var root = WeakReference<View>(null)
    private var installed = false
    private var enabled = false
    private var session: Session? = null
    private var meter: Method? = null
    private var background: Field? = null
    private var translatedView: Field? = null
    private var state = "off"
    private var completed = 0
    private var maxShift = 0f

    fun attach(view: View) {
        if (root.get() !== view) {
            cancel()
            root = WeakReference(view)
        }
    }

    fun configure(value: Boolean) {
        enabled = value && installed
        if (!enabled) cancel()
        state =
            if (enabled) {
                if (session != null) "following native battery" else "ready"
            } else "off"
    }

    fun isEnabled() = enabled

    fun status() = "$state; completed=$completed; maxShift=$maxShift"

    fun install(loader: ClassLoader?) {
        if (Build.VERSION.SDK_INT != 37 || installed) return
        try {
            val visibility =
                Reflect.findClass(
                    BASE +
                        "pipeline.shared.ui.viewmodel.HomeStatusBarViewModelImpl\$shouldHomeStatusBarBeVisible\$1",
                    loader,
                )
            val chip = Reflect.findClass(BASE + "events.SamsungBatteryStatusChip", loader)
            val update =
                Reflect.findClass(
                    BASE +
                        "events.SamsungBatteryStatusChip\$getSystemIconAnimator\$translationXAnimator\$1\$1",
                    loader,
                )
            val animator = Reflect.findClass("androidx.core.animation.Animator", loader)
            val args = Array<Class<*>>(6) { Any::class.java }
            visibility.getDeclaredMethod("invoke", *args)
            val viewModel = visibility.getDeclaredField("this\$0").apply { isAccessible = true }
            val displayId =
                viewModel.type.getDeclaredField("thisDisplayId").apply { isAccessible = true }
            check(displayId.type == Int::class.javaPrimitiveType)
            chip.getDeclaredMethod(
                "getSystemIconAnimator",
                View::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            chip.getDeclaredMethod("resetSystemIconState")
            meter = chip.getDeclaredMethod("getBatteryMeterView")
            background = chip.getDeclaredField("background")
            translatedView = update.getDeclaredField("\$v")
            update.getDeclaredMethod("onAnimationUpdate", animator)
            check(View::class.java.isAssignableFrom(meter!!.returnType))
            check(View::class.java.isAssignableFrom(background!!.type))
            check(translatedView!!.type == View::class.java)
            background!!.isAccessible = true
            translatedView!!.isAccessible = true

            HookRegistry.hook(
                visibility,
                "invoke",
                *args,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        // Only remove the charging-specific reason. Fullscreen, keyguard, disable
                        // flags and Samsung's other visibility requests still run through native
                        // code.
                        if (
                            enabled &&
                                root.get()?.isAttachedToWindow == true &&
                                root.get()?.layoutDirection == View.LAYOUT_DIRECTION_LTR &&
                                displayId.getInt(viewModel.get(p.thisObject)) == 0 &&
                                p.args[4] == true
                        ) {
                            p.args[4] = false
                        }
                    }
                },
            )
            HookRegistry.hook(
                chip,
                "getSystemIconAnimator",
                View::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (!enabled) return
                        try {
                            val icons = p.args[0] as? View ?: return
                            val owner = p.thisObject as? View ?: return
                            if (session?.chip === owner && session?.icons === icons) return
                            val battery = meter!!.invoke(owner) as? View ?: return
                            val growing = background!!.get(owner) as? View ?: return
                            if (
                                icons.display?.displayId != 0 ||
                                    icons.layoutDirection != View.LAYOUT_DIRECTION_LTR
                            )
                                return
                            cancel()
                            val next = Session(owner, icons, battery, growing)
                            session = next
                            next.start()
                            state = "following native battery"
                        } catch (error: Throwable) {
                            fail(error)
                        }
                    }
                },
            )
            HookRegistry.hook(
                update,
                "onAnimationUpdate",
                animator,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val active = session ?: return
                        if (enabled && translatedView!!.get(p.thisObject) === active.icons) {
                            // Replace only Samsung's fixed-distance icon translation. Its animator
                            // still runs to completion, and its separate battery alpha is
                            // untouched.
                            p.setResult(null)
                        }
                    }
                },
            )
            HookRegistry.hook(
                chip,
                "resetSystemIconState",
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (session?.chip === p.thisObject) cancel()
                    }
                },
            )
            installed = true
            state = "off"
            Hooks.log("GalaxyBar: native charging motion ready")
        } catch (error: Throwable) {
            state = "unavailable"
            Hooks.log("GalaxyBar: charging motion unavailable: $error")
        }
    }

    fun cancel() {
        session?.close()
        session = null
        state = if (enabled) "ready" else "off"
    }

    fun reset() {
        enabled = false
        cancel()
        installed = false
    }

    private fun fail(error: Throwable) {
        enabled = false
        cancel()
        state = "unavailable"
        Hooks.log("GalaxyBar: charging motion stopped: $error")
    }

    private class Session(val chip: View, val icons: View, val battery: View, val growing: View) :
        OnPreDrawListener, OnAttachStateChangeListener {
        private val originalX = icons.translationX
        private var appliedX = originalX
        private val clipping = IconClipping.acquire(icons, root.get())
        private val companions = ArrayList<CompanionView>()
        private val matrix = Matrix()
        private val area = RectF()
        private val position = IntArray(2)
        private val gap = 4f * icons.resources.displayMetrics.density
        private var closed = false
        private val timeout = Runnable { if (session === this) cancel() }

        fun start() {
            collectCompanions(root.get())
            chip.viewTreeObserver.addOnPreDrawListener(this)
            chip.addOnAttachStateChangeListener(this)
            main.postDelayed(timeout, 15000)
        }

        override fun onPreDraw(): Boolean {
            if (closed) return true
            if (!enabled || !chip.isAttachedToWindow || !chip.isShown) {
                if (session === this) cancel()
                return true
            }
            try {
                if (growing.width <= 0 || icons.width <= 0) return true
                matrix.reset()
                growing.transformMatrixToGlobal(matrix)
                area.set(0f, 0f, growing.width.toFloat(), growing.height.toFloat())
                matrix.mapRect(area)
                icons.getLocationOnScreen(position)
                // Remove only our local delta. Parent movement (including the privacy chip)
                // remains in screen space, so simultaneous reservations do not add twice.
                val baselineRight = position[0] + icons.width - (appliedX - originalX)
                battery.getLocationOnScreen(position)
                val shift =
                    ChargingMotionGeometry.shift(
                        baselineRight,
                        position[0].toFloat(),
                        area.left,
                        gap,
                        growing.alpha * chip.alpha,
                    )
                val next = originalX + shift
                if (abs(next - appliedX) > 0.01f) {
                    appliedX = next
                    icons.translationX = next
                    for (companion in companions) companion.move(shift)
                }
                maxShift = maxOf(maxShift, -shift)
            } catch (error: Throwable) {
                fail(error)
            }
            return true
        }

        private fun collectCompanions(view: View?) {
            if (view == null || view === icons) return
            if (
                view.javaClass.name == "com.android.systemui.statusbar.policy.NetspeedView" &&
                    view.isShown
            ) {
                val at = IntArray(2)
                view.getLocationOnScreen(at)
                battery.getLocationOnScreen(position)
                if (abs(at[1] - position[1]) < battery.height) companions.add(CompanionView(view))
                return
            }
            if (view is ViewGroup)
                for (i in 0 until view.childCount) collectCompanions(view.getChildAt(i))
        }

        private class CompanionView(val view: View) : AutoCloseable {
            private val original = view.translationX
            private var applied = original
            private val clipping = IconClipping.acquire(view, root.get())

            fun move(shift: Float) {
                applied = original + shift
                view.translationX = applied
            }

            override fun close() {
                if (abs(view.translationX - applied) < 0.01f) view.translationX = original
                clipping.close()
            }
        }

        override fun onViewAttachedToWindow(v: View) {}

        override fun onViewDetachedFromWindow(v: View) {
            if (session === this) cancel()
        }

        fun close() {
            if (closed) return
            closed = true
            main.removeCallbacks(timeout)
            if (chip.viewTreeObserver.isAlive) chip.viewTreeObserver.removeOnPreDrawListener(this)
            chip.removeOnAttachStateChangeListener(this)
            if (abs(icons.translationX - appliedX) < 0.01f) icons.translationX = originalX
            for (companion in companions) companion.close()
            companions.clear()
            clipping.close()
            completed++
        }
    }
}
