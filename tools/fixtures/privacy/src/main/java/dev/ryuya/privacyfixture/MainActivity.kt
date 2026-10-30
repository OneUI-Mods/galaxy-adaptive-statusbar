package dev.ryuya.privacyfixture

import android.app.Activity
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.MediaRecorder.AudioSource
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.widget.TextView
import java.util.List
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/* Private foreground probe. Frames and audio are discarded; no files or network. */
class MainActivity : Activity() {
    private val main: Handler = Handler(Looper.getMainLooper())
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    @Volatile private var audio: AudioRecord? = null
    @Volatile private var active: Boolean = false
    private var label: TextView? = null

    public override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        getWindow().setStatusBarColor(Color.BLACK)
        getWindow().setNavigationBarColor(Color.BLACK)
        label = TextView(this)
        label!!.setTextColor(Color.WHITE)
        label!!.setBackgroundColor(Color.BLACK)
        label!!.setGravity(Gravity.CENTER)
        label!!.setText("Private privacy animation probe\nNo saved frames or audio")
        setContentView(label)
    }

    override fun onResume() {
        super.onResume()
        active = true
        main.postDelayed(
            Runnable {
                if (!active) return@Runnable
                if (getIntent().getBooleanExtra("camera", true)) openCamera()
                if (getIntent().getBooleanExtra("mic", false)) openMic()
                main.postDelayed(
                    Runnable { this.stop() },
                    (max(500, min(10000, getIntent().getIntExtra("duration_ms", 6000)))).toLong(),
                )
            },
            1000,
        )
    }

    private fun openCamera() {
        try {
            val manager = getSystemService<CameraManager?>(CameraManager::class.java)
            manager.openCamera(
                manager.getCameraIdList()[0],
                object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (active) {
                            camera = device
                            startFrames(device)
                        } else device.close()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        label!!.append("\nCamera error " + error)
                    }
                },
                main,
            )
        } catch (error: Exception) {
            label!!.append("\n" + error.javaClass.getSimpleName())
        }
    }

    private fun startFrames(device: CameraDevice) {
        try {
            images = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
            images!!.setOnImageAvailableListener(
                OnImageAvailableListener { reader: ImageReader? ->
                    reader!!.acquireLatestImage().use { image -> }
                },
                main,
            )
            device.createCaptureSession(
                List.of<Surface?>(images!!.getSurface()),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(ready: CameraCaptureSession) {
                        if (!active) {
                            ready.close()
                            return
                        }
                        session = ready
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(images!!.getSurface())
                            ready.setRepeatingRequest(request.build(), null, main)
                            label!!.append("\nCamera stream active; frames discarded")
                        } catch (e: Exception) {
                            label!!.append("\n" + e.javaClass.getSimpleName())
                        }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        failed.close()
                    }
                },
                main,
            )
        } catch (e: Exception) {
            label!!.append("\n" + e.javaClass.getSimpleName())
        }
    }

    private fun openMic() {
        try {
            val size =
                max(
                    4096,
                    AudioRecord.getMinBufferSize(
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                )
            val recording =
                AudioRecord(
                    AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    size,
                )
            audio = recording
            recording.startRecording()
            label!!.append("\nMic active; samples discarded")
            Thread(
                    Runnable {
                        val discard = ShortArray(1024)
                        try {
                            while (active && recording === audio) if (
                                recording.read(discard, 0, discard.size) < 0
                            )
                                break
                        } catch (ignored: Exception) {}
                    },
                    "privacy-probe-discard",
                )
                .start()
        } catch (error: Exception) {
            label!!.append("\n" + error.javaClass.getSimpleName())
        }
    }

    private fun stop() {
        active = false
        if (session != null) {
            session!!.close()
            session = null
        }
        if (camera != null) {
            camera!!.close()
            camera = null
        }
        if (images != null) {
            images!!.close()
            images = null
        }
        val recording: AudioRecord? = audio
        audio = null
        if (recording != null) {
            try {
                recording.stop()
            } catch (ignored: Exception) {}
            recording.release()
        }
        label!!.append("\nStopped")
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
        stop()
    }
}
