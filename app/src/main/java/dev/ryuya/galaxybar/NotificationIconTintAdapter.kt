package dev.ryuya.galaxybar

import android.view.View
import android.widget.ImageView
import dev.ryuya.galaxybar.compat.compatibleDeclaredField
import dev.ryuya.galaxybar.compat.compatibleDeclaredMethod
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.util.WeakHashMap

/** Notification overflow dots use a separate Paint, outside ImageView's drawable tint. */
internal object NotificationIconTintAdapter {
    private const val OWNER = "com.android.systemui.statusbar.StatusBarIconView"
    private val colors = WeakHashMap<View, IconDecorTint>()
    private var type: Class<*>? = null
    private var painting = false

    fun install(loader: ClassLoader?) {
        // This optional contract must not disable the rest of the status bar on a changed APK.
        try {
            val owner = Reflect.findClass(OWNER, loader)
            check(ImageView::class.java.isAssignableFrom(owner))
            check(owner.compatibleDeclaredField("mDecorColor").type == Int::class.javaPrimitiveType)
            check(
                owner.compatibleDeclaredMethod("getVisibleState").returnType ==
                    Int::class.javaPrimitiveType
            )
            check(
                owner
                    .compatibleDeclaredMethod("setDecorColor", Int::class.javaPrimitiveType)
                    .returnType == Void.TYPE
            )
            HookRegistry.hook(
                owner,
                "setDecorColor",
                Int::class.javaPrimitiveType,
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (painting) return
                        val state = colors[p.thisObject] ?: return
                        val native = p.args.firstOrNull() as? Int ?: return
                        // Record theme changes, but prevent a native-color frame between captures.
                        p.args[0] = state.nativeChanged(native)
                    }
                },
            )
            type = owner
        } catch (error: Exception) {
            type = null
            Hooks.log("GalaxyBar: notification decoration unavailable: $error")
        }
    }

    fun supports(view: View): Boolean = type?.isInstance(view) == true

    fun hidden(view: View): Boolean =
        supports(view) && Reflect.callMethod(view, "getVisibleState") == 2

    fun apply(view: View, color: Int) {
        if (!supports(view)) return
        val state =
            colors.getOrPut(view) { IconDecorTint(Reflect.getIntField(view, "mDecorColor")) }
        val before = state.current()
        val after = state.apply(color)
        if (before != after) paint(view, after)
    }

    fun restore(view: View) {
        val state = colors.remove(view) ?: return
        paint(view, state.restore())
    }

    private fun paint(view: View, color: Int) {
        painting = true
        try {
            Reflect.callMethod(view, "setDecorColor", color)
        } finally {
            painting = false
        }
    }

    fun reset() {
        colors.keys.toList().forEach(::restore)
        type = null
    }
}
