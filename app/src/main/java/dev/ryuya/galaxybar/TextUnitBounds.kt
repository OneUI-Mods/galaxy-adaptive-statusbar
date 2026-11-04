package dev.ryuya.galaxybar

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.floor

/** Bound the actual glyphs, including RTL runs and components that wrap onto another line. */
internal object TextUnitBounds {
    fun measure(
        value: String,
        range: TextColorUnits.Range,
        layout: Layout,
        paint: TextPaint,
    ): Rect {
        val result = Rect()
        val selection = Path()
        val horizontal = RectF()
        val glyph = Rect()
        val firstLine = layout.getLineForOffset(range.start)
        val lastLine = layout.getLineForOffset(range.end - 1)
        for (line in firstLine..lastLine) {
            val start = maxOf(range.start, layout.getLineStart(line))
            var end = minOf(range.end, layout.getLineEnd(line))
            while (end > start && value[end - 1] in "\r\n") end--
            if (end <= start) continue
            selection.reset()
            layout.getSelectionPath(start, end, selection)
            selection.computeBounds(horizontal, true)
            paint.getTextBounds(value, start, end, glyph)
            if (glyph.isEmpty || horizontal.isEmpty) continue
            result.union(
                floor(horizontal.left).toInt(),
                layout.getLineBaseline(line) + glyph.top,
                ceil(horizontal.right).toInt(),
                layout.getLineBaseline(line) + glyph.bottom,
            )
        }
        return result
    }
}
