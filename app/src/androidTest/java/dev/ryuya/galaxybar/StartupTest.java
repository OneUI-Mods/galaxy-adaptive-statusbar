package dev.ryuya.galaxybar;

import static org.junit.Assert.*;
import android.content.*;
import android.content.pm.PackageManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class StartupTest {
  @Test
  public void startupPreservesDisabledSettingsAndCrashProtectionWithoutRoot() throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var config = c.getSharedPreferences("config", 0);
    var before = new java.util.HashMap<>(config.getAll());
    var status = c.getSharedPreferences("guard", 0);
    var protection = new java.util.HashMap<>(status.getAll());
    new BootReceiver().onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
    new BootReceiver().onReceive(c, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
    new BootReceiver().onReceive(c, new Intent("unknown"));
    assertEquals(before, config.getAll());
    assertEquals(protection, status.getAll());
    var receiver =
        c.getPackageManager().getReceiverInfo(new ComponentName(c, BootReceiver.class), 0);
    assertTrue(receiver.directBootAware);
    assertFalse(receiver.exported);
    assertEquals(
        PackageManager.PERMISSION_GRANTED,
        c.checkSelfPermission("android.permission.RECEIVE_BOOT_COMPLETED"));
  }
}
