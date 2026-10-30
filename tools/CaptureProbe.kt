import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Bitmap.Config
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.Process
import java.io.FileOutputStream
import kotlin.jvm.JvmStatic
import kotlin.math.min

/* Read-only app_process probe. Never captures secure or protected content. */
object CaptureProbe {
    @Throws(Exception::class)
    @JvmStatic
    fun main(argv: Array<String>) {
        if (argv.size > 3) {
            val result: Int =
                Process::class
                    .java
                    .getMethod("setUid", Int::class.javaPrimitiveType)
                    .invoke(null, argv[3].toInt()) as Int
            println("setUid=" + result + " uid=" + Process.myUid())
        }
        val sc = Class.forName("android.view.SurfaceControl")
        val ids: LongArray? = sc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray?
        val token: IBinder? =
            sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                .invoke(null, ids!![0]) as IBinder?
        val cap = Class.forName("android.window.ScreenCaptureInternal")
        val layers: Boolean = argv.size > 1 && argv[1] == "layers"
        var mirror: Any? = null
        if (layers) {
            mirror = sc.getConstructor().newInstance()
            val wm =
                Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService")
                    .invoke(null)
            println(
                "mirror=" +
                    Class.forName("android.view.IWindowManager")
                        .getMethod("mirrorDisplay", Int::class.javaPrimitiveType, sc)
                        .invoke(wm, 0, mirror)
            )
            if (argv.size > 2) Thread.sleep(min(1000, argv[2].toInt()).toLong())
        }
        val builder =
            Class.forName(
                cap.getName() +
                    (if (layers) "\$LayerCaptureArgs\$Builder" else "\$DisplayCaptureArgs\$Builder")
            )
        val b: Any? =
            if (layers) builder.getConstructor(sc).newInstance(mirror)
            else builder.getConstructor(IBinder::class.java).newInstance(token)
        if (layers)
            builder.getMethod("setChildrenOnly", Boolean::class.javaPrimitiveType).invoke(b, false)
        builder.getMethod("setSourceCrop", Rect::class.java).invoke(b, Rect(0, 0, 1440, 140))
        builder.getMethod("setFrameScale", Float::class.javaPrimitiveType).invoke(b, 0.5f)
        // Fail closed if a secure/protected layer is encountered.
        builder.getMethod("setSecureContentPolicy", Int::class.javaPrimitiveType).invoke(b, 2)
        builder.getMethod("setProtectedContentPolicy", Int::class.javaPrimitiveType).invoke(b, 2)
        val args = builder.getMethod("build").invoke(b)
        val start = System.nanoTime()
        val shot =
            cap.getMethod(if (layers) "captureLayers" else "captureDisplay", args.javaClass)
                .invoke(null, args)
        if (shot == null) {
            println("capture returned null")
            return
        }
        val buffer: HardwareBuffer? =
            shot.javaClass.getMethod("getHardwareBuffer").invoke(shot) as HardwareBuffer?
        try {
            val hardware: Bitmap? = shot.javaClass.getMethod("asBitmap").invoke(shot) as Bitmap?
            val cpu = hardware!!.copy(Config.ARGB_8888, false)
            hardware!!.recycle()
            println(
                ("capture+readback_ms=" +
                    (System.nanoTime() - start) / 1e6 +
                    " size=" +
                    cpu.getWidth() +
                    "x" +
                    cpu.getHeight())
            )
            if (argv[0] != "-")
                FileOutputStream(argv[0]).use { out ->
                    cpu.compress(CompressFormat.PNG, 100, out)
                }
            cpu.recycle()
        } finally {
            buffer!!.close()
            if (mirror != null) sc.getMethod("release").invoke(mirror)
        }
    }
}
