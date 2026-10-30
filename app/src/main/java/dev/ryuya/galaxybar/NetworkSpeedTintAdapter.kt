package dev.ryuya.galaxybar

import android.content.res.ColorStateList
import android.content.res.Resources.NotFoundException
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.TextView
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Reflect
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.abs

/* Colors the two-line native speed label without replacing its frequently updated text. */
object NetworkSpeedTintAdapter {
    private const val OWNER: String = "com.android.systemui.statusbar.policy.NetspeedView"
    private val standard: MutableMap<TextView?, ColorStateList?> =
        WeakHashMap<TextView?, ColorStateList?>()
    private val applied: MutableMap<TextView?, Int?> = WeakHashMap<TextView?, Int?>()

    private val standardSpacing: MutableMap<TextView?, NetworkSpeedTintAdapter.Spacing?> =
        WeakHashMap<TextView?, NetworkSpeedTintAdapter.Spacing?>()
    private var spacingDp: Int = 0

    @JvmStatic
    fun setSpacing(value: Int) {
        val next = NetworkSpeedSpacing.clamp(value)
        if (next == NetworkSpeedTintAdapter.spacingDp) return
        NetworkSpeedTintAdapter.spacingDp = next
        for (view in NetworkSpeedTintAdapter.applied.keys) NetworkSpeedTintAdapter.applySpacing(
            view!!
        )
    }

    @JvmStatic
    private fun applySpacing(view: TextView) {
        val nativeValue =
            NetworkSpeedTintAdapter.standardSpacing.computeIfAbsent(view) { ignored: TextView? ->
                NetworkSpeedTintAdapter.Spacing(
                    view.getLineSpacingExtra(),
                    view.getLineSpacingMultiplier(),
                )
            }
        val extra =
            NetworkSpeedSpacing.extra(
                nativeValue!!.extra,
                view.getResources().getDisplayMetrics().density,
                NetworkSpeedTintAdapter.spacingDp,
            )
        if (
            abs(view.getLineSpacingExtra() - extra) > .01f ||
                view.getLineSpacingMultiplier() != nativeValue!!.multiplier
        )
            view.setLineSpacing(extra, nativeValue!!.multiplier)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun install(loader: ClassLoader?) {
        if (Build.VERSION.SDK_INT != 37) return
        val owner = Reflect.findClass(NetworkSpeedTintAdapter.OWNER, loader)
        owner!!.getDeclaredMethod(
            "onDarkChanged",
            ArrayList::class.java,
            Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        if (
            !TextView::class
                .java
                .isAssignableFrom(owner!!.getDeclaredField("mContentView").getType())
        )
            throw NoSuchFieldException("Netspeed content contract")
        HookRegistry.hook(
            owner,
            "onDarkChanged",
            ArrayList::class.java,
            Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val content = Reflect.getObjectField(p.thisObject!!, "mContentView")
                    if (content !is TextView || !NetworkSpeedTintAdapter.supports(content)) return
                    NetworkSpeedTintAdapter.standard.put(content, content.getTextColors())
                    val adaptive = NetworkSpeedTintAdapter.applied.get(content)
                    if (adaptive != null) NetworkSpeedTintAdapter.paint(content, adaptive)
                }
            },
        )
    }

    @JvmStatic
    fun supports(view: View): Boolean {
        val patternPrefix0 = Build.VERSION.SDK_INT != 37
        val parent = if (!patternPrefix0) view.getParent() as? View else null
        if (patternPrefix0 || parent == null) return false
        val resource: String?
        try {
            resource = view.getResources().getResourceEntryName(view.getId())
        } catch (ignored: NotFoundException) {
            return false
        }
        return StatusBarElements.isNetworkSpeedText(
            Build.VERSION.SDK_INT,
            view.javaClass.getName(),
            parent.javaClass.getName(),
            resource,
        )
    }

    @JvmStatic
    fun apply(view: TextView, color: Int) {
        NetworkSpeedTintAdapter.standard.computeIfAbsent(view) { ignored: TextView? ->
            view.getTextColors()
        }
        NetworkSpeedTintAdapter.applySpacing(view)
        if (NetworkSpeedTintAdapter.applied.get(view) == color) return
        NetworkSpeedTintAdapter.paint(view, color)
        NetworkSpeedTintAdapter.applied.put(view, color)
    }

    @JvmStatic
    private fun paint(view: TextView, color: Int) {
        val baseline = NetworkSpeedTintAdapter.standard.get(view)
        val alpha =
            Color.alpha(
                baseline!!.getColorForState(view.getDrawableState(), baseline!!.getDefaultColor())
            )
        view.setTextColor((color and 0x00ffffff) or (alpha * Color.alpha(color) / 255 shl 24))
    }

    @JvmStatic
    fun restore(view: TextView) {
        NetworkSpeedTintAdapter.applied.remove(view)
        val baseline = NetworkSpeedTintAdapter.standard.get(view)
        if (baseline != null) view.setTextColor(baseline)
        val nativeValue = NetworkSpeedTintAdapter.standardSpacing.remove(view)
        if (nativeValue != null) view.setLineSpacing(nativeValue.extra, nativeValue.multiplier)
    }

    @JvmStatic
    fun reset() {
        for (view in NetworkSpeedTintAdapter.applied.keys) {
            val baseline = NetworkSpeedTintAdapter.standard.get(view)
            if (baseline != null) view!!.setTextColor(baseline)
            val nativeValue = NetworkSpeedTintAdapter.standardSpacing.get(view)
            if (nativeValue != null)
                view!!.setLineSpacing(nativeValue.extra, nativeValue.multiplier)
        }
        NetworkSpeedTintAdapter.standardSpacing.clear()
        NetworkSpeedTintAdapter.applied.clear()
        NetworkSpeedTintAdapter.standard.clear()
    }

    @JvmRecord
    private data class Spacing(val extra: Float, val multiplier: Float) {
        override fun toString(): String = "Spacing[extra=$extra, multiplier=$multiplier]"
    }
}
