package dev.ryuya.galaxybar

import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.Unhook
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.util.ArrayList
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Own hooks only. Never removes hooks belonging to another module. */
object HookRegistry {
    private val hooks: MutableList<Unhook?> = ArrayList<Unhook?>()
    private val lifecycle: MutableList<Unhook?> = ArrayList<Unhook?>()

    @JvmStatic
    fun hook(owner: Class<*>?, name: String?, vararg parameters: Any?) {
        HookRegistry.hooks.add(Reflect.findAndHookMethod(owner!!, name!!, *parameters))
    }

    @JvmStatic
    fun lifecycle(owner: Class<*>?, name: String?, callback: HookCallback?) {
        HookRegistry.lifecycle.addAll(Hooks.hookAllMethods(owner!!, name, callback!!))
    }

    @JvmStatic
    fun colorCount(): Int {
        return HookRegistry.hooks.size
    }

    @JvmStatic
    private fun clear(handles: MutableList<Unhook?>) {
        for (hook in handles) {
            try {
                hook!!.unhook()
            } catch (error: Throwable) {
                Hooks.log(error)
            }
        }
        handles.clear()
    }

    @JvmStatic
    fun clearColors() {
        HookRegistry.clear(HookRegistry.hooks)
    }

    @JvmStatic
    fun clear() {
        HookRegistry.clearColors()
        HookRegistry.clear(HookRegistry.lifecycle)
    }
}
