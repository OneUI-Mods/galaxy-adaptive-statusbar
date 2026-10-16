package dev.ryuya.galaxybar;

import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.view.SurfaceControl;
import java.lang.reflect.*;

/** Existing WMS/SurfaceFlinger APIs only; no process injection or policy changes. Worker-only. */
final class LayerCapture implements AutoCloseable {
  private final Class<?> cap, builder;
  private SurfaceControl mirror;
  private int display = -1;

  LayerCapture() throws Exception {
    cap = Class.forName("android.window.ScreenCaptureInternal");
    builder = Class.forName(cap.getName() + "$LayerCaptureArgs$Builder");
  }

  Bitmap capture(int displayId, Rect crop, SurfaceControl exclude) throws Exception {
    return capture(displayId, crop, new SurfaceControl[] {exclude});
  }

  Bitmap capture(int displayId, Rect crop, SurfaceControl[] excludes) throws Exception {
    if (excludes.length == 0 || excludes[0] == null || !excludes[0].isValid())
      throw new IllegalStateException("Missing SystemUI exclusion surface");
    for (SurfaceControl surface : excludes)
      if (surface == null || !surface.isValid()) throw new UnavailableException();
    if (mirror == null || display != displayId) {
      close();
      mirror = SurfaceControl.class.getConstructor().newInstance();
      Object wm =
          Class.forName("android.view.WindowManagerGlobal")
              .getMethod("getWindowManagerService")
              .invoke(null);
      boolean ok =
          (Boolean)
              Class.forName("android.view.IWindowManager")
                  .getMethod("mirrorDisplay", int.class, SurfaceControl.class)
                  .invoke(wm, displayId, mirror);
      if (!ok) throw new IllegalStateException("mirrorDisplay denied");
      display = displayId;
      // The mirror can be returned before SurfaceFlinger's next snapshot contains it.
      // This one-time wait is worker-only. Subsequent transient failures retain the mirror.
      android.os.SystemClock.sleep(50);
    }
    Object b = builder.getConstructor(SurfaceControl.class).newInstance(mirror);
    builder.getMethod("setChildrenOnly", boolean.class).invoke(b, false);
    builder.getMethod("setSourceCrop", Rect.class).invoke(b, crop);
    builder.getMethod("setFrameScale", float.class).invoke(b, .5f);
    builder.getMethod("setExcludeLayers", SurfaceControl[].class).invoke(b, (Object) excludes);
    builder.getMethod("setSecureContentPolicy", int.class).invoke(b, 2);
    builder.getMethod("setProtectedContentPolicy", int.class).invoke(b, 2);
    Object args = builder.getMethod("build").invoke(b);
    Object shot = cap.getMethod("captureLayers", args.getClass()).invoke(null, args);
    if (shot == null) throw new UnavailableException();
    HardwareBuffer buffer =
        (HardwareBuffer) shot.getClass().getMethod("getHardwareBuffer").invoke(shot);
    try {
      if ((Boolean) shot.getClass().getMethod("containsSecureLayers").invoke(shot))
        throw new SecurityException("Secure layers");
      Bitmap hw = (Bitmap) shot.getClass().getMethod("asBitmap").invoke(shot);
      try {
        if (hw.getWidth() != Math.round(crop.width() * .5f)
            || hw.getHeight() != Math.round(crop.height() * .5f))
          throw new IllegalStateException("Crop was ignored");
        return hw.copy(Bitmap.Config.ARGB_8888, false);
      } finally {
        hw.recycle();
      }
    } finally {
      buffer.close();
    }
  }

  static final class UnavailableException extends IllegalStateException {
    UnavailableException() {
      super("Capture unavailable (secure, protected, or surface transition)");
    }
  }

  @Override
  public void close() {
    if (mirror != null) {
      mirror.release();
      mirror = null;
    }
    display = -1;
  }
}
