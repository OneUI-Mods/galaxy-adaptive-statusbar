package dev.ryuya.galaxybar;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends AppCompatActivity {
  private SharedPreferences prefs;
  private MaterialTextView status;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          SharedPreferences latest = getSharedPreferences("status", MODE_PRIVATE);
          long age = (System.currentTimeMillis() - latest.getLong("time", 0)) / 1000;
          status.setText(
              age < 15
                  ? latest.getString("text", "") + "\n" + getString(R.string.updated_seconds, age)
                  : getString(R.string.waiting_hook));
          handler.postDelayed(this, 2000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("config", MODE_PRIVATE);
    if (getIntent().getBooleanExtra("disable", false)) {
      prefs.edit().putBoolean("enabled", false).apply();
      getIntent().removeExtra("disable");
    }
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    LinearLayout safe = column(0);
    setContentView(safe);
    ViewCompat.setOnApplyWindowInsetsListener(
        safe,
        (view, insets) -> {
          Insets area =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(area.left, area.top, area.right, area.bottom);
          return insets;
        });
    ViewCompat.requestApplyInsets(safe);
    MaterialToolbar toolbar = new MaterialToolbar(this);
    toolbar.setTitle(getString(R.string.screen_title));
    toolbar.setSubtitle(getString(R.string.app_name));
    safe.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
    NestedScrollView scroll = new NestedScrollView(this);
    scroll.setFillViewport(true);
    safe.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = column(20);
    scroll.addView(body);
    LinearLayout hero = card(body);
    text(
        hero,
        getString(R.string.hero_title),
        com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
    text(
        hero,
        getString(R.string.hero_body),
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    MaterialSwitch enabled = toggle(hero, getString(R.string.enabled), "enabled", false);
    text(
        body,
        getString(R.string.color_targets),
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    toggle(body, getString(R.string.clock), "clock", true);
    toggle(body, getString(R.string.notifications), "notifications", true);
    toggle(body, getString(R.string.signals), "signals", true);
    toggle(body, getString(R.string.battery), "battery", true);
    text(
        body,
        getString(R.string.battery_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    int[] rates = {5, 10, 15, 30};
    String[] rateLabels = {"5 Hz", "10 Hz", "15 Hz", getString(R.string.rate_30)};
    int selected = 1;
    for (int i = 0; i < rates.length; i++) if (rates[i] == prefs.getInt("hz", 10)) selected = i;
    dropdown(
        body,
        getString(R.string.frequency),
        rateLabels,
        selected,
        index -> prefs.edit().putInt("hz", rates[index]).apply());
    text(
        body,
        getString(R.string.power_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    String[] keys = {"robust", "mean", "median", "vote"};
    String[] labels = {
      getString(R.string.robust),
      getString(R.string.mean),
      getString(R.string.median),
      getString(R.string.vote)
    };
    selected = 0;
    for (int i = 0; i < keys.length; i++)
      if (keys[i].equals(prefs.getString("algorithm", "robust"))) selected = i;
    dropdown(
        body,
        getString(R.string.algorithm),
        labels,
        selected,
        index -> prefs.edit().putString("algorithm", keys[index]).apply());
    MaterialTextView suppression =
        text(
            body,
            getString(R.string.hysteresis),
            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
    Slider slider = new Slider(this);
    slider.setValueFrom(0);
    slider.setValueTo(50);
    slider.setStepSize(1);
    slider.setValue(Math.round(prefs.getFloat("hysteresis", .15f) * 100));
    suppression.setText(getString(R.string.hysteresis_value, Math.round(slider.getValue())));
    slider.addOnChangeListener(
        (control, value, fromUser) -> {
          suppression.setText(getString(R.string.hysteresis_value, Math.round(value)));
          if (fromUser) prefs.edit().putFloat("hysteresis", value / 100f).apply();
        });
    body.addView(slider);
    button(
        body,
        getString(R.string.open_pattern),
        () -> startActivity(new Intent(this, PatternActivity.class)));
    button(
        body,
        getString(R.string.restore),
        () -> {
          prefs.edit().putBoolean("enabled", false).apply();
          enabled.setChecked(false);
        });
    LinearLayout diagnostic = card(body);
    text(
        diagnostic,
        getString(R.string.status),
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    status =
        text(
            diagnostic,
            "",
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    status.setTextIsSelectable(true);
    text(
        diagnostic,
        getString(R.string.experimental_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    toggle(diagnostic, getString(R.string.fixed_test), "fixed", false);
    toggle(diagnostic, getString(R.string.debug), "debug", false);
    button(diagnostic, getString(R.string.root_check), this::checkRoot);
    text(
        body,
        getString(R.string.appearance),
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    String[] languageTags = {"", "ja", "en", "es", "ko", "zh-Hans"};
    String[] languageNames = {
      getString(R.string.system_default), "日本語", "English", "Español", "한국어", "简体中文"
    };
    String currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags();
    int languageIndex = 0;
    for (int i = 1; i < languageTags.length; i++)
      if (currentLocale.equals(languageTags[i])) languageIndex = i;
    dropdown(
        body,
        getString(R.string.language),
        languageNames,
        languageIndex,
        index ->
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTags[index])));
    int[] themes = {
      AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
      AppCompatDelegate.MODE_NIGHT_NO,
      AppCompatDelegate.MODE_NIGHT_YES
    };
    int themeIndex = 0;
    for (int i = 0; i < themes.length; i++)
      if (themes[i] == prefs.getInt("ui_theme", -1)) themeIndex = i;
    dropdown(
        body,
        getString(R.string.theme),
        new String[] {
          getString(R.string.system_default), getString(R.string.light), getString(R.string.dark)
        },
        themeIndex,
        index -> {
          prefs.edit().putInt("ui_theme", themes[index]).apply();
          AppCompatDelegate.setDefaultNightMode(themes[index]);
        });
    button(
        body,
        getString(R.string.about),
        () -> startActivity(new Intent(this, AboutActivity.class)));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private LinearLayout column(int padding) {
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
    return layout;
  }

  private LinearLayout card(LinearLayout parent) {
    MaterialCardView card = new MaterialCardView(this);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(12), 0, dp(20));
    parent.addView(card, params);
    LinearLayout content = column(20);
    card.addView(content);
    return content;
  }

  private MaterialTextView text(LinearLayout parent, String value, int appearance) {
    MaterialTextView text = new MaterialTextView(this);
    text.setTextAppearance(appearance);
    text.setText(value);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(8), 0, dp(8));
    parent.addView(text, params);
    return text;
  }

  private MaterialSwitch toggle(
      LinearLayout parent, String label, String key, boolean defaultValue) {
    MaterialSwitch control = new MaterialSwitch(this);
    control.setText(label);
    control.setMinHeight(dp(56));
    control.setChecked(prefs.getBoolean(key, defaultValue));
    control.setOnCheckedChangeListener(
        (button, checked) -> prefs.edit().putBoolean(key, checked).apply());
    parent.addView(control, new LinearLayout.LayoutParams(-1, -2));
    return control;
  }

  private interface Selection {
    void choose(int index);
  }

  private void dropdown(
      LinearLayout parent, String hint, String[] labels, int selected, Selection selection) {
    TextInputLayout field =
        new TextInputLayout(
            this,
            null,
            com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle);
    field.setHint(hint);
    MaterialAutoCompleteTextView input = new MaterialAutoCompleteTextView(field.getContext());
    input.setInputType(android.text.InputType.TYPE_NULL);
    input.setAdapter(new ArrayAdapter<>(this, R.layout.dropdown_item, labels));
    input.setText(labels[selected], false);
    input.setOnItemClickListener((adapter, view, index, itemId) -> selection.choose(index));
    field.addView(input, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(12), 0, dp(12));
    parent.addView(field, params);
  }

  private void button(LinearLayout parent, String label, Runnable action) {
    MaterialButton button = new MaterialButton(this);
    button.setText(label);
    button.setOnClickListener(view -> action.run());
    parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
  }

  private void checkRoot() {
    new Thread(
            () -> {
              String result;
              try {
                Process process =
                    new ProcessBuilder("su", "-c", "id; cat /proc/self/attr/current")
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                  process.destroy();
                  result = getString(R.string.root_timeout);
                } else result = new String(process.getInputStream().readAllBytes());
              } catch (Exception error) {
                result = error.toString();
              }
              String message = result;
              runOnUiThread(
                  () -> {
                    if (!isFinishing() && !isDestroyed())
                      new MaterialAlertDialogBuilder(this)
                          .setTitle(getString(R.string.root_status))
                          .setMessage(message)
                          .setPositiveButton(getString(R.string.close), null)
                          .show();
                  });
            },
            "GalaxyBarRootCheck")
        .start();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    boolean disable = intent.getBooleanExtra("disable", false);
    intent.removeExtra("disable");
    setIntent(intent);
    if (disable) {
      prefs.edit().putBoolean("enabled", false).apply();
      recreate();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }
}
