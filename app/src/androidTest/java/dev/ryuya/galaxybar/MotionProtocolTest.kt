package dev.ryuya.galaxybar

import android.os.*
import android.os.BadParcelableException
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class MotionProtocolTest {
    @Test
    open fun endpointIsNotExposedToOtherApplications() {
        val resolver =
            InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver()
        assertThrows(
            SecurityException::class.java,
            { resolver.call(ConfigProvider.URI!!, "motion_endpoint", null, null) },
        )
        val attempt = Bundle()
        attempt.putBinder("motion_endpoint", Binder())
        assertThrows(
            SecurityException::class.java,
            { resolver.call(ConfigProvider.URI!!, "status", "forged", attempt) },
        )
        assertThrows(
            SecurityException::class.java,
            { resolver.call(ConfigProvider.URI!!, "guard_status", null, Bundle()) },
        )
    }

    @Test
    open fun geometryProtocolRoundTripsAndRejectsTrailingData() {
        val original =
            MotionFrame(
                100,
                200,
                true,
                42,
                true,
                true,
                10f,
                20f,
                1000f,
                2000f,
                .9f,
                200f,
                50f,
                false,
            )
        val p = Parcel.obtain()
        try {
            original.write(p)
            p.setDataPosition(0)
            assertEquals(original, MotionFrame.read(p))
            p.setDataPosition(p.dataSize())
            p.writeInt(123)
            p.setDataPosition(0)
            assertThrows(BadParcelableException::class.java, { MotionFrame.read(p) })
        } finally {
            p.recycle()
        }
    }
}
