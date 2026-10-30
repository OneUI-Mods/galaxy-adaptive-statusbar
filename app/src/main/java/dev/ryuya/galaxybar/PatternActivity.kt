package dev.ryuya.galaxybar

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.View.OnClickListener
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.chip.ChipGroup.OnCheckedStateChangeListener
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import kotlin.jvm.JvmField
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Test pixels intentionally extend behind the bar; all controls use Material 3 and safe insets. */
class PatternActivity : AppCompatActivity() {
    private var pattern: PatternActivity.Pattern? = null
    private var choices: ChipGroup? = null
    private val ids: IntArray = IntArray(5)

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        val frame = FrameLayout(this)
        pattern = Pattern()
        frame.addView(pattern)
        setContentView(frame)
        val card = MaterialCardView(this)
        card.setRadius(dp(28).toFloat())
        card.setStrokeWidth(0)
        card.setCardBackgroundColor(
            MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainer)
        )
        val width = min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560))
        val position =
            android.widget.FrameLayout.LayoutParams(
                width,
                -2,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            )
        frame.addView(card, position)
        ViewCompat.setOnApplyWindowInsetsListener(
            frame,
            OnApplyWindowInsetsListener { view: View?, insets: WindowInsetsCompat? ->
                val safe = insets!!.getInsets(Type.systemBars() or Type.displayCutout())
                position.setMargins(
                    safe.left + dp(16),
                    safe.top + dp(16),
                    safe.right + dp(16),
                    safe.bottom + dp(16),
                )
                card.setLayoutParams(position)
                insets!!
            },
        )
        val content = LinearLayout(this)
        content.setOrientation(LinearLayout.VERTICAL)
        content.setPadding(dp(20), dp(16), dp(20), dp(12))
        card.addView(content)
        val title = MaterialTextView(this)
        title.setText(getString(R.string.pattern_title))
        title.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
        )
        content.addView(title)
        val info = MaterialTextView(this)
        info.setText(getString(R.string.pattern_body))
        info.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        )
        info.setTextColor(
            MaterialColors.getColor(info, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        info.setPadding(0, dp(4), 0, dp(12))
        content.addView(info)
        choices = ChipGroup(this)
        choices!!.setSingleSelection(true)
        choices!!.setSelectionRequired(true)
        choices!!.setChipSpacingHorizontal(dp(8))
        choices!!.setChipSpacingVertical(dp(4))
        content.addView(choices, android.widget.LinearLayout.LayoutParams(-1, -2))
        val labels =
            arrayOf<String?>(
                getString(R.string.white),
                getString(R.string.black),
                getString(R.string.split),
                getString(R.string.stripes),
                getString(R.string.moving),
            )
        for (i in labels.indices) {
            val button = Chip(this)
            ids[i] = View.generateViewId()
            button.setId(ids[i])
            button.setText(labels[i])
            button.setCheckable(true)
            button.setEnsureMinTouchTargetSize(true)
            choices!!.addView(button)
        }
        choices!!.setOnCheckedStateChangeListener(
            OnCheckedStateChangeListener { group: ChipGroup?, checked: MutableList<Int?>? ->
                if (checked!!.isEmpty()) return@OnCheckedStateChangeListener
                for (i in ids.indices) if (ids[i] == checked!!.get(0)) {
                    pattern!!.mode = i
                    pattern!!.invalidate()
                }
            }
        )
        val back = MaterialButton(ContextThemeWrapper(this, R.style.TonalActionTheme))
        back.setText(getString(R.string.back_settings))
        back.setOnClickListener(OnClickListener { view: View? -> finish() })
        content.addView(back)
        select(getIntent())
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        select(intent)
    }

    private fun select(intent: Intent) {
        val mode = max(0, min(4, intent.getIntExtra("pattern", 0)))
        choices!!.check(ids[mode])
    }

    private inner class Pattern : View(this@PatternActivity) {
        @JvmField var mode: Int = 0
        @JvmField val start: Long = System.nanoTime()
        @JvmField val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(if (mode == 0) Color.WHITE else Color.BLACK)
            if (mode == 2) {
                paint.setColor(Color.WHITE)
                canvas.drawRect(0f, 0f, getWidth() / 2f, getHeight().toFloat(), paint)
            }
            if (mode >= 3) {
                val stripe: Int = 40
                val offset: Int =
                    if (mode == 4) ((System.nanoTime() - start) / 10000000 % 80).toInt() else 0
                var x: Int = -80
                while (x < getWidth() + 80) {
                    paint.setColor(if (((x / stripe) and 1) == 0) Color.WHITE else Color.BLACK)
                    canvas.drawRect(
                        (x + offset).toFloat(),
                        0f,
                        (x + offset + stripe).toFloat(),
                        getHeight().toFloat(),
                        paint,
                    )
                    x += stripe
                }
            }
            if (mode == 4) postInvalidateOnAnimation()
        }
    }
}
