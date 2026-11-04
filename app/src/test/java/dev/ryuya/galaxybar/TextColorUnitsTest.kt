package dev.ryuya.galaxybar

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TextColorUnitsTest {
    private fun parts(value: String, locale: Locale = Locale.US): List<String> =
        TextColorUnits.ranges(value, ColorGrouping.PARTS, true, locale).map {
            value.substring(it.start, it.end)
        }

    @Test
    fun defaultAndUnknownSettingsKeepTheExistingCharacterMode() {
        assertEquals(ColorGrouping.CHARACTERS, ColorGrouping.fromPreference(null))
        assertEquals(ColorGrouping.CHARACTERS, ColorGrouping.fromPreference("future-mode"))
        assertEquals(ColorGrouping.PARTS, ColorGrouping.fromPreference("parts"))
        assertEquals(ColorGrouping.CHARACTERS, ColorGrouping.fromPreference("characters"))
    }

    @Test
    fun combinedDateAndTimeBecomeTwoIndependentColorDecisions() {
        assertEquals(listOf("11/04", "13:44"), parts("11/04 13:44"))
        assertEquals(listOf("13:44", "11/04"), parts("13:44 11/04"))
        assertEquals(listOf("11/04", "13:44:09"), parts("11/04 13:44:09"))
        assertEquals(listOf("2026年11月4日", "13:44"), parts("2026年11月4日13:44"))
    }

    @Test
    fun dateAndTimeAlreadyInSeparateViewsEachStayIntact() {
        assertEquals(listOf("11/04"), parts("11/04"))
        assertEquals(listOf("13:44"), parts("13:44"))
        assertEquals(listOf("Wed, November 4"), parts("Wed, November 4"))
    }

    @Test
    fun localizedDayPeriodsStayWithTheTime() {
        assertEquals(listOf("11/04", "AM 1:23"), parts("11/04 AM 1:23"))
        assertEquals(listOf("1:23 PM", "11/04"), parts("1:23 PM 11/04"))
        assertEquals(listOf("11/04", "午後 1:23"), parts("11/04 午後 1:23", Locale.JAPAN))
        assertEquals(listOf("11/04", "下午1:23"), parts("11/04 下午1:23", Locale.CHINA))
        assertEquals(listOf("11/04", "오후 1:23"), parts("11/04 오후 1:23", Locale.KOREA))
    }

    @Test
    fun nonBreakingAndDirectionalSeparatorsDoNotBecomeTheirOwnParts() {
        assertEquals(listOf("11/04", "1:23\u202fPM"), parts("11/04\u00a01:23\u202fPM"))
        assertEquals(listOf("11/04", "13:44"), parts("\u200e11/04\u200f \u200e13:44\u200f"))
    }

    @Test
    fun unicodeDigitsColonsAndHourMinuteUnitsKeepTheWholeTimeTogether() {
        assertEquals(listOf("11/04", "١٣:٤٤"), parts("11/04 ١٣:٤٤"))
        assertEquals(listOf("１１/０４", "１３：４４"), parts("１１/０４ １３：４４"))
        assertEquals(listOf("11/04", "13∶44"), parts("11/04 13∶44"))
        assertEquals(listOf("11月4日", "午後1時23分"), parts("11月4日 午後1時23分", Locale.JAPAN))
        assertEquals(listOf("11/04", "오후 1시 23분"), parts("11/04 오후 1시 23분", Locale.KOREA))
    }

    @Test
    fun dayPeriodMatchingDoesNotConsumeLettersFromTheDate() {
        assertEquals(listOf("STAMP", "1:23"), parts("STAMP 1:23"))
        assertEquals(listOf("1:23", "PMonday"), parts("1:23 PMonday"))
    }

    @Test
    fun unknownOrAmbiguousFormatsRemainOneComponent() {
        assertEquals(listOf("11/04 13.44"), parts("11/04 13.44"))
        assertEquals(listOf("13:44 / 14:44"), parts("13:44 / 14:44"))
        assertEquals(listOf("custom clock"), parts("custom clock"))
    }

    @Test
    fun otherTextComponentsAreNotSplitByEitherMode() {
        for (mode in ColorGrouping.entries) {
            val value = "12.3\nK/s"
            assertEquals(
                listOf(TextColorUnits.Range(0, value.length)),
                TextColorUnits.ranges(value, mode, false),
            )
        }
    }

    @Test
    fun charactersRetainGraphemesAndUtf16SpanOffsets() {
        val value = "1\u0301:😀 2"
        val ranges = TextColorUnits.ranges(value, ColorGrouping.CHARACTERS, true)
        assertEquals(
            listOf("1\u0301", ":", "😀", "2"),
            ranges.map { value.substring(it.start, it.end) },
        )
        assertEquals(TextColorUnits.Range(3, 5), ranges[2])
    }

    @Test
    fun blankLabelsHaveNoColorTargets() {
        for (mode in ColorGrouping.entries) {
            assertTrue(TextColorUnits.ranges("", mode, true).isEmpty())
            assertTrue(TextColorUnits.ranges(" \t\n", mode, true).isEmpty())
        }
    }

    @Test
    fun separateDateViewsUseTheClockPreferenceWithoutMatchingUnrelatedLabels() {
        assertTrue(TextColorUnits.isDateAndTime("android.widget.TextView", "date"))
        assertTrue(
            TextColorUnits.isDateAndTime(
                "com.android.systemui.statusbar.policy.VariableDateView",
                "label",
            ),
        )
        assertTrue(
            TextColorUnits.isDateAndTime("com.android.systemui.statusbar.policy.QSClock", "time"),
        )
        assertFalse(TextColorUnits.isDateAndTime("android.widget.TextView", "candidate"))
        assertFalse(
            TextColorUnits.isDateAndTime("android.widget.TextView", "network_speed_contentview"),
        )
    }
}
