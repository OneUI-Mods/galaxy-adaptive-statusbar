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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.textview.MaterialTextView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
        () -> showLicense(R.string.project_license, "legal/PROJECT-MIT.txt"));
    addButton(
        content,
        R.string.open_source,
        () -> showLicense(R.string.open_source, "legal/THIRD-PARTY-NOTICES.txt"));
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

  private void showLicense(int title, String path) {
    String text;
    try (var input = getAssets().open(path)) {
      text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      text = getString(R.string.license_error);
    }
    MaterialTextView legal = new MaterialTextView(this);
    legal.setText(text);
    legal.setTextIsSelectable(true);
    legal.setPadding(dp(24), dp(16), dp(24), dp(16));
    NestedScrollView scroll = new NestedScrollView(this);
    scroll.addView(legal);
    new MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(scroll)
        .setPositiveButton(R.string.close, null)
        .show();
  }
}
