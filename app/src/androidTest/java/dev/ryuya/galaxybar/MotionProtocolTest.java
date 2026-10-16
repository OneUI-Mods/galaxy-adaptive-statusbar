package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MotionProtocolTest {
  @Test
  public void endpointIsNotExposedToOtherApplications() {
    var resolver =
        InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
    assertThrows(
        SecurityException.class,
        () -> resolver.call(ConfigProvider.URI, "motion_endpoint", null, null));
    Bundle attempt = new Bundle();
    attempt.putBinder("motion_endpoint", new Binder());
    assertThrows(
        SecurityException.class,
        () -> resolver.call(ConfigProvider.URI, "status", "forged", attempt));
    assertThrows(
        SecurityException.class,
        () -> resolver.call(ConfigProvider.URI, "guard_status", null, new Bundle()));
  }

  @Test
  public void geometryProtocolRoundTripsAndRejectsTrailingData() {
    MotionFrame original =
        new MotionFrame(100, 200, true, 42, true, true, 10, 20, 1000, 2000, .9f, 200, 50, false);
    Parcel p = Parcel.obtain();
    try {
      original.write(p);
      p.setDataPosition(0);
      assertEquals(original, MotionFrame.read(p));
      p.setDataPosition(p.dataSize());
      p.writeInt(123);
      p.setDataPosition(0);
      assertThrows(BadParcelableException.class, () -> MotionFrame.read(p));
    } finally {
      p.recycle();
    }
  }
}
