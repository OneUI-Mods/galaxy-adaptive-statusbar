package dev.ryuya.galaxybar

import java.text.BreakIterator
import java.text.DateFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** UTF-16 offsets can be used directly by Android Layout and ForegroundColorSpan. */
internal object TextColorUnits {
    data class Range(val start: Int, val end: Int)

    private val time =
        Regex(
            "(?<!\\p{Nd})\\p{Nd}{1,2}[:：∶]\\p{Nd}{2}(?:[:：∶]\\p{Nd}{2})?(?!\\p{Nd})" +
                "|(?<!\\p{Nd})\\p{Nd}{1,2}[時时시]\\s*\\p{Nd}{1,2}[分분](?:\\s*\\p{Nd}{1,2}[秒초])?(?!\\p{Nd})",
        )
    private val periods = ConcurrentHashMap<Locale, List<String>>()
    private val dateIds = setOf("date", "date_view", "date_text", "dateview", "status_bar_date")

    fun ranges(
        value: String,
        grouping: ColorGrouping,
        dateAndTime: Boolean,
        locale: Locale = Locale.getDefault(),
    ): List<Range> {
        if (value.isEmpty() || TextCompatibility.isBlank(value)) return emptyList()
        if (!dateAndTime) return listOf(Range(0, value.length))
        if (grouping == ColorGrouping.CHARACTERS) return characters(value)

        // Separate date/time even when Samsung puts them in a single Clock TextView.
        // Unrecognized or ambiguous clock formats remain one intact component.
        val matches = time.findAll(value).take(2).toList()
        if (matches.size != 1) return listOf(Range(0, value.length))
        val match = matches.single()
        var start = match.range.first
        var end = match.range.last + 1
        val markers =
            periods.computeIfAbsent(locale) {
                (DateFormatSymbols(it).amPmStrings.toList() + listOf("AM", "PM", "a.m.", "p.m."))
                    .filter { marker -> marker.isNotBlank() }
                    .distinct()
                    .sortedByDescending { marker -> marker.length }
            }
        val prefixEnd = skipSeparators(value, start, backwards = true)
        val suffixStart = skipSeparators(value, end, backwards = false)
        for (marker in markers) {
            val prefixStart = prefixEnd - marker.length
            if (
                prefixStart >= 0 &&
                    value.regionMatches(prefixStart, marker, 0, marker.length, ignoreCase = true) &&
                    markerBoundary(value, prefixStart - 1, marker)
            ) {
                start = prefixStart
                break
            }
        }
        for (marker in markers) {
            val suffixEnd = suffixStart + marker.length
            if (
                suffixEnd <= value.length &&
                    value.regionMatches(suffixStart, marker, 0, marker.length, ignoreCase = true) &&
                    markerBoundary(value, suffixEnd, marker)
            ) {
                end = suffixEnd
                break
            }
        }
        return listOfNotNull(
            trimmed(value, 0, start),
            Range(start, end),
            trimmed(value, end, value.length),
        )
    }

    fun isDateAndTime(className: String, id: String): Boolean =
        className.endsWith(".Clock") ||
            className.contains("QSClock") ||
            id.contains("clock") ||
            className.endsWith("DateView") ||
            id in dateIds

    private fun characters(value: String): List<Range> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        val result = ArrayList<Range>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (!TextCompatibility.isBlank(value.substring(start, end)))
                result.add(Range(start, end))
            start = end
            end = iterator.next()
        }
        return result
    }

    private fun markerBoundary(value: String, offset: Int, marker: String): Boolean =
        marker.any { it.code > 127 } || offset !in value.indices || !value[offset].isLetterOrDigit()

    private fun isSeparator(char: Char): Boolean =
        char.isWhitespace() ||
            Character.isSpaceChar(char) ||
            Character.getType(char) == Character.FORMAT.toInt()

    private fun skipSeparators(value: String, offset: Int, backwards: Boolean): Int {
        var result = offset
        if (backwards) {
            while (result > 0 && isSeparator(value[result - 1])) result--
        } else {
            while (result < value.length && isSeparator(value[result])) result++
        }
        return result
    }

    private fun trimmed(value: String, start: Int, end: Int): Range? {
        var left = start
        var right = end
        while (left < right && isSeparator(value[left])) left++
        while (right > left && isSeparator(value[right - 1])) right--
        return if (left < right) Range(left, right) else null
    }
}
