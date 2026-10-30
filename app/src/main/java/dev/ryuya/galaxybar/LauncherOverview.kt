package dev.ryuya.galaxybar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.OnPreDrawListener
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.HookCallback.Unhook
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.lang.ref.WeakReference
import java.util.function.Consumer
import kotlin.jvm.JvmRecord
import kotlin.math.log
import kotlin.math.max

/* Observes the actual overview card and Samsung's separate task-launch surface transform. */
class LauncherOverview(
    context: Context,
    main: Handler,
    sink: LauncherOverview.Sink,
    failure: Consumer<Throwable?>,
) {
    interface Sink {
        fun frame(
            owner: Any?,
            task: Int,
            opening: Boolean,
            overview: Boolean,
            rect: RectF,
            alpha: Float,
            radius: Float,
        )

        fun end(owner: Any?)
    }

    private val context: Context
    private val main: Handler
    private val sink: LauncherOverview.Sink
    private val failure: Consumer<Throwable?>
    private var card: WeakReference<View?> = WeakReference<View?>(null)
    private var observer: ViewTreeObserver? = null
    private var taskId: Int = -1
    private var overview: Boolean = false
    private var launch: Any? = null
    private var launchTask: Int = -1

    @JvmRecord
    private data class CardGeometry(val rect: RectF, val alpha: Float, val radius: Float) {
        override fun toString(): String = "CardGeometry[rect=$rect, alpha=$alpha, radius=$radius]"
    }

    private val heartbeat: Runnable = Runnable { protect(Runnable { this.refreshCard() }) }
    private val preDraw: OnPreDrawListener = OnPreDrawListener {
        protect(Runnable { this.refreshCard() })
        true
    }

    init {
        this.context = context
        this.main = main
        this.sink = sink
        this.failure = failure
    }

    fun install(loader: ClassLoader?, hooks: MutableList<Unhook?>) {
        val manager =
            Reflect.findClass(
                "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager",
                loader,
            )
        hooks.add(
            Reflect.findAndHookMethod(
                manager,
                "startRecentEntering",
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        protect(
                            Runnable {
                                stop()
                                taskId =
                                    (Reflect.callMethod(p.thisObject!!, "getTaskId") as Number)
                                        .toInt()
                                overview = true
                                Hooks.log("GalaxyBar: overview entering task=" + taskId)
                            }
                        )
                    }
                },
            )
        )
        hooks.add(
            Reflect.findAndHookMethod(
                manager,
                "startHomeEntering",
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        protect(Runnable { this@LauncherOverview.stop() })
                    }
                },
            )
        )
        val scene =
            Reflect.findClass(HookCompatibility.taskSceneView(Build.VERSION.SDK_INT)!!, loader)
        hooks.add(
            Reflect.findAndHookMethod(
                scene,
                "onDraw",
                Canvas::class.java,
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        protect(
                            Runnable {
                                if (!overview || taskId < 0 || !(p.thisObject as View).isShown())
                                    return@Runnable
                                if (
                                    !(Reflect.callMethod(p!!.thisObject!!, "hasTaskId", taskId)
                                        as Boolean)
                                )
                                    return@Runnable
                                if (
                                    (Reflect.callMethod(p!!.thisObject!!, "getTasks")
                                            as MutableList<*>)
                                        .size != 1
                                )
                                    return@Runnable
                                val next: View? = p.thisObject as View?
                                if (card.get() !== next) {
                                    // A TaskSceneView can draw before its background crop is ready.
                                    // Do not suppress the native animation until we can send a card
                                    // frame.
                                    val geometry = geometry(next!!)
                                    if (geometry == null || geometry.alpha <= 0) return@Runnable
                                    detach()
                                    card = WeakReference<View?>(next)
                                    observer = next!!.getRootView().getViewTreeObserver()
                                    observer!!.addOnPreDrawListener(preDraw)
                                    Hooks.log(
                                        ("GalaxyBar: overview card connected task=" +
                                            taskId +
                                            " alpha=" +
                                            geometry.alpha)
                                    )
                                    publish(next, geometry)
                                } else {
                                    refreshCard()
                                }
                            }
                        )
                    }
                },
            )
        )
        val transition =
            Reflect.findClass("com.honeyspace.transition.utils.TaskLaunchTransition", loader)
        hooks.addAll(
            Hooks.hookAllMethods(
                transition,
                "createTaskLeashAnimator",
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        protect(
                            Runnable {
                                stop()
                                if (p!!.args!![9] as Int? != 0) return@Runnable
                                val targets: Any? = p!!.args!![3]
                                val target = Reflect.callMethod(targets!!, "getFirstAppTarget")
                                if (target == null) return@Runnable
                                val config = Reflect.getObjectField(target, "windowConfiguration")
                                if (
                                    Reflect.callMethod(config!!, "getWindowingMode") as Int? != 1 ||
                                        Reflect.callMethod(config!!, "getActivityType") as Int? != 1
                                )
                                    return@Runnable
                                val info = Reflect.getObjectField(target, "taskInfo")
                                if (info == null || Reflect.getIntField(info, "displayId") != 0)
                                    return@Runnable
                                launchTask = Reflect.getIntField(target, "taskId")
                                Hooks.log("GalaxyBar: overview app launch task=" + launchTask)
                                launch = Any()
                                p.setObjectExtra("galaxybar.launch", launch)
                                sink.frame(
                                    launch,
                                    launchTask,
                                    true,
                                    false,
                                    RectF(p!!.args!![5] as RectF?),
                                    1f,
                                    p!!.args!![2] as Float,
                                )
                            }
                        )
                    }

                    override fun afterHookedMethod(p: MethodHookParam) {
                        protect(
                            Runnable {
                                val owner = p.getObjectExtra("galaxybar.launch")
                                val patternPrefix0 = owner == null || p.hasThrowable()
                                val animation =
                                    if (!patternPrefix0) p.getResult() as? Animator else null
                                if (patternPrefix0 || animation == null) return@Runnable
                                animation.addListener(
                                    object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(ignored: Animator) {
                                            protect(
                                                Runnable {
                                                    sink.end(owner)
                                                    if (launch === owner) {
                                                        launch = null
                                                        launchTask = -1
                                                    }
                                                    animation.removeListener(this)
                                                }
                                            )
                                        }

                                        override fun onAnimationCancel(ignored: Animator) {
                                            onAnimationEnd(ignored)
                                        }
                                    }
                                )
                            }
                        )
                    }
                },
            )
        )
        hooks.addAll(
            Hooks.hookAllMethods(
                transition,
                "createSurfaceParams",
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        protect(
                            Runnable {
                                if (launch == null || launchTask < 0 || p.hasThrowable())
                                    return@Runnable
                                val crop: Rect? =
                                    Reflect.getStaticObjectField(transition, "cropRect") as Rect?
                                val matrix: Matrix? =
                                    Reflect.getStaticObjectField(transition, "matrix") as Matrix?
                                val rect = RectF(crop)
                                matrix!!.mapRect(rect)
                                val start =
                                    Reflect.getStaticFloatField(transition, "initialWindowRadius")
                                val end =
                                    Reflect.getStaticFloatField(transition, "finalWindowRadius")
                                val fraction: Float = p!!.args!![1] as Float
                                val radius: Float =
                                    ((start + (end - start) * fraction) * rect.width() /
                                        max(1, crop!!.width()))
                                sink.frame(
                                    launch,
                                    launchTask,
                                    true,
                                    false,
                                    rect,
                                    Reflect.getStaticFloatField(transition, "leashAlpha"),
                                    radius,
                                )
                            }
                        )
                    }
                },
            )
        )
    }

    fun followsTask(task: Int): Boolean {
        return overview && taskId == task
    }

    fun ownsCard(): Boolean {
        return overview && card.get() != null
    }

    private fun refreshCard() {
        main.removeCallbacks(heartbeat)
        val view = card.get()
        if (!overview || view == null) return
        if (
            !view.isAttachedToWindow() ||
                !view.isShown() ||
                !context
                    .getSystemService<PowerManager?>(PowerManager::class.java)
                    .isInteractive() ||
                context
                    .getSystemService<KeyguardManager?>(KeyguardManager::class.java)
                    .isKeyguardLocked()
        ) {
            stop()
            return
        }
        val geometry = geometry(view)
        if (geometry == null) return
        publish(view, geometry)
    }

    private fun geometry(view: View): LauncherOverview.CardGeometry? {
        val state = Reflect.callMethod(view, "getSceneStateInfo")
        val ratio: Float = view.getWidth() / (max(1, view.getHeight())).toFloat()
        val bounds: MutableList<*>? =
            Reflect.callMethod(state!!, "getDestBgCropBounds", ratio, 0f) as MutableList<*>?
        if (bounds!!.size != 1) return null
        val local = RectF(bounds!!.get(0) as RectF?)
        if (local.isEmpty()) return null
        val matrix = Matrix()
        view.transformMatrixToGlobal(matrix)
        val rect = RectF(local)
        matrix.mapRect(rect)
        val radii: MutableList<*>? =
            Reflect.callMethod(view, "getBackgroundCornerRadii") as MutableList<*>?
        if (radii!!.size != 1) return null
        val corners: FloatArray? = radii!!.get(0) as FloatArray?
        if (corners!!.size == 0) return null
        var alpha = view.getAlpha()
        var parent = view.getParent()
        while (parent is View) {
            alpha *= parent.getAlpha()
            parent = parent.getParent()
        }
        return LauncherOverview.CardGeometry(
            rect,
            alpha,
            corners!![0] * rect.width() / local.width(),
        )
    }

    private fun publish(view: View?, geometry: LauncherOverview.CardGeometry) {
        sink.frame(view, taskId, false, true, geometry.rect, geometry.alpha, geometry.radius)
        // Traversals supply moving frames; a stationary card only needs a lightweight liveness
        // pulse.
        main.postDelayed(heartbeat, 75)
    }

    private fun detach() {
        if (observer != null && observer!!.isAlive()) observer!!.removeOnPreDrawListener(preDraw)
        observer = null
        main.removeCallbacks(heartbeat)
    }

    fun stop() {
        overview = false
        val previous = card.get()
        if (previous != null) sink.end(previous)
        card.clear()
        detach()
        if (launch != null) sink.end(launch)
        launch = null
        launchTask = -1
        taskId = -1
    }

    private fun protect(task: Runnable) {
        try {
            task.run()
        } catch (error: Throwable) {
            failure.accept(error)
        }
    }
}
