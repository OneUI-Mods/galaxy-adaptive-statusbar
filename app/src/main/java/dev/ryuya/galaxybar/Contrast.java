package dev.ryuya.galaxybar;

import java.util.Arrays;

/** Platform independent contrast decisions in linear sRGB. */
public final class Contrast {
  private static final double[] LINEAR = new double[256];

  static {
    for (int i = 0; i < 256; i++) {
      double s = i / 255.0;
      LINEAR[i] = s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
  }

  public static double luminance(int argb) {
    return .2126 * LINEAR[(argb >> 16) & 255]
        + .7152 * LINEAR[(argb >> 8) & 255]
        + .0722 * LINEAR[argb & 255];
  }

  public static int choose(double[] values, int previous, String algorithm, double hysteresis) {
    if (values.length == 0) throw new IllegalArgumentException("No opaque samples");
    double white, black;
    if (algorithm.equals("median") || algorithm.equals("mean")) {
      double l = 0;
      if (algorithm.equals("median")) {
        Arrays.sort(values);
        l = values[values.length / 2];
      } else {
        for (double v : values) l += v;
        l /= values.length;
      }
      white = 1.05 / (l + .05);
      black = (l + .05) / .05;
    } else if (algorithm.equals("vote")) {
      white = 1;
      black = 1;
      for (double v : values) {
        if (1.05 / (v + .05) > (v + .05) / .05) white++;
        else black++;
      }
    } else {
      // Lower decile contrast: more robust to mixed backgrounds than a mean.
      double[] w = new double[values.length], b = new double[values.length];
      for (int i = 0; i < values.length; i++) {
        w[i] = 1.05 / (values[i] + .05);
        b[i] = (values[i] + .05) / .05;
      }
      Arrays.sort(w);
      Arrays.sort(b);
      white = w[values.length / 10];
      black = b[values.length / 10];
    }
    double ratio = black / white;
    if (previous == 0xff000000 && ratio >= 1 / (1 + hysteresis)) return previous;
    if (previous == 0xffffffff && ratio <= 1 + hysteresis) return previous;
    return ratio >= 1 ? 0xff000000 : 0xffffffff;
  }
}
