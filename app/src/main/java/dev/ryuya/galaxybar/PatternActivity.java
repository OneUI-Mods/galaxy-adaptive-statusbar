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
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textview.MaterialTextView;

/** Test pixels intentionally extend behind the bar; all controls use Material 3 and safe insets. */
public final class PatternActivity extends AppCompatActivity {
  private Pattern pattern;
  private ChipGroup choices;
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
    card.setRadius(dp(28));
    card.setStrokeWidth(0);
    card.setCardBackgroundColor(
        MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainer));
    int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560));
    FrameLayout.LayoutParams position =
        new FrameLayout.LayoutParams(width, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
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
    content.setPadding(dp(20), dp(16), dp(20), dp(12));
    card.addView(content);
    MaterialTextView title = new MaterialTextView(this);
    title.setText(getString(R.string.pattern_title));
    title.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    content.addView(title);
    MaterialTextView info = new MaterialTextView(this);
    info.setText(getString(R.string.pattern_body));
    info.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    info.setTextColor(
        MaterialColors.getColor(info, com.google.android.material.R.attr.colorOnSurfaceVariant));
    info.setPadding(0, dp(4), 0, dp(12));
    content.addView(info);
    choices = new ChipGroup(this);
    choices.setSingleSelection(true);
    choices.setSelectionRequired(true);
    choices.setChipSpacingHorizontal(dp(8));
    choices.setChipSpacingVertical(dp(4));
    content.addView(choices, new LinearLayout.LayoutParams(-1, -2));
    String[] labels = {
      getString(R.string.white),
      getString(R.string.black),
      getString(R.string.split),
      getString(R.string.stripes),
      getString(R.string.moving)
    };
    for (int i = 0; i < labels.length; i++) {
      Chip button = new Chip(this);
      ids[i] = View.generateViewId();
      button.setId(ids[i]);
      button.setText(labels[i]);
      button.setCheckable(true);
      button.setEnsureMinTouchTargetSize(true);
      choices.addView(button);
    }
    choices.setOnCheckedStateChangeListener(
        (group, checked) -> {
          if (checked.isEmpty()) return;
          for (int i = 0; i < ids.length; i++)
            if (ids[i] == checked.get(0)) {
              pattern.mode = i;
              pattern.invalidate();
            }
        });
    MaterialButton back =
        new MaterialButton(
            new androidx.appcompat.view.ContextThemeWrapper(this, R.style.TonalActionTheme));
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
