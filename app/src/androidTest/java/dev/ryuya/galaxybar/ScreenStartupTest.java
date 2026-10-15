package dev.ryuya.galaxybar;

import android.app.LocaleManager;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Catches runtime Material layout errors that compilation and Lint cannot detect. */
@RunWith(AndroidJUnit4.class)
public class ScreenStartupTest {
    @Test public void materialSettingsLaunchAndRespectSystemBars() {
        try (var screen = ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(300);
            screen.onActivity(activity -> {
                ViewGroup decor = (ViewGroup)activity.getWindow().getDecorView();
                MaterialToolbar toolbar = find(decor, MaterialToolbar.class);
                assertNotNull(toolbar);
                assertEquals(4, count(decor, MaterialAutoCompleteTextView.class));
                var insets = ViewCompat.getRootWindowInsets(decor);
                assertNotNull(insets);
                int[] location = new int[2]; toolbar.getLocationOnScreen(location);
                assertTrue("Toolbar overlaps status bar", location[1] >= insets.getInsets(WindowInsetsCompat.Type.statusBars()).top);
            });
        }
    }
    @Test public void allLocalizedScreensLaunch() {
        var manager = InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(LocaleManager.class);
        LocaleList original = manager.getApplicationLocales();
        try {
            for (String tag : new String[]{"ja", "en", "es", "ko", "zh-Hans"}) {
                manager.setApplicationLocales(LocaleList.forLanguageTags(tag));
                try (var screen = ActivityScenario.launch(MainActivity.class)) { screen.onActivity(activity -> assertFalse(activity.isFinishing())); }
                try (var screen = ActivityScenario.launch(PatternActivity.class)) { screen.onActivity(activity -> assertFalse(activity.isFinishing())); }
                try (var screen = ActivityScenario.launch(AboutActivity.class)) { screen.onActivity(activity -> assertFalse(activity.isFinishing())); }
            }
        } finally { manager.setApplicationLocales(original); }
    }
    private static <T> T find(View view, Class<T> type) {
        if(type.isInstance(view))return type.cast(view);
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){T result=find(group.getChildAt(i),type);if(result!=null)return result;}
        return null;
    }
    private static int count(View view, Class<?> type) {
        int n=type.isInstance(view)?1:0;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)n+=count(group.getChildAt(i),type);
        return n;
    }
}
