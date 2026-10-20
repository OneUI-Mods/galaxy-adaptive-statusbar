package dev.ryuya.galaxybar;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** An illustrative background sample; it never displays real notifications or device data. */
final class ColorPreview extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path clip = new Path();

  ColorPreview(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float d = getResources().getDisplayMetrics().density;
    float w = getWidth(), h = getHeight();
    clip.reset();
    clip.addRoundRect(new RectF(0, 0, w, h), 20 * d, 20 * d, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(clip);
    canvas.drawColor(Color.WHITE);
    paint.setColor(Color.BLACK);
    canvas.drawRect(w * .48f, 0, w, h, paint);
    for (int half = 0; half < 2; half++) {
      canvas.save();
      canvas.clipRect(half == 0 ? 0 : w * .48f, 0, half == 0 ? w * .48f : w, h);
      paint.setColor(half == 0 ? Color.BLACK : Color.WHITE);
      paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      paint.setTextSize(27 * d);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawText("12:34", 22 * d, h / 2 + 9 * d, paint);
      float right = w - 22 * d;
      paint.setStrokeWidth(2 * d);
      paint.setStyle(Paint.Style.STROKE);
      canvas.drawRoundRect(
          right - 31 * d, h / 2 - 7 * d, right, h / 2 + 7 * d, 4 * d, 4 * d, paint);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawRoundRect(
          right - 28 * d, h / 2 - 4 * d, right - 8 * d, h / 2 + 4 * d, 2 * d, 2 * d, paint);
      for (int bar = 0; bar < 4; bar++) {
        float x = right - 63 * d + bar * 6 * d;
        canvas.drawRoundRect(x, h / 2 + (3 - bar * 3) * d, x + 3 * d, h / 2 + 7 * d, d, d, paint);
      }
      canvas.restore();
    }
    canvas.restore();
  }
}
