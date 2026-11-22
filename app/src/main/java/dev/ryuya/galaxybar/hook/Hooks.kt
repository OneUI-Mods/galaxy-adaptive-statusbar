package dev.ryuya.galaxybar.hook

import dev.ryuya.galaxybar.compat.compatibleName
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Hooks belong to the currently attached API 102 framework instance. */
object Hooks {
    @Volatile private var framework: XposedInterface? = null
    private val ids = AtomicLong()
    private val calls = ReentrantReadWriteLock()
    @Volatile private var retiresInPlace = false
    @Volatile private var retired = false

    fun enableRetirement() { retiresInPlace = true }

    fun stopDispatch(save: () -> Unit): Boolean {
        val lock = calls.writeLock()
        if (!lock.tryLock()) return false
        try {
            save()
            retired = true
            return true
        } finally {
            lock.unlock()
        }
    }

    @JvmStatic
    fun attach(api: XposedInterface) {
        check(api.apiVersion >= 102) { "libxposed API 102 required" }
        framework = api
        api.log(4, "GalaxyModule", "libxposed API 102 attached")
    }

    @kotlin.jvm.JvmStatic
    private fun api(): XposedInterface =
        checkNotNull(framework) { "Modern hook framework not attached" }

    @JvmStatic
    fun hook(method: Executable, callback: HookCallback): HookCallback.Unhook =
        HookCallback.Unhook(
            api()
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .setId("galaxy-${ids.incrementAndGet()}")
                .intercept { chain ->
                    if (!retiresInPlace) callback.intercept(chain)
                    else if (retired) chain.proceed()
                    else {
                        val lock = calls.readLock()
                        lock.lock()
                        try {
                            if (retired) chain.proceed() else callback.intercept(chain)
                        } finally {
                            lock.unlock()
                        }
                    }
                }
        )

    @JvmStatic
    fun hookAllMethods(
        type: Class<*>,
        name: String?,
        callback: HookCallback,
    ): MutableSet<HookCallback.Unhook> {
        val installed = linkedSetOf<HookCallback.Unhook>()
        try {
            for (method in type.declaredMethods) if (method.compatibleName() == name)
                installed += hook(method, callback)
            return installed
        } catch (error: Throwable) {
            installed.forEach { it.unhook() }
            throw error
        }
    }

    @JvmStatic
    fun hookAllConstructors(
        type: Class<*>,
        callback: HookCallback,
    ): MutableSet<HookCallback.Unhook> {
        val installed = linkedSetOf<HookCallback.Unhook>()
        try {
            for (constructor in type.declaredConstructors) installed += hook(constructor, callback)
            return installed
        } catch (error: Throwable) {
            installed.forEach { it.unhook() }
            throw error
        }
    }

    @JvmStatic
    fun deoptimize(method: Executable) {
        check(api().deoptimize(method)) { "Could not deoptimize $method" }
    }

    @JvmStatic
    fun log(message: String) {
        framework?.log(4, "GalaxyModule", message)
    }

    @JvmStatic
    fun log(error: Throwable) {
        framework?.log(6, "GalaxyModule", error.toString(), error)
    }
}
