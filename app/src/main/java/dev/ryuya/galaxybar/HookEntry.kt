package dev.ryuya.galaxybar

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.ImageView
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.HookCallback.Unhook
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import io.github.libxposed.api.XposedModule
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

    private fun loadPackage(packageName: String, classLoader: ClassLoader?) {
        val launcher: Boolean = packageName == "com.sec.android.app.launcher"
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
        private var engine: Engine? = null
        private var protection: CrashGuard? = null
        private var initialized: Boolean = false
        private var systemLoader: ClassLoader? = null
        private var launcherMotion: LauncherMotion? = null

        @JvmStatic
        private fun initialize(context: Context, loader: ClassLoader?) {
            if (HookEntry.initialized) return
            HookEntry.initialized = true
            if (context.getPackageName() == "com.sec.android.app.launcher") {
                try {
                    HookEntry.protection = CrashGuard.open(context, false)
                    if (HookEntry.protection!!.allowsHooks())
                        HookEntry.launcherMotion =
                            LauncherMotion(context, loader!!, HookEntry.protection!!)
                } catch (error: Throwable) {
                    if (HookEntry.protection != null)
                        HookEntry.protection!!.initializationFailed(error)
                    else CrashGuard.failClosed(context, error)
                    Hooks.log("GalaxyBar: launcher motion unavailable: " + error)
                }
                return
            }
            try {
                HookEntry.protection = CrashGuard.open(context, false)
                if (!HookEntry.protection!!.allowsHooks()) return
                ReloadSession.install(context, loader)
                HookEntry.systemLoader = loader
                HookEntry.installHooks(loader)
            } catch (error: Throwable) {
                HookEntry.stopHooks(error)
                if (HookEntry.protection == null) CrashGuard.failClosed(context, error)
            }
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
                            val view: View? = p.thisObject as View?
                            val root = view!!.getRootView()
                            if (
                                root.getHeight() > 0 &&
                                    root.getHeight() <
                                        root.getResources().getDisplayMetrics().heightPixels / 4
                            ) {
                                if (HookEntry.engine != null && !HookEntry.engine!!.isAttached()) {
                                    HookEntry.engine!!.close()
                                    HookEntry.engine = null
                                }
                                if (HookEntry.engine == null) HookEntry.engine = Engine(root)
                            }
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
            ModernTintAdapter.reset()
            NetworkSpeedTintAdapter.reset()
            BatteryAdapter.reset()
            if (HookEntry.protection != null) HookEntry.protection!!.disarm()
            if (hadHooks) Hooks.log("GalaxyBar: color hooks disabled count=0")
        }

        @JvmStatic
        private fun installAttachmentHook(clock: Class<*>?) {
            HookRegistry.lifecycle(
                clock,
                "onAttachedToWindow",
                object : HookCallback() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val v: View? = p.thisObject as View?
                            val listener: OnGlobalLayoutListener =
                                object : OnGlobalLayoutListener {
                                    override fun onGlobalLayout() {
                                        try {
                                            val root = v!!.getRootView()
                                            if (root.getHeight() == 0) return
                                            if (
                                                root.getHeight() <
                                                    root
                                                        .getResources()
                                                        .getDisplayMetrics()
                                                        .heightPixels / 4
                                            ) {
                                                if (
                                                    HookEntry.engine != null &&
                                                        !HookEntry.engine!!.isAttached()
                                                ) {
                                                    HookEntry.engine!!.close()
                                                    HookEntry.engine = null
                                                }
                                                if (HookEntry.engine == null)
                                                    HookEntry.engine = Engine(root)
                                                v!!
                                                    .getViewTreeObserver()
                                                    .removeOnGlobalLayoutListener(this)
                                            }
                                        } catch (e: Throwable) {
                                            HookEntry.stopHooks(e)
                                            v!!
                                                .getViewTreeObserver()
                                                .removeOnGlobalLayoutListener(this)
                                        }
                                    }
                                }
                            v!!.getViewTreeObserver().addOnGlobalLayoutListener(listener)
                            v!!.post(Runnable { listener.onGlobalLayout() })
                            v!!.addOnAttachStateChangeListener(
                                object : OnAttachStateChangeListener {
                                    override fun onViewAttachedToWindow(ignored: View) {}

                                    override fun onViewDetachedFromWindow(ignored: View) {
                                        if (v!!.getViewTreeObserver().isAlive())
                                            v!!
                                                .getViewTreeObserver()
                                                .removeOnGlobalLayoutListener(listener)
                                        if (
                                            HookEntry.engine != null &&
                                                !HookEntry.engine!!.isAttached()
                                        ) {
                                            HookEntry.engine!!.close()
                                            HookEntry.engine = null
                                        }
                                        v!!.removeOnAttachStateChangeListener(this)
                                    }
                                }
                            )
                        } catch (e: Throwable) {
                            HookEntry.stopHooks(e)
                        }
                    }
                },
            )
        }
    }
}
