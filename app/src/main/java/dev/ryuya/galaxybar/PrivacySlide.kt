package dev.ryuya.galaxybar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.content.res.Configuration
import android.content.res.Resources.NotFoundException
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets.Type
import android.view.animation.PathInterpolator
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.lang.Boolean as JavaBoolean
import java.lang.ref.WeakReference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.log
import kotlin.math.max

/* Optional, View-only privacy reservation. Never changes Compose slots or native flow values. */
object PrivacySlide {
    private const val BASE: String = "com.android.systemui.statusbar."
    private val main: Handler = Handler(Looper.getMainLooper())
    private val easing: PathInterpolator = PathInterpolator(.2f, 0f, 0f, 1f)
    private val exitEasing: PathInterpolator = PathInterpolator(.4f, 0f, .2f, 1f)
    private var root: WeakReference<View?> = WeakReference<View?>(null)
    private var visibility: Constructor<*>? = null
    private var applyVisibility: Method? = null
    private var installed: Boolean = false
    private var enabled: Boolean = false
    private var pending: Boolean = false
    private var replaying: Boolean = false
    private var crowdedFallback: Boolean = true
    // Before the first native emission, the pending event must originate from a fully visible
    // group.
    @Volatile private var otherSamsungHide: Boolean = false
    @Volatile private var homeAllowed: Boolean = true
    @Volatile private var infoAllowed: Boolean = true
    @Volatile private var statusBarRequested: Boolean = false
    private var nativeVisibility: Any? = null
    private var nativeTarget: View? = null
    private var session: PrivacySlide.Session? = null
    private var space: PrivacyIconSpace? = null
    private var pendingGeneration: Int = 0
    private var completed: Int = 0
    private var state: String = "off"

    @JvmStatic
    fun attach(view: View?) {
        if (PrivacySlide.root.get() !== view) {
            PrivacySlide.cancel()
            PrivacySlide.root = WeakReference<View?>(view)
        }
        PrivacyDots.attach(view)
    }

    @JvmStatic
    fun status(): String? {
        return (PrivacySlide.state +
            "; completed=" +
            PrivacySlide.completed +
            "; requested=" +
            PrivacySlide.statusBarRequested +
            "; dot=" +
            PrivacyDots.status())
    }

    @JvmStatic
    @JvmOverloads
    fun configure(value: Boolean, fallback: Boolean = PrivacySlide.crowdedFallback) {
        PrivacySlide.crowdedFallback = fallback
        if (
            PrivacySlide.enabled &&
                fallback &&
                PrivacySlide.session != null &&
                !PrivacySlide.session!!.fits()
        ) {
            PrivacySlide.session!!.close()
            PrivacySlide.restoreNative()
        }
        val next: Boolean = value && PrivacySlide.installed
        if (PrivacySlide.enabled == next) return
        PrivacySlide.enabled = next
        if (!PrivacySlide.enabled) PrivacySlide.clearReservation() else PrivacySlide.state = "ready"
        PrivacyDots.configure(PrivacySlide.enabled)
    }

    @JvmStatic
    fun install(loader: ClassLoader?) {
        if (Build.VERSION.SDK_INT != 37 || PrivacySlide.installed) return
        try {
            val scheduler =
                Reflect.findClass(
                    PrivacySlide.BASE + "events.SystemStatusAnimationSchedulerImpl",
                    loader,
                )
            val event = Reflect.findClass(PrivacySlide.BASE + "events.StatusEvent", loader)
            val controller =
                Reflect.findClass(
                    PrivacySlide.BASE + "events.SystemEventChipAnimationControllerImpl",
                    loader,
                )
            val function = Reflect.findClass("kotlin.jvm.functions.Function1", loader)
            val attributes =
                Reflect.findClass(
                    "com.android.systemui.statusbar.data.repository.StatusBarModePerDisplayRepositoryImpl\$commandQueueCallback$1",
                    loader,
                )
            val regions =
                ReflectArray.newInstance(
                        Reflect.findClass("com.android.internal.view.AppearanceRegion", loader),
                        0,
                    )
                    .javaClass
            val letterboxes =
                ReflectArray.newInstance(
                        Reflect.findClass(
                            "com.android.internal.statusbar.LetterboxDetails",
                            loader,
                        ),
                        0,
                    )
                    .javaClass
            attributes!!.getDeclaredMethod(
                "onSystemBarAttributesChanged",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                regions,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                letterboxes,
            )
            Reflect.findClass(
                    "com.android.systemui.privacy.ui.view.ComposeOngoingPrivacyChip",
                    loader,
                )!!
                .getDeclaredField("launchableView")
            val visibilityType =
                Reflect.findClass(
                    PrivacySlide.BASE + "pipeline.shared.ui.model.VisibilityModel",
                    loader,
                )
            val binder =
                Reflect.findClass(
                    PrivacySlide.BASE + "pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl",
                    loader,
                )
            val flags =
                Reflect.findClass(
                    PrivacySlide.BASE +
                        "pipeline.shared.ui.viewmodel.HomeStatusBarViewModelImpl\$isSystemInfoVisible$1",
                    loader,
                )
            val home =
                Reflect.findClass(
                    PrivacySlide.BASE +
                        "pipeline.shared.ui.viewmodel.HomeStatusBarViewModelImpl\$shouldHomeStatusBarBeVisible$1",
                    loader,
                )
            val samsung =
                Reflect.findClass(
                    PrivacySlide.BASE +
                        "pipeline.shared.domain.interactor.SamsungHomeStatusBarInteractor\$special$\$inlined\$combine$1$3",
                    loader,
                )
            // Verify every contract before registering any optional hook.
            scheduler!!.getDeclaredMethod("onStatusEvent", event)
            controller!!.getDeclaredMethod(
                "prepareChipAnimation",
                function,
                Boolean::class.javaPrimitiveType,
            )
            controller!!.getDeclaredMethod(
                "onSystemEventAnimationBegin",
                Boolean::class.javaPrimitiveType,
            )
            controller!!.getDeclaredMethod(
                "onSystemEventAnimationFinish",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            controller!!.getDeclaredField("currentAnimatedView")
            controller!!.getDeclaredField("animationWindowView")
            controller!!.getDeclaredField("animRect")
            flags!!.getDeclaredMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
            home!!.getDeclaredMethod(
                "invoke",
                Any::class.java,
                Any::class.java,
                Any::class.java,
                Any::class.java,
                Any::class.java,
                Any::class.java,
            )
            samsung!!.getDeclaredMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
            PrivacySlide.visibility =
                visibilityType!!.getDeclaredConstructor(
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            PrivacySlide.applyVisibility =
                binder!!.getDeclaredMethod("adjustVisibility", View::class.java, visibilityType)

            HookRegistry.hook(
                attributes,
                "onSystemBarAttributesChanged",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                regions,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                letterboxes,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (p!!.args!![0] as Int != 0) return
                        PrivacySlide.statusBarRequested =
                            (p!!.args!![5] as Int and Type.statusBars()) != 0
                        if (!PrivacySlide.statusBarRequested && PrivacySlide.enabled)
                            PrivacySlide.cancel()
                    }
                },
            )

            HookRegistry.hook(
                samsung,
                "invoke",
                Any::class.java,
                Any::class.java,
                Any::class.java,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        PrivacySlide.otherSamsungHide =
                            PrivacySlideGeometry.otherHide(
                                run {
                                    val reasons = p!!.args!![1] as? kotlin.Array<Any?>
                                    if (reasons != null) reasons else null
                                }
                            )
                    }
                },
            )
            HookRegistry.hook(
                home,
                "invoke",
                Any::class.java,
                Any::class.java,
                Any::class.java,
                Any::class.java,
                Any::class.java,
                Any::class.java,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        PrivacySlide.homeAllowed =
                            JavaBoolean.TRUE == p!!.args!![0] &&
                                (JavaBoolean.TRUE != p!!.args!![1]) &&
                                (JavaBoolean.TRUE != p!!.args!![4])
                    }
                },
            )
            HookRegistry.hook(
                flags,
                "invoke",
                Any::class.java,
                Any::class.java,
                Any::class.java,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        PrivacySlide.infoAllowed =
                            Reflect.getBooleanField(p!!.args!![1]!!, "isSystemInfoAllowed")
                    }
                },
            )
            HookRegistry.hook(
                scheduler,
                "onStatusEvent",
                event,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (
                            !PrivacySlide.enabled ||
                                !PrivacySlide.statusBarRequested ||
                                !PrivacySlide.privacyEvent(p!!.args!![0]!!) ||
                                PrivacySlide.session != null
                        )
                            return
                        val group = PrivacySlide.rightGroup()
                        if (
                            !PrivacySlide.scene(group) ||
                                !group!!.isShown() ||
                                group!!.getAlpha() < .99f
                        )
                            return
                        PrivacySlide.pending = true
                        PrivacySlide.state = "reserving"
                        val generation: Int = ++PrivacySlide.pendingGeneration
                        PrivacySlide.main.postDelayed(
                            Runnable {
                                if (
                                    PrivacySlide.pending &&
                                        PrivacySlide.session == null &&
                                        generation == PrivacySlide.pendingGeneration
                                ) {
                                    PrivacySlide.pending = false
                                    PrivacySlide.restoreNative()
                                    PrivacySlide.state =
                                        if (PrivacySlide.enabled) "ready" else "off"
                                }
                            },
                            1500,
                        )
                    }
                },
            )
            HookRegistry.hook(
                binder,
                "adjustVisibility",
                View::class.java,
                visibilityType,
                object : HookCallback() {
                    @Throws(Exception::class)
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (PrivacySlide.replaying || p!!.args!![0] !== PrivacySlide.rightGroup())
                            return
                        PrivacySlide.nativeTarget = p!!.args!![0] as View?
                        PrivacySlide.nativeVisibility = p!!.args!![1]
                        if (PrivacySlide.canReserve())
                            p!!.args!![1] =
                                PrivacySlide.visibility!!.newInstance(View.VISIBLE, false)
                    }
                },
            )
            HookRegistry.hook(
                controller,
                "prepareChipAnimation",
                function,
                Boolean::class.javaPrimitiveType,
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (
                            !PrivacySlide.pending ||
                                JavaBoolean.TRUE == p!!.args!![1] ||
                                !PrivacySlide.canReserve()
                        )
                            return
                        try {
                            val view = Reflect.getObjectField(p.thisObject!!, "currentAnimatedView")
                            val group = PrivacySlide.rightGroup()
                            if (
                                (view !is View) ||
                                    (view.javaClass.getName() !=
                                        "com.android.systemui.privacy.ui.view.ComposeOngoingPrivacyChip") ||
                                    !PrivacySlide.scene(group)
                            )
                                return
                            val next = PrivacySlide.Session(group!!, view, p.thisObject!!)
                            if (!next.fits()) {
                                PrivacySlide.pending = false
                                PrivacySlide.restoreNative()
                                PrivacySlide.state = "native fallback: insufficient space"
                                return
                            }
                            PrivacySlide.session = next
                            next.attach()
                        } catch (error: Throwable) {
                            PrivacySlide.fail(error)
                        }
                    }
                },
            )
            HookRegistry.hook(
                controller,
                "onSystemEventAnimationBegin",
                Boolean::class.javaPrimitiveType,
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val active = PrivacySlide.session
                        if (
                            active != null &&
                                active.controller === p.thisObject &&
                                (JavaBoolean.TRUE != p!!.args!![0])
                        ) {
                            active.started = SystemClock.uptimeMillis()
                            active.entering = true
                            active.chip.invalidate()
                            PrivacySlide.state = "entering"
                        }
                    }
                },
            )
            HookRegistry.hook(
                controller,
                "onSystemEventAnimationFinish",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val active = PrivacySlide.session
                        if (
                            active != null &&
                                active.controller === p.thisObject &&
                                (JavaBoolean.TRUE != p!!.args!![1])
                        ) {
                            active.finish(JavaBoolean.TRUE == p!!.args!![0])
                        }
                    }
                },
            )
            PrivacyDots.install(loader)
            PrivacySlide.installed = true
            PrivacySlide.state = "off"
        } catch (unavailable: Throwable) {
            PrivacySlide.state = "unavailable"
            Hooks.log("GalaxyBar: privacy slide contract unavailable: " + unavailable)
        }
    }

    @JvmStatic
    private fun privacyEvent(event: Any): Boolean {
        if (event.javaClass.getName() != PrivacySlide.BASE + "events.PrivacyEvent") return false
        if (
            !Reflect.getBooleanField(event, "forceVisible") ||
                !Reflect.getBooleanField(event, "showAnimation")
        )
            return false
        val items = Reflect.getObjectField(event, "privacyItems") as? MutableList<*>
        if (items == null) return false
        for (item in items) {
            val type = Reflect.getObjectField(item!!, "privacyType")
            if (type is Enum<*> && (type.name == "TYPE_CAMERA" || type.name == "TYPE_MICROPHONE"))
                return true
        }
        return false
    }

    @JvmStatic
    private fun canReserve(): Boolean {
        return PrivacySlide.enabled &&
            PrivacySlide.statusBarRequested &&
            (PrivacySlide.pending || PrivacySlide.session != null) &&
            !PrivacySlide.otherSamsungHide &&
            PrivacySlide.homeAllowed &&
            PrivacySlide.infoAllowed &&
            PrivacySlide.scene(PrivacySlide.rightGroup())
    }

    @JvmStatic
    fun allowDot(): Boolean {
        return PrivacySlide.enabled &&
            PrivacySlide.statusBarRequested &&
            (!PrivacySlide.pending ||
                PrivacySlide.session != null && PrivacySlide.session!!.exiting) &&
            (PrivacySlide.session == null || PrivacySlide.session!!.exiting) &&
            !PrivacySlide.otherSamsungHide &&
            PrivacySlide.homeAllowed &&
            PrivacySlide.infoAllowed &&
            PrivacySlide.scene(PrivacySlide.rightGroup())
    }

    @JvmStatic
    fun reserveDot(dotLeft: Float, alpha: Float): Boolean {
        val group = PrivacySlide.rightGroup()
        if (
            !PrivacySlide.allowDot() || group == null || !group.isShown() || group.getAlpha() < .99f
        ) {
            PrivacySlide.releaseDot()
            return false
        }
        val gap: Float = 4 * group.getResources().getDisplayMetrics().density
        val left = PrivacySlide.baselineLeft(group)
        val dx = PrivacySlideGeometry.dotShift(left + group.getWidth(), dotLeft, gap, alpha)
        if (PrivacySlide.crowdedFallback && !PrivacySlide.fits(group, left, dx)) {
            PrivacySlide.releaseDot()
            return false
        }
        if (dx < 0) PrivacySlide.iconSpace(group)!!.dot(dx)
        else if (PrivacySlide.space != null) PrivacySlide.space!!.dot(0f)
        PrivacySlide.releaseIdleSpace()
        return true
    }

    @JvmStatic
    fun releaseDot() {
        if (PrivacySlide.space != null) PrivacySlide.space!!.dot(0f)
        PrivacySlide.releaseIdleSpace()
    }

    @JvmStatic
    private fun releaseIdleSpace() {
        if (
            PrivacySlide.space != null &&
                PrivacySlide.space!!.idle() &&
                PrivacySlide.session == null &&
                !PrivacySlide.pending
        ) {
            PrivacySlide.space!!.close()
            PrivacySlide.space = null
        }
    }

    @JvmStatic
    private fun iconSpace(group: View?): PrivacyIconSpace? {
        if (PrivacySlide.space == null || PrivacySlide.space!!.group !== group) {
            if (PrivacySlide.space != null) PrivacySlide.space!!.close()
            PrivacySlide.space = PrivacyIconSpace(group!!, PrivacySlide.root.get())
        }
        return PrivacySlide.space
    }

    @JvmStatic
    private fun baselineLeft(group: View): Float {
        if (PrivacySlide.space != null && PrivacySlide.space!!.group === group)
            return PrivacySlide.space!!.baselineLeft()
        val at = IntArray(2)
        group.getLocationOnScreen(at)
        return at[0].toFloat()
    }

    @JvmStatic
    private fun fits(group: View, left: Float, dx: Float): Boolean {
        val gap: Float = 4 * group.getResources().getDisplayMetrics().density
        var occupied: Float = 0f
        for (id in arrayOf<String>("clock", "notificationIcons")) {
            val start = PrivacySlide.find(PrivacySlide.root.get(), id)
            if (start != null && start.isShown()) {
                val at = IntArray(2)
                start.getLocationOnScreen(at)
                occupied = max(occupied, at[0] + start.getWidth() + gap)
            }
        }
        return group.getWidth() > 0 && PrivacySlideGeometry.fits(left, dx, occupied)
    }

    @JvmStatic
    private fun scene(view: View?): Boolean {
        val window = PrivacySlide.root.get()
        return view != null &&
            window != null &&
            window.isAttachedToWindow() &&
            window.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR &&
            (window.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_PORTRAIT) &&
            window.getDisplay() != null &&
            window.getDisplay().getDisplayId() == 0
    }

    @JvmStatic
    private fun find(view: View?, name: String?): View? {
        if (view == null) return null
        try {
            if (view.getResources().getResourceEntryName(view.getId()) == name) return view
        } catch (ignored: NotFoundException) {}
        if (view is ViewGroup)
            for (i in 0 until view.getChildCount()) {
                val found = PrivacySlide.find(view.getChildAt(i), name)
                if (found != null) return found
            }
        return null
    }

    @JvmStatic
    private fun rightGroup(): View? {
        return PrivacySlide.find(PrivacySlide.root.get(), "status_bar_end_side_content")
    }

    @JvmStatic
    private fun restoreNative() {
        if (
            PrivacySlide.nativeTarget == null ||
                PrivacySlide.nativeVisibility == null ||
                PrivacySlide.applyVisibility == null
        )
            return
        PrivacySlide.replaying = true
        try {
            PrivacySlide.applyVisibility!!.invoke(
                null,
                PrivacySlide.nativeTarget,
                PrivacySlide.nativeVisibility,
            )
        } catch (error: ReflectiveOperationException) {
            Hooks.log(error)
        } finally {
            PrivacySlide.replaying = false
        }
    }

    @JvmStatic
    fun cancel() {
        val resume = PrivacySlide.enabled
        PrivacySlide.clearReservation()
        PrivacySlide.enabled = resume
        PrivacySlide.state = if (PrivacySlide.enabled) "ready" else "off"
        PrivacyDots.configure(PrivacySlide.enabled)
    }

    @JvmStatic
    fun reset() {
        PrivacySlide.clearReservation()
        PrivacySlide.installed = false
        PrivacyDots.reset()
    }

    @JvmStatic
    private fun clearReservation() {
        PrivacySlide.enabled = false
        PrivacyDots.configure(false)
        PrivacySlide.pending = false
        ++PrivacySlide.pendingGeneration
        if (PrivacySlide.session != null) PrivacySlide.session!!.close()
        if (PrivacySlide.space != null) {
            PrivacySlide.space!!.close()
            PrivacySlide.space = null
        }
        PrivacySlide.restoreNative()
        PrivacySlide.nativeVisibility = null
        PrivacySlide.nativeTarget = null
        PrivacySlide.state = "off"
    }

    @JvmStatic
    private fun fail(error: Throwable?) {
        PrivacySlide.reset()
        HookEntry.stopHooks(error)
    }

    private class Session(group: View, chip: View, controller: Any) :
        OnPreDrawListener, OnAttachStateChangeListener {
        @JvmField val group: View
        @JvmField val chip: View
        @JvmField val window: View
        @JvmField val content: View
        @JvmField val controller: Any
        @JvmField val contentX: Float
        @JvmField val contentAlpha: Float
        @JvmField val chipClipsChildren: Boolean
        @JvmField val chipClipsPadding: Boolean
        @JvmField val gap: Float
        @JvmField var started: Long = 0
        @JvmField var entering: Boolean = false
        @JvmField var exiting: Boolean = false
        @JvmField var closed: Boolean = false
        @JvmField var appliedContentX: Float
        @JvmField var appliedContentAlpha: Float
        @JvmField var requestedShift: Float = 0f
        @JvmField var exitAnimator: ValueAnimator? = null

        init {
            this.group = group
            this.chip = chip
            this.controller = controller
            window = PrivacySlide.root.get()!!
            content = Reflect.getObjectField(chip, "launchableView") as View
            appliedContentX = content.getTranslationX()
            contentX = appliedContentX
            appliedContentAlpha = content.getAlpha()
            contentAlpha = appliedContentAlpha
            chipClipsChildren = (chip as ViewGroup).getClipChildren()
            chipClipsPadding = (chip as ViewGroup).getClipToPadding()
            gap = 4 * group.getResources().getDisplayMetrics().density
        }

        fun fits(): Boolean {
            val bounds: Rect? = Reflect.getObjectField(controller, "chipBounds") as Rect?
            val origin = IntArray(2)
            (Reflect.getObjectField(controller, "animationWindowView") as View).getLocationOnScreen(
                origin
            )
            val shift =
                PrivacySlideGeometry.shift(
                    PrivacySlide.baselineLeft(group) + group.getWidth(),
                    (origin[0] + bounds!!.left).toFloat(),
                    (origin[0] + bounds!!.right).toFloat(),
                    gap,
                    1f,
                    1f,
                )
            return bounds!!.width() > 0 &&
                group.getWidth() > 0 &&
                (!PrivacySlide.crowdedFallback ||
                    PrivacySlide.fits(group, PrivacySlide.baselineLeft(group), shift))
        }

        @Throws(Exception::class)
        fun attach() {
            PrivacySlide.iconSpace(group)
            (chip as ViewGroup).setClipChildren(false)
            (chip as ViewGroup).setClipToPadding(false)
            group.getViewTreeObserver().addOnPreDrawListener(this)
            chip.addOnAttachStateChangeListener(this)
            window.addOnAttachStateChangeListener(this)
            PrivacySlide.replaying = true
            try {
                PrivacySlide.applyVisibility!!.invoke(
                    null,
                    group,
                    PrivacySlide.visibility!!.newInstance(View.VISIBLE, false),
                )
            } finally {
                PrivacySlide.replaying = false
            }
            entrance(0f)
            PrivacySlide.main.postDelayed(
                Runnable {
                    if (!closed) {
                        close()
                        PrivacySlide.pending = false
                        PrivacySlide.restoreNative()
                        PrivacySlide.state = "native fallback: timeout"
                    }
                },
                7000,
            )
        }

        override fun onPreDraw(): Boolean {
            if (closed) return true
            try {
                if (
                    !PrivacySlide.scene(group) ||
                        !PrivacySlide.canReserve() ||
                        (!exiting && !chip.isAttachedToWindow())
                ) {
                    close()
                    PrivacySlide.restoreNative()
                    return true
                }
                if (exiting) return true
                val rect: Rect? = Reflect.getObjectField(controller, "animRect") as Rect?
                val origin = IntArray(2)
                (Reflect.getObjectField(controller, "animationWindowView") as View)
                    .getLocationOnScreen(origin)
                val baselineRight: Float = PrivacySlide.baselineLeft(group) + group.getWidth()
                val fraction: Float =
                    if (started == 0L) 0f
                    else
                        PrivacySlide.easing.getInterpolation(
                            PrivacySlideGeometry.unit(
                                (SystemClock.uptimeMillis() - started) /
                                    PrivacySlideGeometry.ENTER_DURATION_MS.toFloat()
                            )
                        )
                val reveal: Float =
                    if (entering) fraction else (if (started == 0L) 0 else 1).toFloat()
                val dx =
                    PrivacySlideGeometry.shift(
                        baselineRight,
                        origin[0] + rect!!.left + chip.getTranslationX(),
                        origin[0] + rect!!.right + chip.getTranslationX(),
                        gap,
                        reveal,
                        chip.getAlpha(),
                    )
                requestedShift = dx
                PrivacySlide.iconSpace(group)!!.chip(dx)
                if (entering && fraction < 1) {
                    entrance(fraction)
                    chip.postInvalidateOnAnimation()
                } else {
                    restoreEntrance()
                    if (!exiting && started != 0L) PrivacySlide.state = "holding"
                    if (exiting) chip.postInvalidateOnAnimation()
                }
            } catch (error: Throwable) {
                PrivacySlide.fail(error)
            }
            return true
        }

        fun finish(persistentDot: Boolean) {
            if (closed || exiting) return
            entering = false
            exiting = true
            restoreEntrance()
            PrivacySlide.state = "leaving"
            // Keep ownership across native chip removal. Releasing it at detach produces a jump
            // when Samsung's persistent-dot window appears on a different frame.
            val from: Float = requestedShift
            exitAnimator = ValueAnimator.ofFloat(0f, 1f)
            exitAnimator!!.setDuration(PrivacySlideGeometry.ENTER_DURATION_MS)
            exitAnimator!!.setStartDelay((if (persistentDot) 100 else 0).toLong())
            exitAnimator!!.setInterpolator(PrivacySlide.exitEasing)
            exitAnimator!!.addUpdateListener(
                AnimatorUpdateListener { animator: ValueAnimator? ->
                    try {
                        if (closed) return@AnimatorUpdateListener
                        if (!PrivacySlide.canReserve()) {
                            close()
                            PrivacySlide.restoreNative()
                            return@AnimatorUpdateListener
                        }
                        val endpoint: Float =
                            if (persistentDot) -gap * PrivacyDots.reservationAlpha() else 0f
                        val progress: Float = animator!!.getAnimatedValue() as Float
                        requestedShift = from + (endpoint - from) * progress
                        PrivacySlide.iconSpace(group)!!.chip(requestedShift)
                    } catch (error: Throwable) {
                        PrivacySlide.fail(error)
                    }
                }
            )
            exitAnimator!!.addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!closed) {
                            try {
                                PrivacyDots.refresh()
                                close()
                            } catch (error: Throwable) {
                                PrivacySlide.fail(error)
                            }
                        }
                    }
                }
            )
            exitAnimator!!.start()
        }

        fun entrance(progress: Float) {
            appliedContentX =
                contentX + PrivacySlideGeometry.entranceOffset(chip.getWidth().toFloat(), progress)
            appliedContentAlpha = contentAlpha * PrivacySlideGeometry.entranceAlpha(progress)
            content.setTranslationX(appliedContentX)
            content.setAlpha(appliedContentAlpha)
        }

        fun restoreEntrance() {
            if (abs(content.getTranslationX() - appliedContentX) < .01f)
                content.setTranslationX(contentX)
            if (abs(content.getAlpha() - appliedContentAlpha) < .01f) content.setAlpha(contentAlpha)
            (chip as ViewGroup).setClipChildren(chipClipsChildren)
            (chip as ViewGroup).setClipToPadding(chipClipsPadding)
        }

        fun close() {
            if (closed) return
            closed = true
            if (exitAnimator != null) {
                exitAnimator!!.removeAllUpdateListeners()
                exitAnimator!!.removeAllListeners()
                exitAnimator!!.cancel()
                exitAnimator = null
            }
            if (group.getViewTreeObserver().isAlive())
                group.getViewTreeObserver().removeOnPreDrawListener(this)
            chip.removeOnAttachStateChangeListener(this)
            window.removeOnAttachStateChangeListener(this)
            if (PrivacySlide.space != null && PrivacySlide.space!!.group === group)
                PrivacySlide.space!!.chip(0f)
            restoreEntrance()
            if (PrivacySlide.session == this) PrivacySlide.session = null
            PrivacySlide.pending = false
            PrivacySlide.releaseIdleSpace()
            PrivacySlide.completed++
            PrivacySlide.state = if (PrivacySlide.enabled) "ready" else "off"
        }

        override fun onViewAttachedToWindow(view: View) {}

        override fun onViewDetachedFromWindow(view: View) {
            if (view === chip && exiting) return
            close()
        }
    }
}
