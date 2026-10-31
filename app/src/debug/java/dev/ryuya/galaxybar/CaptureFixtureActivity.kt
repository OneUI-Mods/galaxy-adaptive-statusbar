package dev.ryuya.galaxybar

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceHolder.Callback
import android.view.SurfaceView
import android.view.WindowManager.LayoutParams
import androidx.core.view.WindowCompat

/* Private debug-only compositor fixture. No controls or additional production UI. */
class CaptureFixtureActivity : Activity(), Callback {
    private val handler: Handler = Handler()
    private var surface: SurfaceView? = null
    private var player: MediaPlayer? = null
    private var running: Boolean = false
    private val frame: Runnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                val c = surface!!.getHolder().lockCanvas()
                if (c != null) {
                    try {
                        c.drawColor(Color.BLACK)
                        val p = Paint()
                        p.setColor(Color.WHITE)
                        val offset = (SystemClock.elapsedRealtime() / 10 % 320).toInt()
                        var x: Int = -320
                        while (x < c.getWidth() + 320) {
                            c.drawRect(
                                (x + offset).toFloat(),
                                0f,
                                (x + offset + 160).toFloat(),
                                c.getHeight().toFloat(),
                                p,
                            )
                            x += 320
                        }
                    } finally {
                        surface!!.getHolder().unlockCanvasAndPost(c)
                    }
                }
                handler.postDelayed(this, 33)
            }
        }

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (getIntent().getBooleanExtra("secure", false))
            getWindow().addFlags(LayoutParams.FLAG_SECURE)
        val attrs = getWindow().getAttributes()
        attrs.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        getWindow().setAttributes(attrs)
        setRequestedOrientation(
            if (getIntent().getBooleanExtra("landscape", false))
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        )
        surface = SurfaceView(this)
        surface!!.getHolder().addCallback(this)
        setContentView(surface)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (getIntent().getBooleanExtra("video", false)) {
            player = MediaPlayer.create(this, R.raw.contrast_fixture)
            player!!.setDisplay(holder)
            player!!.setLooping(true)
            player!!.setVolume(0f, 0f)
            player!!.start()
        } else {
            running = true
            handler.post(frame)
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        handler.removeCallbacks(frame)
        if (player != null) {
            player!!.release()
            player = null
        }
    }
}
