package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.core.widget.NestedScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Map;
import org.junit.Test;

/** Navigation must preserve settings and hidden-page positions across Activity recreation. */
public class SettingsNavigationTest {
  @Test
  public void tabsRestoreSelectionAndPagePositionWithoutChangingSettings() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Map<String, ?> before = context.getSharedPreferences("config", 0).getAll();
    int[] scroll = {0};
    try (var screen = ActivityScenario.launch(MainActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screen.onActivity(
          a -> {
            TabLayout tabs = a.findViewById(R.id.settings_tabs);
            assertEquals(4, tabs.getTabCount());
            assertEquals(0, tabs.getSelectedTabPosition());
            NestedScrollView page = shown(a.getWindow().getDecorView(), NestedScrollView.class);
            assertNotNull(page);
            page.scrollTo(0, 100000);
            scroll[0] = page.getScrollY();
            tabs.getTabAt(3).select();
          });
      screen.recreate();
      screen.onActivity(
          a -> {
            TabLayout tabs = a.findViewById(R.id.settings_tabs);
            assertEquals(3, tabs.getSelectedTabPosition());
            tabs.getTabAt(0).select();
          });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screen.onActivity(
          a ->
              assertEquals(
                  scroll[0],
                  shown(a.getWindow().getDecorView(), NestedScrollView.class).getScrollY()));
      assertEquals(before, context.getSharedPreferences("config", 0).getAll());
    }
  }

  @Test
  public void licenseSearchDetailsAndBackKeepTheQueryAndLicensePosition() {
    int[] scroll = {0};
    try (var screen = ActivityScenario.launch(LicensesActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screen.onActivity(
          a -> {
            TextInputEditText search = a.findViewById(R.id.license_search);
            search.setText("material");
            ListView list = a.findViewById(R.id.license_list);
            assertEquals(1, list.getAdapter().getCount());
          });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screen.onActivity(
          a -> {
            ListView list = a.findViewById(R.id.license_list);
            assertNotNull(list.getChildAt(0));
            list.getChildAt(0).performClick();
            TextView license = a.findViewById(R.id.license_text);
            assertTrue(license.getText().toString().contains("TERMS AND CONDITIONS"));
          });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screen.onActivity(
          a -> {
            NestedScrollView page = shown(a.getWindow().getDecorView(), NestedScrollView.class);
            page.scrollTo(0, 100000);
            scroll[0] = page.getScrollY();
            assertTrue(scroll[0] > 0);
            TabLayout tabs = a.findViewById(R.id.settings_tabs);
            assertEquals(2, tabs.getTabCount());
            tabs.getTabAt(1).select();
          });
      screen.recreate();
      screen.onActivity(
          a -> {
            TabLayout tabs = a.findViewById(R.id.settings_tabs);
            assertEquals(1, tabs.getSelectedTabPosition());
            tabs.getTabAt(0).select();
          });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screen.onActivity(
          a -> {
            assertEquals(
                scroll[0],
                shown(a.getWindow().getDecorView(), NestedScrollView.class).getScrollY());
            a.getOnBackPressedDispatcher().onBackPressed();
            TextInputEditText search = a.findViewById(R.id.license_search);
            assertEquals("material", search.getText().toString());
            ListView list = a.findViewById(R.id.license_list);
            assertEquals(1, list.getAdapter().getCount());
            search.setText("no-such-library-829403");
            assertEquals(0, list.getAdapter().getCount());
          });
    }
  }

  private static <T extends View> T shown(View node, Class<T> type) {
    if (!node.isShown()) return null;
    if (type.isInstance(node)) return type.cast(node);
    if (node instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        T found = shown(group.getChildAt(i), type);
        if (found != null) return found;
      }
    return null;
  }
}
