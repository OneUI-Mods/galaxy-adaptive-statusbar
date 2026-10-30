package dev.ryuya.galaxybar

import android.content.res.Resources.NotFoundException
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.lang.ref.WeakReference
import java.util.ArrayList
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.log
import kotlin.math.min

/* Enlarges the original dot without changing its privacy state, color, or visibility. */
class PrivacyDots : OnPreDrawListener {
    override fun onPreDraw(): Boolean {
        try {
            val owner = PrivacyDots.controller.get()
            if (!PrivacyDots.enabled || owner == null || !PrivacySlide.allowDot()) {
                PrivacyDots.restore()
                return true
            }
            val state = Reflect.getObjectField(owner, "currentViewState")
            if (
                state == null ||
                    Reflect.getBooleanField(state, "systemPrivacyEventLocationOnlyIsActive")
            ) {
                PrivacyDots.restore()
                return true
            }
            val target = Reflect.getObjectField(state, "designatedCorner")
            if (target !is View || !target.isShown()) {
                PrivacyDots.restore()
                return true
            }
            val dot = PrivacyDots.findDot(target)
            if (dot == null || dot.getWidth() <= 0 || dot.getHeight() <= 0) {
                PrivacyDots.restore()
                return true
            }
            var visual: PrivacyDots.Visual? = null
            for (item in PrivacyDots.visuals) if (item!!.dot === dot) visual = item
            if (visual == null) {
                PrivacyDots.restore()
                visual = PrivacyDots.Visual(dot)
                PrivacyDots.visuals.add(visual)
            }
            visual.apply()
            val at = IntArray(2)
            // getLocationOnScreen includes the view's scale around its existing pivot.
            dot.getLocationOnScreen(at)
            if (!PrivacySlide.reserveDot(at[0].toFloat(), target.getAlpha())) PrivacyDots.restore()
        } catch (error: Throwable) {
            // A mismatch disables only this optional presentation, leaving the native dot visible.
            PrivacyDots.configure(false)
            Hooks.log("GalaxyBar: privacy dot presentation stopped: " + error)
        }
        return true
    }

    private class Visual(dot: View) {
        @JvmField val dot: View
        @JvmField val scaleX: Float
        @JvmField val scaleY: Float
        @JvmField
        val clipping: ArrayList<PrivacyDots.Clipping?> = ArrayList<PrivacyDots.Clipping?>()
        @JvmField var appliedX: Float
        @JvmField var appliedY: Float

        init {
            this.dot = dot
            appliedX = dot.getScaleX()
            scaleX = appliedX
            appliedY = dot.getScaleY()
            scaleY = appliedY
            var parent = dot.getParent()
            while (parent is ViewGroup) {
                clipping.add(
                    PrivacyDots.Clipping(
                        parent,
                        parent.getClipChildren(),
                        parent.getClipToPadding(),
                    )
                )
                parent.setClipChildren(false)
                parent.setClipToPadding(false)
                parent = parent.getParent()
            }
        }

        fun apply() {
            val factor =
                PrivacySlideGeometry.dotScale(
                    (min(dot.getWidth(), dot.getHeight())).toFloat(),
                    dot.getResources().getDisplayMetrics().density,
                )
            appliedX = scaleX * factor
            appliedY = scaleY * factor
            dot.setScaleX(appliedX)
            dot.setScaleY(appliedY)
        }

        fun close() {
            if (abs(dot.getScaleX() - appliedX) < .001f) dot.setScaleX(scaleX)
            if (abs(dot.getScaleY() - appliedY) < .001f) dot.setScaleY(scaleY)
            for (item in clipping) {
                item!!.parent!!.setClipChildren(item!!.children)
                item!!.parent!!.setClipToPadding(item!!.padding)
            }
            clipping.clear()
        }
    }

    @JvmRecord
    private data class Clipping(
        val parent: ViewGroup?,
        val children: Boolean,
        val padding: Boolean,
    ) {
        override fun toString(): String =
            "Clipping[parent=$parent, children=$children, padding=$padding]"
    }

    companion object {
        private val observer: PrivacyDots = PrivacyDots()
        private var window: WeakReference<View?> = WeakReference<View?>(null)
        private var controller: WeakReference<Any?> = WeakReference<Any?>(null)
        private val corners: ArrayList<WeakReference<View?>?> = ArrayList<WeakReference<View?>?>()
        private val visuals: ArrayList<PrivacyDots.Visual?> = ArrayList<PrivacyDots.Visual?>()
        private var installed: Boolean = false
        private var enabled: Boolean = false

        @JvmStatic
        fun status(): String? {
            return if (!PrivacyDots.installed) "unavailable"
            else if (!PrivacyDots.enabled) "off" else "ready; active=" + PrivacyDots.visuals.size
        }

        @JvmStatic
        fun refresh() {
            PrivacyDots.observer.onPreDraw()
        }

        @JvmStatic
        fun reservationAlpha(): Float {
            val owner = PrivacyDots.controller.get()
            if (!PrivacyDots.enabled || owner == null) return 0f
            val state = Reflect.getObjectField(owner, "currentViewState")
            if (state == null) return 0f
            val target = Reflect.getObjectField(state, "designatedCorner")
            if (target is View && target.isShown()) return target.getAlpha()
            return 0f
        }

        @JvmStatic
        fun install(loader: ClassLoader?) {
            if (Build.VERSION.SDK_INT != 37 || PrivacyDots.installed) return
            try {
                val owner =
                    Reflect.findClass(
                        "com.android.systemui.statusbar.events.PrivacyDotViewControllerImpl",
                        loader,
                    )
                val state =
                    Reflect.findClass("com.android.systemui.statusbar.events.ViewState", loader)
                owner!!.getDeclaredMethod(
                    "initialize",
                    View::class.java,
                    View::class.java,
                    View::class.java,
                    View::class.java,
                )
                owner!!.getDeclaredMethod("showDotView", View::class.java)
                owner!!.getDeclaredField("currentViewState")
                owner!!.getDeclaredField("displayId")
                state!!.getDeclaredField("systemPrivacyEventLocationOnlyIsActive")
                state!!.getDeclaredField("designatedCorner")
                HookRegistry.hook(
                    owner,
                    "initialize",
                    View::class.java,
                    View::class.java,
                    View::class.java,
                    View::class.java,
                    object : HookCallback() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            PrivacyDots.remember(p.thisObject, p.args!!)
                        }
                    },
                )
                HookRegistry.hook(
                    owner,
                    "showDotView",
                    View::class.java,
                    object : HookCallback() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            PrivacyDots.remember(p.thisObject, p.args!!)
                        }
                    },
                )
                PrivacyDots.installed = true
            } catch (unavailable: Throwable) {
                Hooks.log("GalaxyBar: privacy dot contract unavailable: " + unavailable)
            }
        }

        @JvmStatic
        private fun remember(owner: Any?, views: Array<Any?>) {
            // The cover display has its own controller and geometry.
            if (Reflect.getIntField(owner!!, "displayId") != 0) return
            PrivacyDots.controller = WeakReference<Any?>(owner)
            for (value in views) {
                if (value !is View) continue
                var known: Boolean = false
                for (corner in PrivacyDots.corners) if (corner!!.get() === value) known = true
                if (!known) PrivacyDots.corners.add(WeakReference<View?>(value))
                PrivacyDots.listen(value)
            }
            val root = PrivacyDots.window.get()
            if (root != null) root.invalidate()
        }

        @JvmStatic
        fun attach(root: View?) {
            val old = PrivacyDots.window.get()
            if (old !== root) {
                PrivacyDots.unlisten(old)
                PrivacyDots.restore()
                PrivacyDots.window = WeakReference<View?>(root)
            }
            PrivacyDots.listen(root)
        }

        @JvmStatic
        fun configure(value: Boolean) {
            PrivacyDots.enabled = value && PrivacyDots.installed
            if (!PrivacyDots.enabled) {
                PrivacyDots.restore()
                PrivacyDots.unlisten(PrivacyDots.window.get())
                for (corner in PrivacyDots.corners) PrivacyDots.unlisten(corner!!.get())
            } else {
                PrivacyDots.listen(PrivacyDots.window.get())
                for (corner in PrivacyDots.corners) PrivacyDots.listen(corner!!.get())
            }
        }

        @JvmStatic
        fun reset() {
            PrivacyDots.configure(false)
            PrivacyDots.corners.clear()
            PrivacyDots.controller.clear()
            PrivacyDots.installed = false
        }

        @JvmStatic
        private fun listen(view: View?) {
            if (!PrivacyDots.enabled || view == null || !view.getViewTreeObserver().isAlive())
                return
            view.getViewTreeObserver().removeOnPreDrawListener(PrivacyDots.observer)
            view.getViewTreeObserver().addOnPreDrawListener(PrivacyDots.observer)
        }

        @JvmStatic
        private fun unlisten(view: View?) {
            if (view != null && view.getViewTreeObserver().isAlive())
                view.getViewTreeObserver().removeOnPreDrawListener(PrivacyDots.observer)
        }

        @JvmStatic
        private fun findDot(view: View): View? {
            try {
                if (view.getResources().getResourceEntryName(view.getId()) == "privacy_dot")
                    return view
            } catch (ignored: NotFoundException) {}
            if (view is ViewGroup)
                for (i in 0 until view.getChildCount()) {
                    val found = PrivacyDots.findDot(view.getChildAt(i))
                    if (found != null) return found
                }
            return null
        }

        @JvmStatic
        private fun restore() {
            for (visual in PrivacyDots.visuals) visual!!.close()
            PrivacyDots.visuals.clear()
            PrivacySlide.releaseDot()
        }
    }
}
