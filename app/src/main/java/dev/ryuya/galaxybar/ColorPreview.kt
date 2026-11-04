package dev.ryuya.galaxybar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.Path
import android.graphics.Path.Direction
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/* An illustrative background sample; it never displays real notifications or device data. */
class ColorPreview(context: Context?) : View(context) {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip: Path = Path()
    internal var grouping: ColorGrouping = ColorGrouping.CHARACTERS
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = getResources().getDisplayMetrics().density
        val w = getWidth().toFloat()
        val h = getHeight().toFloat()
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), 20 * d, 20 * d, Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(Color.WHITE)
        paint.setColor(Color.BLACK)
        canvas.drawRect(w * .48f, 0f, w, h, paint)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 22 * d
        val value = "11/04 12:34"
        val textX = w * .48f - paint.measureText("11/04 12")
        fun samples(range: TextColorUnits.Range): DoubleArray {
            val left = textX + paint.measureText(value, 0, range.start)
            val right = textX + paint.measureText(value, 0, range.end)
            return DoubleArray(16) { i ->
                if (left + (right - left) * (i + .5f) / 16 < w * .48f) 1.0 else 0.0
            }
        }
        fun draw(range: TextColorUnits.Range, color: Int) {
            val left = textX + paint.measureText(value, 0, range.start)
            paint.color = color
            paint.style = Style.FILL
            canvas.drawText(value, range.start, range.end, left, h / 2 + 8 * d, paint)
        }
        val ranges = TextColorUnits.ranges(value, grouping, dateAndTime = true)
        val characters = TextColorUnits.ranges(value, ColorGrouping.CHARACTERS, true)
        for (range in ranges) {
            if (grouping == ColorGrouping.AUTO) {
                val glyphs = characters.filter { it.start >= range.start && it.end <= range.end }
                val analysis =
                    AutomaticColorGrouping.analyze(
                        glyphs.map { samples(it) },
                        0,
                        IntArray(glyphs.size),
                        "robust",
                        .15,
                    )
                if (analysis.preferCharacters)
                    glyphs.forEachIndexed { i, glyph -> draw(glyph, analysis.characterColors[i]) }
                else draw(range, analysis.partColor)
            } else draw(range, Contrast.choose(samples(range), 0, "robust", .15))
        }
        for (half in 0..1) {
            canvas.save()
            canvas.clipRect(if (half == 0) 0f else w * .48f, 0f, if (half == 0) w * .48f else w, h)
            paint.setColor(if (half == 0) Color.BLACK else Color.WHITE)
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            paint.setTextSize(27 * d)
            paint.setStyle(Style.FILL)
            val right: Float = w - 22 * d
            paint.setStrokeWidth(2 * d)
            paint.setStyle(Style.STROKE)
            canvas.drawRoundRect(
                right - 31 * d,
                h / 2 - 7 * d,
                right,
                h / 2 + 7 * d,
                4 * d,
                4 * d,
                paint,
            )
            paint.setStyle(Style.FILL)
            canvas.drawRoundRect(
                right - 28 * d,
                h / 2 - 4 * d,
                right - 8 * d,
                h / 2 + 4 * d,
                2 * d,
                2 * d,
                paint,
            )
            for (bar in 0..3) {
                val x: Float = right - 63 * d + bar * 6 * d
                canvas.drawRoundRect(
                    x,
                    h / 2 + (3 - bar * 3) * d,
                    x + 3 * d,
                    h / 2 + 7 * d,
                    d,
                    d,
                    paint,
                )
            }
            canvas.restore()
        }
        canvas.restore()
    }
}
