package dev.ryuya.galaxybar;

import java.util.Arrays;

/** Only fully resolved background pixels may participate in contrast decisions. */
final class BackgroundSamples {
  static final class Unavailable extends IllegalStateException {
    Unavailable(String reason) {
      super(reason);
    }
  }

  static double[] read(
      int[] pixels, int width, int height, int left, int top, int right, int bottom) {
    if (width <= 0 || height <= 0 || (long) width * height != pixels.length)
      throw new IllegalArgumentException("Invalid background buffer");
    if (left < 0 || top < 0 || right > width || bottom > height)
      throw new IllegalArgumentException("Invalid sample bounds");
    if (left >= right || top >= bottom) throw new Unavailable("Empty background");
    double[] values = new double[(right - left) * (bottom - top)];
    int count = 0;
    int step = Math.max(1, (int) Math.sqrt(values.length / 256.0));
    for (int y = top; y < bottom; y += step) {
      for (int x = left; x < right; x += step) {
        int pixel = pixels[y * width + x];
        if ((pixel >>> 24) < 250) throw new Unavailable("Background not yet opaque");
        values[count++] = Contrast.luminance(pixel);
      }
    }
    return Arrays.copyOf(values, count);
  }
}
