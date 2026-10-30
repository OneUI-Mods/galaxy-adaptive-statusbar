package dev.ryuya.galaxybar

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Rect
import android.hardware.HardwareBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.jvm.JvmField
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class LayerCaptureTest {
    class Shot(buffer: HardwareBuffer?, bitmap: Bitmap?, secure: Boolean) {
        @JvmField val buffer: HardwareBuffer?
        @JvmField val bitmap: Bitmap?
        @JvmField val secure: Boolean
        @JvmField var bitmapRequested: Boolean = false

        init {
            this.buffer = buffer
            this.bitmap = bitmap
            this.secure = secure
        }

        fun getHardwareBuffer(): HardwareBuffer? {
            return buffer!!
        }

        fun containsSecureLayers(): Boolean {
            return secure
        }

        fun asBitmap(): Bitmap? {
            bitmapRequested = true
            return bitmap!!
        }
    }

    private fun buffer(): HardwareBuffer? {
        return HardwareBuffer.create(
            8,
            8,
            HardwareBuffer.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
    }

    @Test
    @Throws(Exception::class)
    open fun emptySurfaceResultIsTemporaryAndNextFrameCanRecover() {
        val crop = Rect(100, 401, 117, 418)
        assertThrows(
            LayerCapture.UnavailableException::class.java,
            { LayerCapture.copyCapture(null, crop) },
        )
        val missing = LayerCaptureTest.Shot(null, null, false)
        assertThrows(
            LayerCapture.UnavailableException::class.java,
            { LayerCapture.copyCapture(missing, crop) },
        )
        assertFalse(missing.bitmapRequested)

        buffer().use { buffer ->
            val pixels = Bitmap.createBitmap(8, 8, Config.ARGB_8888)
            pixels.eraseColor(-0xedcbaa)
            val recovered =
                LayerCapture.copyCapture(LayerCaptureTest.Shot(buffer!!, pixels, false), crop)
            try {
                assertTrue(buffer!!.isClosed())
                assertTrue(pixels.isRecycled())
                assertEquals(-0xedcbaa, recovered!!.getPixel(0, 0))
            } finally {
                recovered!!.recycle()
            }
        }
    }

    @Test
    open fun missingBitmapIsTemporaryAndClosesOwnedBuffer() {
        buffer().use { buffer ->
            val missing = LayerCaptureTest.Shot(buffer!!, null, false)
            assertThrows(
                LayerCapture.UnavailableException::class.java,
                { LayerCapture.copyCapture(missing, Rect(0, 0, 16, 16)) },
            )
            assertTrue(buffer!!.isClosed())
        }
    }

    @Test
    open fun secureCaptureStillRejectsPixelsAndClosesBuffer() {
        buffer().use { buffer ->
            val secure = LayerCaptureTest.Shot(buffer!!, null, true)
            assertThrows(
                LayerCapture.ProtectedContentException::class.java,
                { LayerCapture.copyCapture(secure, Rect(0, 0, 16, 16)) },
            )
            assertFalse(secure.bitmapRequested)
            assertTrue(buffer!!.isClosed())
        }
    }
}
