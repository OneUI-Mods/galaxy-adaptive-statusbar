package dev.ryuya.galaxybar

import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.Image.Plane
import android.media.ImageReader
import kotlin.jvm.JvmStatic
import kotlin.math.abs

/* Headless GPU regression probe using the production shader. Does not attach to SystemUI. */
object NativeBatteryRenderProbe {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val palette = NativeBatteryPalette()
        val body: Int = if (args.size > 0 && args[0] == "fade") -0x7f7f80 else Color.BLACK
        val text: Int = if (args.size > 0 && args[0] == "fade") -0x7f7f80 else Color.WHITE
        val layer = RenderNode("NativeBatteryProbeLayer")
        layer.setPosition(0, 0, 100, 100)
        var canvas = layer.beginRecording()
        val paint = Paint()
        paint.setColor(Color.WHITE)
        canvas.drawRect(10f, 10f, 30f, 30f, paint)
        paint.setColor(-0xb2b2ae)
        canvas.drawRect(40f, 10f, 60f, 30f, paint)
        paint.setColor(Color.GREEN)
        canvas.drawRect(10f, 40f, 30f, 60f, paint)
        paint.setColor(-0x66000001)
        canvas.drawRect(40f, 40f, 60f, 60f, paint)
        layer.endRecording()
        layer.setRenderEffect(palette.effect(body, text, -0x66000001, -0xb2b2ae))
        val root = RenderNode("NativeBatteryProbeRoot")
        root.setPosition(0, 0, 100, 100)
        canvas = root.beginRecording()
        canvas.drawColor(Color.WHITE)
        canvas.drawRenderNode(layer)
        root.endRecording()
        ImageReader.newInstance(
                100,
                100,
                PixelFormat.RGBA_8888,
                3,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN,
            )
            .use { reader ->
                val renderer = HardwareRenderer()
                renderer.setOpaque(true)
                renderer.setSurface(reader.getSurface())
                renderer.setContentRoot(root)
                val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                var image: Image? = null
                var i: Int = 0
                while (i < 100 && image == null) {
                    Thread.sleep(10)
                    image = reader.acquireLatestImage()
                    i++
                }
                checkNotNull(image) { "No GPU output; status=" + status }
                val plane: Plane = image.getPlanes()[0]
                val bytes = plane.getBuffer()
                var sampleIndex: Int = 0
                val fade: Int = if (body == -0x7f7f80) 128 else 0
                val expected =
                    arrayOf<IntArray>(
                        intArrayOf(fade, fade, fade, 255),
                        intArrayOf(text and 255, text and 255, text and 255, 255),
                        intArrayOf(0, 255, 0, 255),
                        intArrayOf(
                            if (fade == 128) 179 else 102,
                            if (fade == 128) 179 else 102,
                            if (fade == 128) 179 else 102,
                            255,
                        ),
                        intArrayOf(255, 255, 255, 255),
                    )
                for (point in
                    arrayOf<IntArray>(
                        intArrayOf(20, 20),
                        intArrayOf(50, 20),
                        intArrayOf(20, 50),
                        intArrayOf(50, 50),
                        intArrayOf(80, 80),
                    )) {
                    val offset: Int =
                        point[1] * plane.getRowStride() + point[0] * plane.getPixelStride()
                    for (channel in 0..3) if (
                        abs(
                            (bytes.get(offset + channel).toInt() and 255) -
                                expected[sampleIndex][channel]
                        ) > 1
                    )
                        throw AssertionError(
                            "Unexpected GPU output at sample " +
                                sampleIndex +
                                ", channel " +
                                channel
                        )
                    sampleIndex++
                    System.out.printf(
                        "%d,%d=%02x%02x%02x%02x%n",
                        point[0],
                        point[1],
                        bytes.get(offset).toInt() and 255,
                        bytes.get(offset + 1).toInt() and 255,
                        bytes.get(offset + 2).toInt() and 255,
                        bytes.get(offset + 3).toInt() and 255,
                    )
                }
                image.close()
                renderer.destroy()
            }
        println("GPU probe completed")
        System.exit(0)
    }
}
