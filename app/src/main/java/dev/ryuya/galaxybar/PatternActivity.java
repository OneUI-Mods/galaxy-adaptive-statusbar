package dev.ryuya.galaxybar;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textview.MaterialTextView;

/** Test pixels intentionally extend behind the bar; all controls use Material 3 and safe insets. */
public final class PatternActivity extends AppCompatActivity {
  private Pattern pattern;
  private MaterialButtonToggleGroup choices;
  private final int[] ids = new int[5];

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    FrameLayout frame = new FrameLayout(this);
    pattern = new Pattern();
    frame.addView(pattern);
    setContentView(frame);
    MaterialCardView card = new MaterialCardView(this);
    FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    frame.addView(card, position);
    ViewCompat.setOnApplyWindowInsetsListener(
        frame,
        (view, insets) -> {
          Insets safe =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          position.setMargins(
              safe.left + dp(16), safe.top + dp(16), safe.right + dp(16), safe.bottom + dp(16));
          card.setLayoutParams(position);
          return insets;
        });
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(16), dp(16), dp(16), dp(16));
    card.addView(content);
    MaterialTextView title = new MaterialTextView(this);
    title.setText(getString(R.string.pattern_title));
    title.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    content.addView(title);
    MaterialTextView info = new MaterialTextView(this);
    info.setText(getString(R.string.pattern_body));
    info.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    content.addView(info);
    choices = new MaterialButtonToggleGroup(this);
    choices.setSingleSelection(true);
    choices.setSelectionRequired(true);
    content.addView(choices, new LinearLayout.LayoutParams(-1, -2));
    String[] labels = {
      getString(R.string.white),
      getString(R.string.black),
      getString(R.string.split),
      getString(R.string.stripes),
      getString(R.string.moving)
    };
    for (int i = 0; i < labels.length; i++) {
      MaterialButton button =
          new MaterialButton(
              this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
      ids[i] = View.generateViewId();
      button.setId(ids[i]);
      button.setText(labels[i]);
      button.setMinWidth(0);
      button.setInsetTop(0);
      button.setInsetBottom(0);
      button.setPadding(dp(8), dp(8), dp(8), dp(8));
      choices.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    choices.addOnButtonCheckedListener(
        (group, id, checked) -> {
          if (checked)
            for (int i = 0; i < ids.length; i++)
              if (ids[i] == id) {
                pattern.mode = i;
                pattern.invalidate();
              }
        });
    MaterialButton back = new MaterialButton(this);
    back.setText(getString(R.string.back_settings));
    back.setOnClickListener(view -> finish());
    content.addView(back);
    select(getIntent());
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    select(intent);
  }

  private void select(Intent intent) {
    int mode = Math.max(0, Math.min(4, intent.getIntExtra("pattern", 0)));
    choices.check(ids[mode]);
  }

  private final class Pattern extends View {
    int mode;
    final long start = System.nanoTime();
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Pattern() {
      super(PatternActivity.this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.drawColor(mode == 0 ? Color.WHITE : Color.BLACK);
      if (mode == 2) {
        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, getWidth() / 2f, getHeight(), paint);
      }
      if (mode >= 3) {
        int stripe = 40;
        int offset = mode == 4 ? (int) ((System.nanoTime() - start) / 10_000_000 % 80) : 0;
        for (int x = -80; x < getWidth() + 80; x += stripe) {
          paint.setColor(((x / stripe) & 1) == 0 ? Color.WHITE : Color.BLACK);
          canvas.drawRect(x + offset, 0, x + offset + stripe, getHeight(), paint);
        }
      }
      if (mode == 4) postInvalidateOnAnimation();
    }
  }
}
