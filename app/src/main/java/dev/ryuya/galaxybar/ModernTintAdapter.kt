package dev.ryuya.galaxybar

import android.view.View
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Reflect
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.jvm.JvmStatic

/* Uses the verified One UI binding API, including the Compose stacked-mobile icon. */
object ModernTintAdapter {
    private val standard: MutableMap<View?, IntArray?> = WeakHashMap<View?, IntArray?>()
    private val applied: MutableMap<View?, Int?> = WeakHashMap<View?, Int?>()
    private var applying: Boolean = false
    private var type: Class<*>? = null
    private var dispatcher: Class<*>? = null
    private var dependency: Class<*>? = null

    @JvmStatic
    fun install(loader: ClassLoader?) {
        ModernTintAdapter.type =
            Reflect.findClass(
                "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView",
                loader,
            )
        ModernTintAdapter.dispatcher =
            Reflect.findClass("com.android.systemui.plugins.DarkIconDispatcher", loader)
        ModernTintAdapter.dependency = Reflect.findClass("com.android.systemui.Dependency", loader)
        HookRegistry.hook(
            ModernTintAdapter.type,
            "onDarkChangedWithContrast",
            ArrayList::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (ModernTintAdapter.applying) return
                    val view: View? = p.thisObject as View?
                    val color: Int =
                        Reflect.callStaticMethod(
                            ModernTintAdapter.dispatcher!!,
                            "getTint",
                            p!!.args!![0],
                            view,
                            p!!.args!![1],
                        ) as Int
                    val inverse: Int =
                        Reflect.callStaticMethod(
                            ModernTintAdapter.dispatcher!!,
                            "getInverseTint",
                            p!!.args!![0],
                            view,
                            p!!.args!![2],
                        ) as Int
                    ModernTintAdapter.standard.put(view, intArrayOf(color, inverse))
                    // The dispatcher updates the binding directly, bypassing the static-color
                    // setter.
                    // Keep its new baseline, then restore our current paint before the frame is
                    // drawn.
                    val adaptive = ModernTintAdapter.applied.get(view)
                    if (adaptive != null) ModernTintAdapter.paint(view, adaptive)
                }
            },
        )
        HookRegistry.hook(
            ModernTintAdapter.type,
            "setStaticDrawableColor",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!ModernTintAdapter.applying) {
                        val view: View? = p.thisObject as View?
                        ModernTintAdapter.standard.put(
                            view,
                            intArrayOf(p!!.args!![0] as Int, p!!.args!![1] as Int),
                        )
                        val adaptive = ModernTintAdapter.applied.get(view)
                        if (adaptive != null) {
                            p!!.args!![0] = adaptive
                            p!!.args!![1] = adaptive xor 0x00ffffff
                        }
                    }
                }
            },
        )
    }

    @JvmStatic
    fun supports(view: View?): Boolean {
        return ModernTintAdapter.type != null && ModernTintAdapter.type!!.isInstance(view)
    }

    @JvmStatic
    fun canRestore(view: View?): Boolean {
        if (!ModernTintAdapter.standard.containsKey(view)) {
            // Hooks were absent while OFF. Ask the verified native dispatcher for today's baseline,
            // rather than reusing a tint from before the user switched apps or changed the theme.
            val owner = Reflect.getStaticObjectField(ModernTintAdapter.dependency!!, "sDependency")
            if (owner != null) {
                val source =
                    Reflect.callMethod(owner, "getDependencyInner", ModernTintAdapter.dispatcher)
                val receivers: MutableMap<*, *>? =
                    Reflect.getObjectField(source!!, "mReceivers") as MutableMap<*, *>?
                if (receivers!!.containsKey(view)) Reflect.callMethod(source!!, "applyDark", view)
            }
        }
        return ModernTintAdapter.standard.containsKey(view)
    }

    @JvmStatic
    fun reset() {
        ModernTintAdapter.standard.clear()
        ModernTintAdapter.applied.clear()
        ModernTintAdapter.type = null
    }

    @JvmStatic
    fun apply(view: View?, color: Int) {
        if (!ModernTintAdapter.canRestore(view) || ModernTintAdapter.applied.get(view) == color)
            return
        ModernTintAdapter.paint(view, color)
        ModernTintAdapter.applied.put(view, color)
    }

    @JvmStatic
    private fun paint(view: View?, color: Int) {
        ModernTintAdapter.applying = true
        try {
            Reflect.callMethod(view!!, "setStaticDrawableColor", color, color xor 0x00ffffff)
            Reflect.callMethod(view!!, "setDecorColor", color)
        } finally {
            ModernTintAdapter.applying = false
        }
    }

    @JvmStatic
    fun restore(view: View?) {
        ModernTintAdapter.applied.remove(view)
        val colors = ModernTintAdapter.standard.get(view)
        if (colors == null) return
        ModernTintAdapter.applying = true
        try {
            Reflect.callMethod(view!!, "setStaticDrawableColor", colors[0], colors[1])
            Reflect.callMethod(view!!, "setDecorColor", colors[0])
        } finally {
            ModernTintAdapter.applying = false
        }
    }
}
