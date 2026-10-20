package dev.ryuya.galaxybar;

import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import java.nio.ByteBuffer;

/** Headless GPU regression probe using the production shader. Does not attach to SystemUI. */
public final class NativeBatteryRenderProbe {
  public static void main(String[] args) throws Exception {
    NativeBatteryPalette palette = new NativeBatteryPalette();
    int body = args.length > 0 && args[0].equals("fade") ? 0xff808080 : Color.BLACK;
    int text = args.length > 0 && args[0].equals("fade") ? 0xff808080 : Color.WHITE;
    RenderNode layer = new RenderNode("NativeBatteryProbeLayer");
    layer.setPosition(0, 0, 100, 100);
    Canvas canvas = layer.beginRecording();
    Paint paint = new Paint();
    paint.setColor(Color.WHITE);
    canvas.drawRect(10, 10, 30, 30, paint);
    paint.setColor(0xff4d4d52);
    canvas.drawRect(40, 10, 60, 30, paint);
    paint.setColor(Color.GREEN);
    canvas.drawRect(10, 40, 30, 60, paint);
    paint.setColor(0x99ffffff);
    canvas.drawRect(40, 40, 60, 60, paint);
    layer.endRecording();
    layer.setRenderEffect(palette.effect(body, text, 0x99ffffff, 0xff4d4d52));
    RenderNode root = new RenderNode("NativeBatteryProbeRoot");
    root.setPosition(0, 0, 100, 100);
    canvas = root.beginRecording();
    canvas.drawColor(Color.WHITE);
    canvas.drawRenderNode(layer);
    root.endRecording();
    try (ImageReader reader =
        ImageReader.newInstance(
            100,
            100,
            PixelFormat.RGBA_8888,
            3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_CPU_READ_OFTEN)) {
      HardwareRenderer renderer = new HardwareRenderer();
      renderer.setOpaque(true);
      renderer.setSurface(reader.getSurface());
      renderer.setContentRoot(root);
      int status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
      Image image = null;
      for (int i = 0; i < 100 && image == null; i++) {
        Thread.sleep(10);
        image = reader.acquireLatestImage();
      }
      if (image == null) throw new IllegalStateException("No GPU output; status=" + status);
      Image.Plane plane = image.getPlanes()[0];
      ByteBuffer bytes = plane.getBuffer();
      int sampleIndex = 0;
      int fade = body == 0xff808080 ? 128 : 0;
      int[][] expected = {
        {fade, fade, fade, 255},
        {text & 255, text & 255, text & 255, 255},
        {0, 255, 0, 255},
        {fade == 128 ? 179 : 102, fade == 128 ? 179 : 102, fade == 128 ? 179 : 102, 255},
        {255, 255, 255, 255}
      };
      for (int[] point : new int[][] {{20, 20}, {50, 20}, {20, 50}, {50, 50}, {80, 80}}) {
        int offset = point[1] * plane.getRowStride() + point[0] * plane.getPixelStride();
        for (int channel = 0; channel < 4; channel++)
          if (Math.abs((bytes.get(offset + channel) & 255) - expected[sampleIndex][channel]) > 1)
            throw new AssertionError(
                "Unexpected GPU output at sample " + sampleIndex + ", channel " + channel);
        sampleIndex++;
        System.out.printf(
            "%d,%d=%02x%02x%02x%02x%n",
            point[0],
            point[1],
            bytes.get(offset) & 255,
            bytes.get(offset + 1) & 255,
            bytes.get(offset + 2) & 255,
            bytes.get(offset + 3) & 255);
      }
      image.close();
      renderer.destroy();
    }
    System.out.println("GPU probe completed");
    System.exit(0);
  }
}
