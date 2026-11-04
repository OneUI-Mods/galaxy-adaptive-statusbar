package dev.ryuya.galaxybar

import android.content.Context
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import org.junit.Assert.*
import org.junit.Test

/** Runs without root or Samsung SystemUI; never drives a real device from the test itself. */
class ColorGroupingTest {
    @Test
    fun combinedDateAndTimeHaveNonOverlappingSamplingBounds() {
        val value = "11/04 13:44"
        val paint = TextPaint().apply { textSize = 32f }
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, 500).build()
        val ranges = TextColorUnits.ranges(value, ColorGrouping.PARTS, true)
        assertEquals(2, ranges.size)
        val date = TextUnitBounds.measure(value, ranges[0], layout, paint)
        val time = TextUnitBounds.measure(value, ranges[1], layout, paint)
        assertFalse(date.isEmpty)
        assertFalse(time.isEmpty)
        assertTrue(date.right <= time.left)
        assertTrue(time.right <= layout.width)
    }

    @Test
    fun wrappedComponentsKeepTheBoundsFromEveryLine() {
        val value = "12.3\nK/s"
        val paint = TextPaint().apply { textSize = 32f }
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, 500).build()
        val range = TextColorUnits.ranges(value, ColorGrouping.PARTS, false).single()
        val bounds = TextUnitBounds.measure(value, range, layout, paint)
        assertFalse(bounds.isEmpty)
        assertTrue(bounds.top < layout.getLineBottom(0))
        assertTrue(bounds.bottom > layout.getLineBottom(0))
    }

    @Test
    fun rtlClockComponentsStayWithinTheRenderedLayout() {
        val value = "١١/٠٤ ١٣:٤٤"
        val paint = TextPaint().apply { textSize = 32f }
        val layout =
            StaticLayout.Builder.obtain(value, 0, value.length, paint, 500)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .build()
        for (range in TextColorUnits.ranges(value, ColorGrouping.PARTS, true)) {
            val bounds = TextUnitBounds.measure(value, range, layout, paint)
            assertFalse(bounds.isEmpty)
            assertTrue(bounds.left >= 0)
            assertTrue(bounds.right <= layout.width)
        }
    }

    @Test
    fun groupingSelectionPersistsAndReachesTheHookProviderWithoutReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        val hadValue = prefs.contains(ColorGrouping.KEY)
        val before = prefs.getString(ColorGrouping.KEY, null)
        prefs.edit().remove(ColorGrouping.KEY).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                for (mode in
                    listOf(ColorGrouping.PARTS, ColorGrouping.AUTO, ColorGrouping.CHARACTERS)) {
                    screen.onActivity { activity ->
                        val control =
                            activity.findViewById<MaterialAutoCompleteTextView>(R.id.color_grouping)
                        control.onItemClickListener!!.onItemClick(
                            null,
                            null,
                            mode.ordinal,
                            mode.ordinal.toLong(),
                        )
                        val config =
                            context.contentResolver.call(
                                ConfigProvider.URI!!,
                                "config",
                                null,
                                null,
                            )!!
                        assertEquals(mode.preference, config.getString(ColorGrouping.KEY))
                    }
                    screen.recreate()
                    screen.onActivity { activity ->
                        val control =
                            activity.findViewById<MaterialAutoCompleteTextView>(R.id.color_grouping)
                        val label =
                            when (mode) {
                                ColorGrouping.PARTS -> R.string.color_grouping_parts
                                ColorGrouping.AUTO -> R.string.color_grouping_auto
                                ColorGrouping.CHARACTERS -> R.string.color_grouping_characters
                            }
                        assertEquals(activity.getString(label), control.text.toString())
                    }
                }
            }
        } finally {
            if (hadValue) prefs.edit().putString(ColorGrouping.KEY, before).commit()
            else prefs.edit().remove(ColorGrouping.KEY).commit()
        }
    }
}
