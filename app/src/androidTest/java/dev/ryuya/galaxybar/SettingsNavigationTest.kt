package dev.ryuya.galaxybar

import android.view.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

/* Navigation must preserve settings and hidden-page positions across Activity recreation. */
open class SettingsNavigationTest {
    @Test
    open fun tabsRestoreSelectionAndPagePositionWithoutChangingSettings() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val before = context.getSharedPreferences("config", 0).getAll()
        val scroll = intArrayOf(0)
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(4, tabs.getTabCount())
                assertEquals(0, tabs.getSelectedTabPosition())
                val page =
                    SettingsNavigationTest.shown(
                        a.getWindow().getDecorView(),
                        NestedScrollView::class.java,
                    )
                assertNotNull(page)
                page!!.scrollTo(0, 100000)
                scroll[0] = page!!.getScrollY()
                tabs.getTabAt(3)!!.select()
            })
            screen.recreate()
            screen.onActivity({ a ->
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(3, tabs.getSelectedTabPosition())
                tabs.getTabAt(0)!!.select()
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                assertEquals(
                    scroll[0],
                    SettingsNavigationTest.shown(
                            a.getWindow().getDecorView(),
                            NestedScrollView::class.java,
                        )!!
                        .getScrollY(),
                )
            })
            assertEquals(before, context.getSharedPreferences("config", 0).getAll())
        }
    }

    @Test
    open fun licenseSearchDetailsAndBackKeepTheQueryAndLicensePosition() {
        val scroll = intArrayOf(0)
        ActivityScenario.launch(LicensesActivity::class.java).use { screen ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                val search: com.google.android.material.textfield.TextInputEditText =
                    a.findViewById(R.id.license_search)
                search.setText("material")
                val list: ListView = a.findViewById(R.id.license_list)
                assertEquals(1, list.getAdapter().getCount())
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                val list: ListView = a.findViewById(R.id.license_list)
                assertNotNull(list.getChildAt(0))
                list.getChildAt(0).performClick()
                val license: TextView = a.findViewById(R.id.license_text)
                assertTrue(license.getText().toString().contains("TERMS AND CONDITIONS"))
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                val page =
                    SettingsNavigationTest.shown(
                        a.getWindow().getDecorView(),
                        NestedScrollView::class.java,
                    )
                page!!.scrollTo(0, 100000)
                scroll[0] = page!!.getScrollY()
                assertTrue(scroll[0] > 0)
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(2, tabs.getTabCount())
                tabs.getTabAt(1)!!.select()
            })
            screen.recreate()
            screen.onActivity({ a ->
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(1, tabs.getSelectedTabPosition())
                tabs.getTabAt(0)!!.select()
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                assertEquals(
                    scroll[0],
                    SettingsNavigationTest.shown(
                            a.getWindow().getDecorView(),
                            NestedScrollView::class.java,
                        )!!
                        .getScrollY(),
                )
                a.onBackPressedDispatcher.onBackPressed()
                val search: com.google.android.material.textfield.TextInputEditText =
                    a.findViewById(R.id.license_search)
                assertEquals("material", search.getText().toString())
                val list: ListView = a.findViewById(R.id.license_list)
                assertEquals(1, list.getAdapter().getCount())
                search.setText("no-such-library-829403")
                assertEquals(0, list.getAdapter().getCount())
            })
        }
    }

    companion object {
        @JvmStatic
        private fun <T : View?> shown(node: View, type: Class<T>): T? {
            if (!node.isShown()) return null
            if (type.isInstance(node)) return type.cast(node)
            if (node is ViewGroup)
                for (i in 0 until node.getChildCount()) {
                    val found = SettingsNavigationTest.shown(node.getChildAt(i), type)
                    if (found != null) return found
                }
            return null
        }
    }
}
