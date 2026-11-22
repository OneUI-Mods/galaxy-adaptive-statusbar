package dev.ryuya.galaxybar

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.ImageView
import dev.ryuya.galaxybar.compat.DexSymbols
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.HookCallback.Unhook
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import dev.ryuya.galaxybar.startup.FirstUnlockGate
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlin.jvm.JvmStatic
import kotlin.math.log

class HookEntry : XposedModule() {
    private var processName: String? = null

    override fun onModuleLoaded(p: ModuleLoadedParam) {
        Hooks.attach(this)
        processName = p.getProcessName()
    }

    override fun onPackageReady(p: PackageReadyParam) {
        if (p.isFirstPackage()) loadPackage(p.getPackageName(), p.getClassLoader())
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (processName == LAUNCHER) return retireLauncher(param)
        if (processName != SYSTEM_UI) return false
        return MainThreadReload.run(main) handOff@{
            val context = systemHost
            val loader = systemLoader
            if (context == null || loader == null) {
                val fallback = reconnectState ?: return@handOff false
                param.setSavedInstanceState(fallback)
                removeSystemUiHooks()
                reconnectState = null
                return@handOff true
            }
            if (engine?.readyForHotReload() == false ||
                protection?.readyForHotReload() == false ||
                !ReloadSession.readyForHotReload()
            ) return@handOff false
            if (!Hooks.stopDispatch {
                    param.setSavedInstanceState(arrayOf(context, loader, WeakReference(engine?.liveRoot())))
                }) return@handOff false
            removeSystemUiHooks()
            true
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        if (param.processName == LAUNCHER) {
            reloadLauncher(param)
            return
        }
        check(param.processName == SYSTEM_UI)
        val state = requireNotNull(param.savedInstanceState as? Array<*>)
        val context = requireNotNull(state.getOrNull(0) as? Context)
        val loader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        val root = (state.getOrNull(2) as? WeakReference<*>)?.get() as? View
        check(context.packageName == SYSTEM_UI)
        onModuleLoaded(param)
        reconnectState = state
        check(MainThreadReload.run(main) {
            check(DexSymbols.canInstallNow(context)) { "SystemUI discovery is still preparing" }
            check(installSystemUi(context, loader, root?.takeIf(View::isAttachedToWindow))) {
                "Adaptive Bar startup protection is active"
            }
            param.oldHookHandles.forEach { it.unhook() }
            reconnectState = null
            Hooks.log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
            true
        }) { "SystemUI main thread was unavailable" }
    }

    private fun retireLauncher(param: HotReloadingParam): Boolean =
        MainThreadReload.run(main) {
            val motion = launcherMotion ?: return@run false
            val context = launcherHost ?: return@run false
            val loader = launcherLoader ?: return@run false
            if (!motion.readyForHotReload() || protection?.readyForHotReload() != true) {
                return@run false
            }
            if (!Hooks.stopDispatch {
                    val (wanted, endpoint) = motion.currentConfiguration()
                    param.setSavedInstanceState(arrayOf(context, loader, wanted, endpoint))
                }) return@run false
            motion.close()
            launcherMotion = null
            launcherHost = null
            launcherLoader = null
            DexSymbols.detachObserver()
            protection?.retire()
            protection = null
            true
        }

    private fun reloadLauncher(param: HotReloadedParam) {
        val state = requireNotNull(param.savedInstanceState as? Array<*>)
        val context = requireNotNull(state.getOrNull(0) as? Context)
        val loader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        val wanted = requireNotNull(state.getOrNull(2) as? Boolean)
        val endpoint = state.getOrNull(3) as? IBinder
        check(context.packageName == LAUNCHER)
        onModuleLoaded(param)
        check(MainThreadReload.run(main) {
            check(installLauncher(context, loader, Pair(wanted, endpoint))) {
                "Launcher startup protection is active"
            }
            param.oldHookHandles.forEach { it.unhook() }
            Hooks.log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
            true
        }) { "Launcher main thread was unavailable" }
    }

    private fun loadPackage(packageName: String, classLoader: ClassLoader?) {
        val launcher: Boolean = packageName == LAUNCHER
        if ((!launcher && packageName != "com.android.systemui") || processName != packageName)
            return
        // Deliberate allowlist: other firmware requires inspection before enabling hooks.
        if (!HookCompatibility.isCurrentSupported()) {
            Hooks.log("GalaxyBar: unsupported firmware " + Build.DISPLAY)
            return
        }
        val bootstrap = arrayOfNulls<Unhook>(1)
        bootstrap[0] =
            Reflect.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val context: Context? = p!!.args!![0] as Context?
                        if (packageName != context!!.getPackageName()) return
                        if (bootstrap[0] != null) bootstrap[0]!!.unhook()
                        HookEntry.initialize(context!!, classLoader)
                    }
                },
            )
    }

    companion object {
        private const val LAUNCHER = "com.sec.android.app.launcher"
        private const val SYSTEM_UI = "com.android.systemui"
        private val main = Handler(Looper.getMainLooper())
        private val clockWatches = WeakHashMap<View, ClockWatch>()
        @Volatile private var reconnectState: Array<*>? = null
        private var systemHost: Context? = null
        private var engine: Engine? = null
        private var protection: CrashGuard? = null
        private var initialized: Boolean = false
        private val firstUnlock = FirstUnlockGate()
        private var systemLoader: ClassLoader? = null
        private var launcherMotion: LauncherMotion? = null
        private var launcherHost: Context? = null
        private var launcherLoader: ClassLoader? = null

        @JvmStatic
        private fun initialize(context: Context, loader: ClassLoader?) {
            if (HookEntry.initialized) return
            HookEntry.initialized = true
            firstUnlock.run(context) {
                DexSymbols.whenPrepared(context) { installPrepared(context, loader) }
            }
        }

        private fun installPrepared(context: Context, loader: ClassLoader?) {
            if (context.packageName == LAUNCHER) {
                try {
                    installLauncher(context, requireNotNull(loader))
                } catch (error: Throwable) {
                    if (HookEntry.protection != null)
                        HookEntry.protection!!.initializationFailed(error)
                    else CrashGuard.failClosed(context, error)
                    Hooks.log("GalaxyBar: launcher motion unavailable: " + error)
                }
                return
            }
            try {
                installSystemUi(context, requireNotNull(loader))
            } catch (error: Throwable) {
                HookEntry.stopHooks(error)
                if (HookEntry.protection == null) CrashGuard.failClosed(context, error)
            }
        }

        private fun installLauncher(
            context: Context,
            loader: ClassLoader,
            configuration: Pair<Boolean, IBinder?>? = null,
        ): Boolean {
            val guard = CrashGuard.open(context, false)
            protection = guard
            if (!guard.allowsHooks()) return false
            Hooks.enableRetirement()
            launcherMotion = LauncherMotion(context, loader, guard, configuration)
            launcherHost = context
            launcherLoader = loader
            return true
        }

        private fun installSystemUi(context: Context, loader: ClassLoader, root: View? = null): Boolean {
            protection = CrashGuard.open(context, false)
            if (protection?.allowsHooks() != true) return false
            Hooks.enableRetirement()
            systemLoader = loader
            ReloadSession.install(context, loader)
            installHooks(loader)
            if (root != null) attachRoot(root)
            systemHost = context
            return true
        }

        private fun removeSystemUiHooks() {
            main.removeCallbacksAndMessages(null)
            for ((view, watch) in clockWatches.entries.toList()) watch.detach(view)
            clockWatches.clear()
            runCatching { engine?.closeForHotReload() }.onFailure(Hooks::log)
            engine = null
            disableColors()
            HookRegistry.clear()
            ReloadSession.reset()
            DexSymbols.detachObserver()
            protection?.retire()
            protection = null
            systemHost = null
            systemLoader = null
        }

        @JvmStatic
        fun stopHooks(error: Throwable?) {
            ReloadSession.reset()
            if (HookEntry.engine != null) {
                try {
                    HookEntry.engine!!.close()
                } catch (ignored: Throwable) {}
                HookEntry.engine = null
            }
            HookEntry.disableColors()
            HookRegistry.clear()
            if (HookEntry.protection != null) HookEntry.protection!!.initializationFailed(error)
            Hooks.log(error!!)
        }

        @JvmStatic
        @Throws(Throwable::class)
        private fun installHooks(loader: ClassLoader?) {
            NativeBatteryView.installObservation(loader)
            val clock =
                Reflect.findClass(
                    "com.android.systemui.statusbar.policy.QSClockIndicatorView",
                    loader,
                )
            HookRegistry.lifecycle(
                clock,
                "onLayout",
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val view = p.thisObject as? View ?: return
                            attachRoot(view.rootView)
                        } catch (error: Throwable) {
                            HookEntry.stopHooks(error)
                        }
                    }
                },
            )
            HookEntry.installAttachmentHook(clock)
            Hooks.log("GalaxyBar: QSClockIndicatorView lifecycle hook installed " + Build.DISPLAY)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun enableColors() {
            if (HookRegistry.colorCount() != 0) return
            if (HookEntry.protection != null) HookEntry.protection!!.arm()
            ModernTintAdapter.install(HookEntry.systemLoader)
            NotificationIconTintAdapter.install(HookEntry.systemLoader)
            NetworkSpeedTintAdapter.install(HookEntry.systemLoader)
            PrivacySlide.install(HookEntry.systemLoader)
            ChargingMotion.install(HookEntry.systemLoader)
            BatteryAdapter.install(HookEntry.systemLoader)
            HookRegistry.hook(
                ImageView::class.java,
                "setColorFilter",
                ColorFilter::class.java,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (HookEntry.engine != null)
                            p!!.args!![0] =
                                HookEntry.engine!!.standardFilter(
                                    p.thisObject as ImageView?,
                                    p!!.args!![0] as ColorFilter?,
                                )
                    }
                },
            )
            val tintChange: HookCallback =
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (HookEntry.engine != null)
                            HookEntry.engine!!.standardDrawable(p.thisObject as ImageView)
                    }
                }
            HookRegistry.hook(
                ImageView::class.java,
                "setImageTintList",
                ColorStateList::class.java,
                tintChange,
            )
            HookRegistry.hook(
                ImageView::class.java,
                "setImageDrawable",
                Drawable::class.java,
                tintChange,
            )
            Hooks.log("GalaxyBar: color hooks enabled count=" + HookRegistry.colorCount())
        }

        @JvmStatic
        fun disableColors() {
            val hadHooks: Boolean = HookRegistry.colorCount() != 0
            PrivacySlide.reset()
            ChargingMotion.reset()
            HookRegistry.clearColors()
            NotificationIconTintAdapter.reset()
            ModernTintAdapter.reset()
            NetworkSpeedTintAdapter.reset()
            BatteryAdapter.reset()
            if (HookEntry.protection != null) HookEntry.protection!!.disarm()
            if (hadHooks) Hooks.log("GalaxyBar: color hooks disabled count=0")
        }

        private fun attachRoot(root: View): Boolean {
            if (root.height <= 0 || root.height >= root.resources.displayMetrics.heightPixels / 4) {
                return false
            }
            if (engine?.isAttached() == false) {
                engine?.close()
                engine = null
            }
            if (engine == null) engine = Engine(root)
            engine?.availabilityChanged()
            return true
        }

        private fun installAttachmentHook(clock: Class<*>?) {
            HookRegistry.lifecycle(clock, "onAttachedToWindow", object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val view = p.thisObject as? View ?: return
                    if (clockWatches.containsKey(view)) return
                    val watch = ClockWatch(view)
                    clockWatches[view] = watch
                    view.viewTreeObserver.addOnGlobalLayoutListener(watch)
                    view.addOnAttachStateChangeListener(watch)
                    main.post { watch.onGlobalLayout() }
                }
            })
        }

        private class ClockWatch(view: View) : OnGlobalLayoutListener, OnAttachStateChangeListener {
            private val view = WeakReference(view)
            private var detached = false

            override fun onGlobalLayout() {
                val owner = view.get() ?: return
                if (detached) return
                try {
                    if (attachRoot(owner.rootView)) owner.viewTreeObserver.removeOnGlobalLayoutListener(this)
                } catch (error: Throwable) {
                    detach(owner)
                    stopHooks(error)
                }
            }

            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                detach(view)
                if (engine?.isAttached() == false) {
                    engine?.close()
                    engine = null
                }
            }

            fun detach(view: View) {
                detached = true
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                view.removeOnAttachStateChangeListener(this)
                clockWatches.remove(view)
            }
        }
    }
}
