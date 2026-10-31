package dev.ryuya.galaxybar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.os.UserManager
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.HookCallback.Unhook
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.util.ArrayList
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min

/* Observes One UI's final task-window transform after both standard and Home Up calculators. */
class LauncherMotion(context: Context, loader: ClassLoader, protection: CrashGuard) {
    private val context: Context
    private val loader: ClassLoader
    private val protection: CrashGuard
    private val main: Handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService? =
        Executors.newSingleThreadExecutor(
            ThreadFactory { r: Runnable? ->
                val t = Thread(r, "GalaxyBarMotionConfig")
                t.setDaemon(true)
                t
            }
        )
    private val sessions: MutableMap<Any?, LauncherMotion.Session?> =
        WeakHashMap<Any?, LauncherMotion.Session?>()
    private val hooks: MutableList<Unhook?> = ArrayList<Unhook?>()
    private var foreground: LauncherMotion.Session? = null
    private val overview: LauncherOverview
    @Volatile private var endpoint: IBinder? = null
    private var enabled: Boolean = false
    private var failed: Boolean = false
    private var revision: Int = 0
    private val heartbeat: Runnable = Runnable {
        try {
            heartbeat()
        } catch (error: Throwable) {
            fail(error)
        }
    }
    private val observer: ContentObserver =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key: String? = if (uri == null) null else uri.getLastPathSegment()
                if (
                    uri == null || uri == ConfigProvider.URI || "enabled" == key || "motion" == key
                ) {
                    disable()
                    failed = !protection.allowsHooks()
                }
                refresh()
            }
        }

    init {
        this.context = context
        this.loader = loader
        this.protection = protection
        overview =
            LauncherOverview(
                context,
                main,
                object : LauncherOverview.Sink {
                    override fun frame(
                        owner: Any?,
                        task: Int,
                        opening: Boolean,
                        recent: Boolean,
                        rect: RectF,
                        alpha: Float,
                        radius: Float,
                    ) {
                        if (!enabled || endpoint == null) return
                        var s = sessions.get(owner)
                        if (s == null || s.ended || s != foreground) s = begin(owner)
                        val now = SystemClock.uptimeMillis()
                        s.lastSent = now
                        s.last =
                            MotionFrame(
                                s.id,
                                now,
                                opening,
                                task,
                                recent,
                                recent,
                                rect.left,
                                rect.top,
                                rect.width(),
                                rect.height(),
                                max(0f, min(1f, alpha)),
                                -1f,
                                max(0f, radius),
                                false,
                            )
                        send(s.last!!)
                    }

                    override fun end(owner: Any?) {
                        val s = sessions.get(owner)
                        if (s != null) this@LauncherMotion.end(s)
                    }
                },
                Consumer { error: Throwable? -> this.fail(error) },
            )
        registerMotion()
    }

    private fun registerMotion() {
        if (
            !HookCompatibility.matchesLauncherVersion(
                Build.VERSION.SDK_INT,
                context
                    .getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode(),
            )
        ) {
            Hooks.log("GalaxyBar: unsupported One UI Home version; motion hooks not installed")
            return
        }
        context.getContentResolver().registerContentObserver(ConfigProvider.URI!!, true, observer)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    refresh()
                }
            },
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    private fun refresh() {
        if (!context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked())
            return
        val request: Int = ++revision
        io!!.execute(
            Runnable {
                try {
                    val config =
                        context
                            .getContentResolver()
                            .call(ConfigProvider.URI!!, "config", null, null)
                    val result =
                        context
                            .getContentResolver()
                            .call(ConfigProvider.URI!!, "motion_endpoint", null, null)
                    val binder: IBinder? =
                        if (result == null) null else result.getBinder("motion_endpoint")
                    main.post(
                        Runnable {
                            if (request != revision) return@Runnable
                            endpoint = binder
                            val wanted: Boolean =
                                config != null &&
                                    config.getBoolean("enabled") &&
                                    config.getBoolean("motion", true)
                            if (!wanted) {
                                disable()
                                failed = !protection.allowsHooks()
                            } else if (!enabled && !failed && protection.allowsHooks()) {
                                try {
                                    protection.arm()
                                    install()
                                    enabled = true
                                } catch (error: Throwable) {
                                    fail(error)
                                }
                            }
                        }
                    )
                } catch (error: Throwable) {
                    main.post(
                        Runnable {
                            if (request == revision) disable()
                        }
                    )
                }
            }
        )
    }

    private fun install() {
        val player = Reflect.findClass(LauncherMotion.PLAYER, loader)
        val input =
            Reflect.findClass(
                "com.honeyspace.transition.anim.floating.entity.FloatingInputData",
                loader,
            )
        val desktop =
            Reflect.findClass(
                "com.honeyspace.transition.anim.floating.entity.DesktopInputData",
                loader,
            )
        val transaction =
            Reflect.findClass("com.honeyspace.transition.utils.SurfaceTransaction", loader)
        val start: HookCallback =
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        if (!enabled) return
                        overview.stop()
                        val s = begin(p.thisObject)
                        s.input = p!!.args!![0]
                        s.opening = Reflect.getBooleanField(p!!.args!![0]!!, "isForward")
                        s.spring = p.method!!.getName() == "startSpring"
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        val s = sessions.get(p.thisObject)
                        if (s == null || !enabled) return
                        val animator: AnimatorSet? =
                            Reflect.callMethod(p!!.args!![0]!!, "getAnimatorSet") as AnimatorSet?
                        if (!s.spring && s.duration <= 0) {
                            val params =
                                Reflect.getObjectField(p.thisObject!!, "appTransitionParams")
                            val opening = Reflect.callMethod(params!!, "getOpenParams")
                            s.duration =
                                ((Reflect.callMethod(opening!!, "getWindowPositionDuration")
                                        as Number)
                                    .toFloat() * ValueAnimator.getDurationScale())
                        }
                        animator!!.addListener(
                            object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(ignored: Animator) {
                                    end(s)
                                    animator!!.removeListener(this)
                                }

                                override fun onAnimationCancel(ignored: Animator) {
                                    end(s)
                                }
                            }
                        )
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            }
        hooks.add(
            Reflect.findAndHookMethod(
                player,
                "start",
                input,
                Boolean::class.javaPrimitiveType,
                start,
            )
        )
        hooks.add(Reflect.findAndHookMethod(player, "startSpring", input, desktop, start))
        hooks.add(
            Reflect.findAndHookMethod(
                player,
                "makeUpdateInfo",
                transaction,
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            if (
                                !enabled ||
                                    endpoint == null ||
                                    p.hasThrowable() ||
                                    overview.ownsCard()
                            )
                                return
                            val s = sessions.get(p.thisObject)
                            if (s == null || s.ended || s != foreground) return
                            if (!s.verified) {
                                if (!fullscreen(p.thisObject)) return
                                s.verified = true
                            }
                            val now = SystemClock.uptimeMillis()
                            if (now == s.lastSent) return
                            val info = p.getResult()
                            val point: PointF? = Reflect.getObjectField(info!!, "point") as PointF?
                            val crop: Rect? = Reflect.getObjectField(info!!, "crop") as Rect?
                            val scale = Reflect.getFloatField(info!!, "scale")
                            val width: Float = crop!!.width() * scale
                            var remaining: Float = -1f
                            val opening = Reflect.getBooleanField(p.thisObject!!, "isForward")
                            if (opening != s.opening) {
                                s.opening = opening
                                s.spring =
                                    true // A changed native target invalidates the old end-time
                                // estimate.
                            }
                            if (!s.spring && !s.interactive && s.duration > 0)
                                remaining = max(0f, s.duration - (now - s.started))
                            s.lastSent = now
                            val task =
                                (Reflect.callMethod(p.thisObject!!, "getRunningTaskId") as Number)
                                    .toInt()
                            s.last =
                                MotionFrame(
                                    s.id,
                                    now,
                                    s.opening,
                                    task,
                                    s.interactive,
                                    overview.followsTask(task),
                                    point!!.x + crop!!.left * scale,
                                    point!!.y + crop!!.top * scale,
                                    width,
                                    crop!!.height() * scale,
                                    Reflect.getFloatField(info!!, "alpha"),
                                    remaining,
                                    Reflect.getFloatField(info!!, "cornerRadius") * scale,
                                    false,
                                )
                            send(s.last!!)
                        } catch (error: Throwable) {
                            fail(error)
                        }
                    }
                },
            )
        )
        val finish: HookCallback =
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val s = sessions.get(p.thisObject)
                    if (s != null) end(s)
                }
            }
        for (method in HookCompatibility.playerFinishMethods(Build.VERSION.SDK_INT)!!) hooks.add(
            Reflect.findAndHookMethod(player, method!!, finish)
        )
        // Catch/moving can run with the old AnimatorSet paused, before any start/startSpring call.
        val gesture: HookCallback =
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        if (!enabled) return
                        var s = sessions.get(p.thisObject)
                        if (s == null || s.ended || s != foreground) s = begin(p.thisObject)
                        foreground = s
                        s.interactive = true
                        s.spring = true
                        main.removeCallbacks(heartbeat)
                        main.postDelayed(heartbeat, 75)
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            }
        hooks.add(
            Reflect.findAndHookMethod(
                player,
                "catch",
                RectF::class.java,
                Boolean::class.javaPrimitiveType,
                gesture,
            )
        )
        hooks.add(
            Reflect.findAndHookMethod(
                player,
                "moving",
                RectF::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                gesture,
            )
        )
        val valuePlayer =
            Reflect.findClass(
                "com.honeyspace.transition.anim.floating.animator.ValueAnimPlayer",
                loader,
            )
        val function = Reflect.findClass("kotlin.jvm.functions.Function1", loader)
        hooks.add(
            Reflect.findAndHookMethod(
                valuePlayer,
                "play",
                function,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            if (!enabled || foreground == null) return
                            val calculator = Reflect.getObjectField(p.thisObject!!, "calculator")
                            if (Reflect.callMethod(calculator!!, "getInput") !== foreground!!.input)
                                return
                            foreground!!.duration =
                                ((Reflect.callMethod(calculator!!, "getPositionDuration") as Number)
                                    .toFloat() * ValueAnimator.getDurationScale())
                        } catch (error: Throwable) {
                            fail(error)
                        }
                    }
                },
            )
        )
        overview.install(loader, hooks)
        Hooks.log("GalaxyBar: One UI Home window motion hooks installed")
    }

    private fun fullscreen(player: Any?): Boolean {
        if (Reflect.getBooleanField(player!!, "isMultiTargets")) return false
        val targets = Reflect.getObjectField(player!!, "finalRemoteTargets")
        if (targets == null) return false
        val apps: Array<Any?>? = Reflect.callMethod(targets, "getApps") as Array<Any?>?
        if (apps == null) return false
        for (target in apps) {
            val info = Reflect.getObjectField(target!!, "taskInfo")
            if (info == null || Reflect.getIntField(info, "displayId") != 0) continue
            val configuration = Reflect.getObjectField(target!!, "windowConfiguration")
            if (
                Reflect.callMethod(configuration!!, "getWindowingMode") as Int? == 1 &&
                    Reflect.callMethod(configuration!!, "getActivityType") as Int? == 1
            )
                return true
        }
        return false
    }

    private fun send(frame: MotionFrame) {
        val target: IBinder? = endpoint
        if (target == null) return
        val data = Parcel.obtain()
        try {
            frame.write(data)
            if (!target.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY))
                endpoint = null
        } catch (error: RemoteException) {
            endpoint = null
        } catch (error: RuntimeException) {
            endpoint = null
        } finally {
            data.recycle()
        }
    }

    private fun end(s: LauncherMotion.Session) {
        if (s.ended) return
        s.ended = true
        send(
            MotionFrame(
                s.id,
                SystemClock.uptimeMillis(),
                s.opening,
                0,
                false,
                false,
                0f,
                0f,
                0f,
                0f,
                0f,
                -1f,
                0f,
                true,
            )
        )
    }

    private fun begin(player: Any?): LauncherMotion.Session {
        val previous = sessions.remove(player)
        if (previous != null) end(previous)
        val s = LauncherMotion.Session()
        s.id = SystemClock.elapsedRealtimeNanos()
        s.started = SystemClock.uptimeMillis()
        sessions.put(player, s)
        foreground = s
        return s
    }

    private fun heartbeat() {
        if (!enabled) return
        if (
            !context.getSystemService<PowerManager?>(PowerManager::class.java).isInteractive() ||
                context
                    .getSystemService<KeyguardManager?>(KeyguardManager::class.java)
                    .isKeyguardLocked()
        ) {
            for (s in sessions.values) if (s!!.interactive) end(s!!)
            return
        }
        var again: Boolean = false
        val now = SystemClock.uptimeMillis()
        for (s in sessions.values) {
            if (!s!!.interactive || s!!.ended || s != foreground) continue
            again = true
            if (s!!.last == null || now - s!!.lastSent < 60) continue
            val f = s!!.last
            send(
                MotionFrame(
                    f!!.session,
                    now,
                    f!!.opening,
                    f!!.taskId,
                    true,
                    f!!.overview,
                    f!!.x,
                    f!!.y,
                    f!!.width,
                    f!!.height,
                    f!!.alpha,
                    -1f,
                    f!!.radius,
                    false,
                )
            )
            s!!.lastSent = now
        }
        if (again) main.postDelayed(heartbeat, 75)
    }

    private fun disable() {
        enabled = false
        overview.stop()
        main.removeCallbacks(heartbeat)
        for (s in sessions.values) end(s!!)
        sessions.clear()
        foreground = null
        for (hook in hooks) {
            try {
                hook!!.unhook()
            } catch (error: Throwable) {
                Hooks.log(error)
            }
        }
        hooks.clear()
        protection.disarm()
    }

    private fun fail(error: Throwable?) {
        disable()
        failed = true
        protection.initializationFailed(error)
        Hooks.log("GalaxyBar: launcher motion disabled: " + error)
    }

    private class Session {
        @JvmField var id: Long = 0
        @JvmField var started: Long = 0
        @JvmField var lastSent: Long = 0
        @JvmField var opening: Boolean = false
        @JvmField var spring: Boolean = false
        @JvmField var verified: Boolean = false
        @JvmField var ended: Boolean = false
        @JvmField var interactive: Boolean = false
        @JvmField var duration: Float = 0f
        @JvmField var last: MotionFrame? = null
        @JvmField var input: Any? = null
    }

    companion object {
        private const val PLAYER: String = "com.honeyspace.transition.anim.floating.PlayerImpl"
    }
}
