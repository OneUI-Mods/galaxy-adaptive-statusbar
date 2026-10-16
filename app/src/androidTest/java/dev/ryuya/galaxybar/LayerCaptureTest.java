package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LayerCaptureTest {
  public static final class Shot {
    final HardwareBuffer buffer;
    final Bitmap bitmap;
    final boolean secure;
    boolean bitmapRequested;

    Shot(HardwareBuffer buffer, Bitmap bitmap, boolean secure) {
      this.buffer = buffer;
      this.bitmap = bitmap;
      this.secure = secure;
    }

    public HardwareBuffer getHardwareBuffer() {
      return buffer;
    }

    public boolean containsSecureLayers() {
      return secure;
    }

    public Bitmap asBitmap() {
      bitmapRequested = true;
      return bitmap;
    }
  }

  private HardwareBuffer buffer() {
    return HardwareBuffer.create(
        8, 8, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
  }

  @Test
  public void emptySurfaceResultIsTemporaryAndNextFrameCanRecover() throws Exception {
    Rect crop = new Rect(100, 401, 117, 418);
    assertThrows(
        LayerCapture.UnavailableException.class, () -> LayerCapture.copyCapture(null, crop));
    Shot missing = new Shot(null, null, false);
    assertThrows(
        LayerCapture.UnavailableException.class, () -> LayerCapture.copyCapture(missing, crop));
    assertFalse(missing.bitmapRequested);

    try (HardwareBuffer buffer = buffer()) {
      Bitmap pixels = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
      pixels.eraseColor(0xff123456);
      Bitmap recovered = LayerCapture.copyCapture(new Shot(buffer, pixels, false), crop);
      try {
        assertTrue(buffer.isClosed());
        assertTrue(pixels.isRecycled());
        assertEquals(0xff123456, recovered.getPixel(0, 0));
      } finally {
        recovered.recycle();
      }
    }
  }

  @Test
  public void missingBitmapIsTemporaryAndClosesOwnedBuffer() {
    try (HardwareBuffer buffer = buffer()) {
      Shot missing = new Shot(buffer, null, false);
      assertThrows(
          LayerCapture.UnavailableException.class,
          () -> LayerCapture.copyCapture(missing, new Rect(0, 0, 16, 16)));
      assertTrue(buffer.isClosed());
    }
  }

  @Test
  public void secureCaptureStillRejectsPixelsAndClosesBuffer() {
    try (HardwareBuffer buffer = buffer()) {
      Shot secure = new Shot(buffer, null, true);
      assertThrows(
          SecurityException.class, () -> LayerCapture.copyCapture(secure, new Rect(0, 0, 16, 16)));
      assertFalse(secure.bitmapRequested);
      assertTrue(buffer.isClosed());
    }
  }
}
