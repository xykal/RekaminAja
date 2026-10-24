package id.rekaminaja.app.recorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import id.rekaminaja.app.R
import id.rekaminaja.app.RekaminApp
import id.rekaminaja.app.util.Util

/** Ambil satu frame layar sebagai screenshot PNG lalu berhenti. */
class ScreenshotService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    @Volatile private var ready = false
    @Volatile private var done = false

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = finish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { dataExtra(it) }
        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        thread = HandlerThread("rekamin-shot").apply { start() }
        handler = Handler(thread!!.looper)
        handler!!.post { capture(resultCode, data) }
        // Timeout pengaman.
        mainHandler.postDelayed({ if (!done) finish() }, 6_000)
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val notif = NotificationCompat.Builder(this, RekaminApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentTitle(getString(R.string.screenshot))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun capture(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, data)
            projection = proj
            proj.registerCallback(callback, handler)
            val size = Util.realDisplaySize(this)
            val r = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ onImage(it, size.width, size.height) }, handler)
            virtualDisplay = proj.createVirtualDisplay(
                "RekaminShot", size.width, size.height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            // Beri waktu frame asli tergambar (frame pertama sering kosong).
            handler!!.postDelayed({ ready = true }, 350)
        } catch (e: Exception) {
            toast(R.string.screenshot_failed)
            finish()
        }
    }

    private fun onImage(reader: ImageReader, width: Int, height: Int) {
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        if (!ready || done) {
            image.close()
            return
        }
        done = true
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmpWidth = width + rowPadding / pixelStride
            val full = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)
            val cropped = if (bmpWidth == width) full
            else Bitmap.createBitmap(full, 0, 0, width, height)
            image.close()
            val uri = OutputStore.publishImage(this, cropped, OutputStore.imageDisplayName())
            if (cropped !== full) full.recycle()
            cropped.recycle()
            toast(
                if (uri != null) R.string.screenshot_saved else R.string.screenshot_failed
            )
        } catch (e: Exception) {
            try {
                image.close()
            } catch (ignored: Exception) {
            }
            toast(R.string.screenshot_failed)
        } finally {
            finish()
        }
    }

    private fun finish() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null
        try {
            projection?.unregisterCallback(callback)
        } catch (e: Exception) {
        }
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        try {
            thread?.quitSafely()
        } catch (e: Exception) {
        }
    }

    private fun toast(resId: Int) {
        mainHandler.post { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }
    }

    private fun dataExtra(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1002
    }
}
