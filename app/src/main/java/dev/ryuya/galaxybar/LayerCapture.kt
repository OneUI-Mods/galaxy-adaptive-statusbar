package dev.ryuya.galaxybar

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.view.SurfaceControl
import dev.ryuya.galaxybar.compat.DexSymbols
import dev.ryuya.galaxybar.compat.compatibleMethod
import dev.ryuya.galaxybar.compat.compatibleName
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.jvm.JvmStatic

/* Existing WMS/SurfaceFlinger APIs only; no process injection or policy changes. Worker-only. */
class LayerCapture : AutoCloseable {
    private val cap: Class<*>
    private val builder: Class<*>
    private val operations by lazy {
        BuilderOperations(
            builder.getConstructor(SurfaceControl::class.java),
            builder.compatibleMethod("setChildrenOnly", Boolean::class.javaPrimitiveType),
            builder.compatibleMethod("setSourceCrop", Rect::class.java),
            builder.compatibleMethod("setFrameScale", Float::class.javaPrimitiveType),
            builder.compatibleMethod("setExcludeLayers", Array<SurfaceControl>::class.java),
            builder.compatibleMethod("setSecureContentPolicy", Int::class.javaPrimitiveType),
            builder.compatibleMethod("setProtectedContentPolicy", Int::class.javaPrimitiveType),
            builder.compatibleMethod("build"),
        )
    }
    private var captureLayers: Method? = null
    private var mirror: SurfaceControl? = null
    private var display: Int = -1
    var lastCaptureCostMs: Double = 0.0
        private set

    init {
        cap = DexSymbols.findClass("android.window.ScreenCaptureInternal")
        builder = DexSymbols.findClass(cap.compatibleName() + "\$LayerCaptureArgs\$Builder")
    }

    @Throws(Exception::class)
    fun capture(displayId: Int, crop: Rect, exclude: SurfaceControl?): Bitmap? {
        return capture(displayId, crop, arrayOf<SurfaceControl?>(exclude))
    }

    @Throws(Exception::class)
    fun capture(displayId: Int, crop: Rect, excludes: Array<SurfaceControl?>): Bitmap? {
        check(!(excludes.size == 0 || excludes[0] == null || !excludes[0]!!.isValid())) {
            "Missing SystemUI exclusion surface"
        }
        for (surface in excludes) if (surface == null || !surface.isValid())
            throw LayerCapture.UnavailableException()
        if (mirror == null || display != displayId) {
            close()
            mirror = SurfaceControl::class.java.getConstructor().newInstance()
            val wm =
                DexSymbols.findClass("android.view.WindowManagerGlobal")
                    .compatibleMethod("getWindowManagerService")
                    .invoke(null)
            val ok: Boolean =
                DexSymbols.findClass("android.view.IWindowManager")
                    .compatibleMethod(
                        "mirrorDisplay",
                        Int::class.javaPrimitiveType,
                        SurfaceControl::class.java,
                    )
                    .invoke(wm, displayId, mirror) as Boolean
            check(ok) { "mirrorDisplay denied" }
            display = displayId
            // The mirror can be returned before SurfaceFlinger's next snapshot contains it.
            // This one-time wait is worker-only. Subsequent transient failures retain the mirror.
            SystemClock.sleep(50)
        }
        val ops = operations
        val b = ops.constructor.newInstance(mirror)
        ops.childrenOnly.invoke(b, false)
        ops.crop.invoke(b, crop)
        ops.scale.invoke(b, .5f)
        ops.excludes.invoke(b, excludes as Any?)
        ops.secure.invoke(b, 2)
        ops.protectedContent.invoke(b, 2)
        val args = ops.build.invoke(b)
        val capture = captureLayers ?: cap.compatibleMethod("captureLayers", args.javaClass)
            .also { captureLayers = it }
        // Exclude the one-time mirror readiness wait from the compositor-work estimate.
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            val shot = capture.invoke(null, args)
            return LayerCapture.copyCapture(shot, crop)
        } finally {
            lastCaptureCostMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }
    }

    private data class BuilderOperations(
        val constructor: Constructor<*>,
        val childrenOnly: Method,
        val crop: Method,
        val scale: Method,
        val excludes: Method,
        val secure: Method,
        val protectedContent: Method,
        val build: Method,
    )

    class UnavailableException :
        IllegalStateException("Capture unavailable (secure, protected, or surface transition)")

    class ProtectedContentException : SecurityException("Secure layers")

    override fun close() {
        if (mirror != null) {
            mirror!!.release()
            mirror = null
        }
        display = -1
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun copyCapture(shot: Any?, crop: Rect): Bitmap? {
            if (shot == null) throw LayerCapture.UnavailableException()
            val buffer: HardwareBuffer? =
                shot.javaClass.compatibleMethod("getHardwareBuffer").invoke(shot) as HardwareBuffer?
            // A capture result can exist without pixels while a task's surfaces are being replaced.
            // Do not dereference or close a missing buffer, and do not count this as a hook
            // failure.
            if (buffer == null) throw LayerCapture.UnavailableException()
            try {
                if (shot.javaClass.compatibleMethod("containsSecureLayers").invoke(shot) as Boolean)
                    throw LayerCapture.ProtectedContentException()
                val hw: Bitmap? =
                    shot.javaClass.compatibleMethod("asBitmap").invoke(shot) as Bitmap?
                if (hw == null) throw LayerCapture.UnavailableException()
                try {
                    // SurfaceFlinger truncates scaled output dimensions, including odd moving
                    // crops.
                    check(
                        !(hw.getWidth() != (crop.width() * .5f).toInt() ||
                            hw.getHeight() != (crop.height() * .5f).toInt()),
                    ) {
                        "Crop was ignored"
                    }
                    val copy = hw.copy(Config.ARGB_8888, false)
                    if (copy == null) throw LayerCapture.UnavailableException()
                    return copy
                } finally {
                    hw.recycle()
                }
            } finally {
                buffer.close()
            }
        }
    }
}
