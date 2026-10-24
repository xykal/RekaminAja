package id.rekaminaja.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.util.Util
import kotlin.math.abs

/**
 * Facecam: preview kamera dalam jendela overlay bundar yang bisa diseret.
 * Jendela overlay ikut terekam — persis cara kerja facecam di recorder populer.
 * Tekan-tahan untuk ganti kamera depan/belakang.
 */
class FacecamController(
    private val context: Context,
    private val onError: (String) -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var container: FrameLayout? = null
    private var textureView: TextureView? = null

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    val isShowing: Boolean get() = container != null

    fun show() {
        if (container != null) return
        if (!Util.canOverlay(context)) {
            onError("overlay")
            return
        }
        val size = Util.dp(context, Prefs.facecamSizeDp)
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.DKGRAY)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        val tv = TextureView(context)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                openCamera(st)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        frame.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val lp = OverlayUtil.params(
            size, size,
            x = Util.dp(context, 16), y = Util.dp(context, 140),
            gravity = Gravity.TOP or Gravity.END
        )
        attachDrag(frame, lp)
        try {
            wm.addView(frame, lp)
            container = frame
            textureView = tv
            startBg()
        } catch (e: Exception) {
            onError("overlay")
        }
    }

    fun hide() {
        closeCamera()
        container?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        container = null
        textureView = null
        stopBg()
    }

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var downAt = 0L
            private var moved = false

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y
                        touchX = event.rawX; touchY = event.rawY
                        downAt = System.currentTimeMillis()
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) moved = true
                        // gravity END: x bertambah ke arah kiri
                        lp.x = startX - dx
                        lp.y = startY + dy
                        try {
                            wm.updateViewLayout(view, lp)
                        } catch (e: Exception) {
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved && System.currentTimeMillis() - downAt > 550) {
                            Prefs.facecamFront = !Prefs.facecamFront
                            textureView?.surfaceTexture?.let {
                                closeCamera()
                                openCamera(it)
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun startBg() {
        bgThread = HandlerThread("rekamin-facecam").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBg() {
        try {
            bgThread?.quitSafely()
        } catch (e: Exception) {
        }
        bgThread = null
        bgHandler = null
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (e: Exception) {
        }
        session = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
        }
        cameraDevice = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(surfaceTexture: SurfaceTexture) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("camera")
            return
        }
        if (bgHandler == null) startBg()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val wantFront = Prefs.facecamFront
            var chosen: String? = null
            var fallback: String? = null
            for (id in manager.cameraIdList) {
                if (fallback == null) fallback = id
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
                if (isFront == wantFront) {
                    chosen = id
                    break
                }
            }
            val cameraId = chosen ?: fallback ?: run {
                onError("camera")
                return
            }
            // Ukuran preview: pilih yang <= 1280 lebar, kalau tidak ada pakai yang pertama.
            val map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val preview = sizes?.firstOrNull { it.width <= 1280 && it.height <= 1280 }
                ?: sizes?.firstOrNull()
            if (preview != null) surfaceTexture.setDefaultBufferSize(preview.width, preview.height)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createSession(device, surfaceTexture)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                    onError("camera")
                }
            }, bgHandler)
        } catch (e: Exception) {
            onError("camera")
        }
    }

    private fun createSession(device: CameraDevice, surfaceTexture: SurfaceTexture) {
        try {
            val surface = Surface(surfaceTexture)
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply {
                                    addTarget(surface)
                                    set(
                                        CaptureRequest.CONTROL_MODE,
                                        CaptureRequest.CONTROL_MODE_AUTO
                                    )
                                }.build()
                            s.setRepeatingRequest(req, null, bgHandler)
                        } catch (e: Exception) {
                            onError("camera")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        onError("camera")
                    }
                },
                bgHandler
            )
        } catch (e: Exception) {
            onError("camera")
        }
    }
}
