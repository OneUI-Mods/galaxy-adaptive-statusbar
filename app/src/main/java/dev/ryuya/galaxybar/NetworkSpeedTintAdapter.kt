package dev.ryuya.galaxybar

import android.content.res.ColorStateList
import android.content.res.Resources.NotFoundException
import android.graphics.Color
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.TextView
import dev.ryuya.galaxybar.compat.compatibleDeclaredField
import dev.ryuya.galaxybar.compat.compatibleDeclaredMethod
import dev.ryuya.galaxybar.compat.compatibleName
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.util.ArrayList
import java.util.Observable
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

    internal fun nativeContainers(): List<View> =
        applied.keys
            .mapNotNull { text -> text?.parent as? View }
            .filter { it.javaClass.compatibleName() == OWNER }

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
        owner!!.compatibleDeclaredMethod(
            "onDarkChanged",
            ArrayList::class.java,
            Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        if (
            !TextView::class
                .java
                .isAssignableFrom(owner!!.compatibleDeclaredField("mContentView").getType())
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
        // Width history and the observer were reviewed on this exact One UI build.
        if (Build.VERSION.INCREMENTAL == "S948QOPU1BZI9") {
            runCatching { installStableTextUpdate(owner) }
                .onFailure { Hooks.log("GalaxyBar: native speed update left unchanged: ${it.javaClass.simpleName}") }
        }
    }

    @Suppress("DEPRECATION")
    private fun installStableTextUpdate(owner: Class<*>) {
        val label = owner.compatibleDeclaredField("mContentView").apply { isAccessible = true }
        val contentUpdated = owner.compatibleDeclaredField("mContentUpdated").apply { isAccessible = true }
        val inStatusBar = owner.compatibleDeclaredField("mInStatusBar").apply { isAccessible = true }
        val widthHistory = owner.compatibleDeclaredField("mStableWidthHelper").apply { isAccessible = true }
        val listeners = TextView::class.java.getDeclaredField("mListeners").apply { isAccessible = true }
        check(contentUpdated.type == Boolean::class.javaPrimitiveType &&
            inStatusBar.type == Boolean::class.javaPrimitiveType &&
            Collection::class.java.isAssignableFrom(widthHistory.type))
        HookRegistry.hook(
            owner, "update", Observable::class.java, Any::class.java,
            object : HookCallback() {
                private var reported = false

                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (Looper.myLooper() != Looper.getMainLooper()) return
                    val container = p.thisObject as? View ?: return
                    val value = p.args?.get(1) as? String ?: return
                    val view = label.get(container) as? TextView ?: return
                    if (view.javaClass !== label.type || value != view.text ||
                        !view.isLaidOut || view.isLayoutRequested || container.isLayoutRequested ||
                        view.isTextSelectable || view.filters.isNotEmpty() ||
                        (listeners.get(view) as? Collection<*>)?.isNotEmpty() == true
                    ) return
                    val layout = view.layout ?: return
                    if (layout.lineCount != 2 || layout.getEllipsisCount(0) != 0 ||
                        layout.getEllipsisCount(1) != 0
                    ) return
                    if (inStatusBar.getBoolean(container)) {
                        val widths = widthHistory.get(container) as? Collection<*> ?: return
                        val width = view.measuredWidth
                        if (widths.size != 10 || widths.any { it != width }) return
                    }
                    // A later native measure still receives the same update marker.
                    contentUpdated.setBoolean(container, true)
                    p.setResult(null)
                    if (!reported) {
                        reported = true
                        Hooks.log("GalaxyBar: reused an unchanged native speed label")
                    }
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
            view.javaClass.compatibleName(),
            parent.javaClass.compatibleName(),
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
