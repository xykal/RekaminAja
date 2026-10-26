package id.rekaminaja.app.recorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import id.rekaminaja.app.R
import id.rekaminaja.app.overlay.FloatingBubbleService
import id.rekaminaja.app.overlay.ScreenshotBubbleService
import id.rekaminaja.app.util.Util

/**
 * Penangkap screenshot satu frame yang memakai MediaProjection milik
 * ScreenRecordService. Dengan begitu izin tangkapan yang sudah aktif untuk
 * rekaman juga bisa dipakai screenshot instan, tanpa dialog berulang.
 *
 * Perbaikan penting dari implementasi lama: frame terbaru selalu disimpan
 * sebagai bitmap cadangan. Layar statis yang hanya mengirim satu frame tidak
 * lagi berakhir timeout diam-diam hanya karena frame itu tiba sebelum status
 * "ready".
 */
class ScreenshotCapturer(
    context: Context,
    private val projection: MediaProjection,
    private val handler: Handler,
    private val onDone: (Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pendingBitmap: Bitmap? = null

    @Volatile
    private var done = false

    @Volatile
    private var readyToSave = false

    fun start() {
        // Sembunyikan kontrol RekaminAja sendiri agar bola/timer tidak ikut
        // masuk hasil screenshot. Overlay gambar/facecam tidak disentuh.
        FloatingBubbleService.setHiddenForScreenshot(true)
        ScreenshotBubbleService.setHiddenForScreenshot(true)

        handler.postDelayed({ setupDisplay() }, HIDE_OVERLAY_DELAY_MS)
        handler.postDelayed({
            readyToSave = true
            savePendingIfAvailable()
        }, READY_DELAY_MS)
        handler.postDelayed({
            if (!done) finish(false)
        }, TIMEOUT_MS)
    }

    fun cancel() {
        handler.post { finish(false) }
    }

    private fun setupDisplay() {
        if (done) return
        try {
            val size = Util.realDisplaySize(appContext)
            val imageReader = ImageReader.newInstance(
                size.width,
                size.height,
                PixelFormat.RGBA_8888,
                2
            )
            reader = imageReader
            imageReader.setOnImageAvailableListener({ reader ->
                onImageAvailable(reader, size.width, size.height)
            }, handler)
            virtualDisplay = projection.createVirtualDisplay(
                "RekaminShot",
                size.width,
                size.height,
                appContext.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            ) ?: throw IllegalStateException("VirtualDisplay screenshot null")
        } catch (e: Exception) {
            finish(false)
        }
    }

    private fun onImageAvailable(reader: ImageReader, width: Int, height: Int) {
        if (done) return
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val bitmap = image.toBitmap(width, height)
            val old = pendingBitmap
            pendingBitmap = bitmap
            if (old != null && old !== bitmap) old.recycle()
        } catch (e: Exception) {
            // Frame rusak sesekali tidak perlu membatalkan seluruh percobaan;
            // frame berikutnya atau timeout akan menentukan hasil akhir.
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
            }
        }

        if (readyToSave) savePendingIfAvailable()
    }

    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride.coerceAtLeast(4)
        val rowStride = plane.rowStride
        val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
        val paddedWidth = width + rowPadding / pixelStride
        val full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return full
        val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
        if (cropped !== full) full.recycle()
        return cropped
    }

    private fun savePendingIfAvailable() {
        if (done) return
        val bitmap = pendingBitmap ?: return
        pendingBitmap = null
        val saved = try {
            OutputStore.publishImage(
                appContext,
                bitmap,
                OutputStore.imageDisplayName()
            ) != null
        } catch (e: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
        if (saved) toast(R.string.screenshot_saved)
        finish(saved)
    }

    private fun finish(success: Boolean) {
        if (done) return
        done = true
        // Handler dipakai bersama ScreenRecordService; jangan hapus antrean
        // worker lain. Callback tertunda capturer aman karena dijaga `done`.

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
        pendingBitmap?.recycle()
        pendingBitmap = null

        FloatingBubbleService.setHiddenForScreenshot(false)
        ScreenshotBubbleService.setHiddenForScreenshot(false)
        if (!success) toast(R.string.screenshot_failed)
        onDone(success)
    }

    private fun toast(resId: Int) {
        mainHandler.post {
            Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val HIDE_OVERLAY_DELAY_MS = 180L
        const val READY_DELAY_MS = 520L
        const val TIMEOUT_MS = 6_000L
    }
}
