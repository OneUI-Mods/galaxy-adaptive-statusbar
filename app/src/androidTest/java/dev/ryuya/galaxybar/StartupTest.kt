package dev.ryuya.galaxybar

import android.content.*
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import java.util.HashMap
import org.junit.Assert.*
import org.junit.Test

open class StartupTest {
    @Test
    @Throws(Exception::class)
    open fun startupPreservesDisabledSettingsAndCrashProtectionWithoutRoot() {
        val c = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val config = c.getSharedPreferences("config", 0)
        val before = HashMap<String?, Any?>(config.getAll())
        val status = c.getSharedPreferences("guard", 0)
        val protection = HashMap<String?, Any?>(status.getAll())
        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(c, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        BootReceiver().onReceive(c, Intent("unknown"))
        assertEquals(before, config.getAll())
        assertEquals(protection, status.getAll())
        val receiver =
            c.getPackageManager().getReceiverInfo(ComponentName(c, BootReceiver::class.java), 0)
        assertTrue(receiver.directBootAware)
        assertFalse(receiver.exported)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            c.checkSelfPermission("android.permission.RECEIVE_BOOT_COMPLETED"),
        )
    }
}
