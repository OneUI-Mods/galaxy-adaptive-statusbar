package dev.ryuya.galaxybar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import kotlin.math.round

class AboutActivity : AppCompatActivity() {
    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(
            root,
            OnApplyWindowInsetsListener { view: View?, insets: WindowInsetsCompat? ->
                val safe = insets!!.getInsets(Type.systemBars() or Type.displayCutout())
                view!!.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets!!
            },
        )
        val toolbar = MaterialToolbar(this)
        toolbar.setTitle(R.string.about)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationContentDescription(R.string.back)
        toolbar.setNavigationOnClickListener(OnClickListener { v: View? -> finish() })
        root.addView(toolbar, LayoutParams(-1, dp(64)))
        val scroll = NestedScrollView(this)
        root.addView(scroll, LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this)
        content.setOrientation(LinearLayout.VERTICAL)
        content.setPadding(dp(24), dp(24), dp(24), dp(24))
        scroll.addView(content)
        val icon = ShapeableImageView(this)
        icon.setImageResource(R.mipmap.ic_launcher)
        icon.setContentDescription(getString(R.string.app_name))
        content.addView(icon, LayoutParams(dp(96), dp(96)))
        val name = MaterialTextView(this)
        name.setText(R.string.app_name)
        name.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall
        )
        content.addView(name)
        val version = MaterialTextView(this)
        version.setText(getString(R.string.version, BuildConfig.VERSION_NAME))
        content.addView(version)
        addButton(
            content,
            R.string.source,
            Runnable {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.SOURCE_URL)))
            },
        )
        addButton(
            content,
            R.string.project_license,
            Runnable {
                startActivity(
                    Intent(this, LicensesActivity::class.java).putExtra("library", "project")
                )
            },
        )
        addButton(
            content,
            R.string.open_source,
            Runnable { startActivity(Intent(this, LicensesActivity::class.java)) },
        )
        addButton(content, R.string.back, Runnable { this.finish() })
    }

    private fun dp(n: Int): Int {
        return Math.round(n * getResources().getDisplayMetrics().density)
    }

    private fun addButton(parent: LinearLayout, label: Int, action: Runnable) {
        val button = MaterialButton(this)
        button.setText(label)
        button.setOnClickListener(OnClickListener { view: View? -> action.run() })
        parent.addView(button, LayoutParams(-1, -2))
    }

    companion object {
        const val SOURCE_URL: String = "https://github.com/ryuya0124/galaxy-adaptive-statusbar"
    }
}
