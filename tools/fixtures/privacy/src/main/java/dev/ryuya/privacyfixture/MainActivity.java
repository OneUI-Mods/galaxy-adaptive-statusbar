package dev.ryuya.privacyfixture;

import android.app.Activity;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.widget.TextView;

/** Private foreground probe. Frames and audio are discarded; no files or network. */
public final class MainActivity extends Activity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private CameraDevice camera;
  private CameraCaptureSession session;
  private ImageReader images;
  private volatile AudioRecord audio;
  private volatile boolean active;
  private TextView label;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().setStatusBarColor(android.graphics.Color.BLACK);
    getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
    label = new TextView(this);
    label.setTextColor(android.graphics.Color.WHITE);
    label.setBackgroundColor(android.graphics.Color.BLACK);
    label.setGravity(android.view.Gravity.CENTER);
    label.setText("Private privacy animation probe\nNo saved frames or audio");
    setContentView(label);
  }

  @Override
  protected void onResume() {
    super.onResume();
    active = true;
    main.postDelayed(
        () -> {
          if (!active) return;
          if (getIntent().getBooleanExtra("camera", true)) openCamera();
          if (getIntent().getBooleanExtra("mic", false)) openMic();
          main.postDelayed(
              this::stop,
              Math.max(500, Math.min(10000, getIntent().getIntExtra("duration_ms", 6000))));
        },
        1000);
  }

  private void openCamera() {
    try {
      CameraManager manager = getSystemService(CameraManager.class);
      manager.openCamera(
          manager.getCameraIdList()[0],
          new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice device) {
              if (active) {
                camera = device;
                startFrames(device);
              } else device.close();
            }

            public void onDisconnected(CameraDevice device) {
              device.close();
            }

            public void onError(CameraDevice device, int error) {
              device.close();
              label.append("\nCamera error " + error);
            }
          },
          main);
    } catch (Exception error) {
      label.append("\n" + error.getClass().getSimpleName());
    }
  }

  private void startFrames(CameraDevice device) {
    try {
      images = ImageReader.newInstance(320, 240, android.graphics.ImageFormat.YUV_420_888, 2);
      images.setOnImageAvailableListener(
          reader -> {
            try (var image = reader.acquireLatestImage()) {}
          },
          main);
      device.createCaptureSession(
          java.util.List.of(images.getSurface()),
          new CameraCaptureSession.StateCallback() {
            public void onConfigured(CameraCaptureSession ready) {
              if (!active) {
                ready.close();
                return;
              }
              session = ready;
              try {
                var request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(images.getSurface());
                ready.setRepeatingRequest(request.build(), null, main);
                label.append("\nCamera stream active; frames discarded");
              } catch (Exception e) {
                label.append("\n" + e.getClass().getSimpleName());
              }
            }

            public void onConfigureFailed(CameraCaptureSession failed) {
              failed.close();
            }
          },
          main);
    } catch (Exception e) {
      label.append("\n" + e.getClass().getSimpleName());
    }
  }

  private void openMic() {
    try {
      int size =
          Math.max(
              4096,
              AudioRecord.getMinBufferSize(
                  16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
      AudioRecord recording =
          new AudioRecord(
              MediaRecorder.AudioSource.MIC,
              16000,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              size);
      audio = recording;
      recording.startRecording();
      label.append("\nMic active; samples discarded");
      new Thread(
              () -> {
                short[] discard = new short[1024];
                try {
                  while (active && recording == audio)
                    if (recording.read(discard, 0, discard.length) < 0) break;
                } catch (Exception ignored) {
                }
              },
              "privacy-probe-discard")
          .start();
    } catch (Exception error) {
      label.append("\n" + error.getClass().getSimpleName());
    }
  }

  private void stop() {
    active = false;
    if (session != null) {
      session.close();
      session = null;
    }
    if (camera != null) {
      camera.close();
      camera = null;
    }
    if (images != null) {
      images.close();
      images = null;
    }
    AudioRecord recording = audio;
    audio = null;
    if (recording != null) {
      try {
        recording.stop();
      } catch (Exception ignored) {
      }
      recording.release();
    }
    label.append("\nStopped");
  }

  @Override
  protected void onPause() {
    super.onPause();
    main.removeCallbacksAndMessages(null);
    stop();
  }
}
