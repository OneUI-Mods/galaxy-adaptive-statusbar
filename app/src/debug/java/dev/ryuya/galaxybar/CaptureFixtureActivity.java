package dev.ryuya.galaxybar;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import androidx.core.view.WindowCompat;

/** Private debug-only compositor fixture. No controls or additional production UI. */
public final class CaptureFixtureActivity extends Activity implements SurfaceHolder.Callback {
  private final Handler handler = new Handler();
  private SurfaceView surface;
  private MediaPlayer player;
  private boolean running;
  private final Runnable frame =
      new Runnable() {
        @Override
        public void run() {
          if (!running) return;
          Canvas c = surface.getHolder().lockCanvas();
          if (c != null) {
            try {
              c.drawColor(Color.BLACK);
              Paint p = new Paint();
              p.setColor(Color.WHITE);
              int offset = (int) (android.os.SystemClock.elapsedRealtime() / 10 % 320);
              for (int x = -320; x < c.getWidth() + 320; x += 320)
                c.drawRect(x + offset, 0, x + offset + 160, c.getHeight(), p);
            } finally {
              surface.getHolder().unlockCanvasAndPost(c);
            }
          }
          handler.postDelayed(this, 33);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (getIntent().getBooleanExtra("secure", false))
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    WindowManager.LayoutParams attrs = getWindow().getAttributes();
    attrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    getWindow().setAttributes(attrs);
    setRequestedOrientation(
        getIntent().getBooleanExtra("landscape", false)
            ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    surface = new SurfaceView(this);
    surface.getHolder().addCallback(this);
    setContentView(surface);
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    if (getIntent().getBooleanExtra("video", false)) {
      player = MediaPlayer.create(this, R.raw.contrast_fixture);
      player.setDisplay(holder);
      player.setLooping(true);
      player.setVolume(0, 0);
      player.start();
    } else {
      running = true;
      handler.post(frame);
    }
  }

  @Override
  public void surfaceChanged(SurfaceHolder h, int f, int w, int height) {}

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    running = false;
    handler.removeCallbacks(frame);
    if (player != null) {
      player.release();
      player = null;
    }
  }
}
