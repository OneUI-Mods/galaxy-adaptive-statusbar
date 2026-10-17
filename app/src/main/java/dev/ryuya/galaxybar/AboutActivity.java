package dev.ryuya.galaxybar;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.textview.MaterialTextView;

public final class AboutActivity extends AppCompatActivity {
  public static final String SOURCE_URL = "https://github.com/ryuya0124/galaxy-adaptive-statusbar";

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    setContentView(root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          Insets safe =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return insets;
        });
    MaterialToolbar toolbar = new MaterialToolbar(this);
    toolbar.setTitle(R.string.about);
    toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
    toolbar.setNavigationContentDescription(R.string.back);
    toolbar.setNavigationOnClickListener(v -> finish());
    root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
    NestedScrollView scroll = new NestedScrollView(this);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(24), dp(24), dp(24), dp(24));
    scroll.addView(content);
    ShapeableImageView icon = new ShapeableImageView(this);
    icon.setImageResource(R.mipmap.ic_launcher);
    icon.setContentDescription(getString(R.string.app_name));
    content.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));
    MaterialTextView name = new MaterialTextView(this);
    name.setText(R.string.app_name);
    name.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
    content.addView(name);
    MaterialTextView version = new MaterialTextView(this);
    version.setText(getString(R.string.version, BuildConfig.VERSION_NAME));
    content.addView(version);
    addButton(
        content,
        R.string.source,
        () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))));
    addButton(
        content,
        R.string.project_license,
        () ->
            startActivity(new Intent(this, LicensesActivity.class).putExtra("library", "project")));
    addButton(
        content,
        R.string.open_source,
        () -> startActivity(new Intent(this, LicensesActivity.class)));
    addButton(content, R.string.back, this::finish);
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private void addButton(LinearLayout parent, int label, Runnable action) {
    MaterialButton button = new MaterialButton(this);
    button.setText(label);
    button.setOnClickListener(view -> action.run());
    parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
  }
}
