package id.rekaminaja.app.recorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.widget.Toast
import id.rekaminaja.app.R
import id.rekaminaja.app.overlay.FloatingBubbleService
import id.rekaminaja.app.overlay.ScreenshotBubbleService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Penangkap screenshot satu frame pada VirtualDisplay persisten milik
 * ScreenRecordService.
 *
 * VirtualDisplay yang sama dipakai bergantian untuk perekaman dan screenshot
 * dengan menukar Surface-nya. Ini penting untuk Android modern: satu
 * MediaProjection tidak boleh diasumsikan bisa membuat VirtualDisplay baru
 * berulang-ulang. Saat screenshot diambil di tengah rekaman, Surface encoder
 * dipinjam sebentar oleh ImageReader lalu dikembalikan; video dapat membeku
 * sepersekian detik, tetapi rekaman dan audionya tetap berjalan.
 *
 * Frame terbaru selalu disimpan sebagai bitmap cadangan. Layar statis yang
 * hanya mengirim satu frame tidak berakhir timeout hanya karena frame itu
 * tiba sebelum status "ready".
 */
class ScreenshotCapturer(
    context: Context,
    private val virtualDisplay: VirtualDisplay,
    private val size: Size,
    private val restoreSurface: Surface?,
    private val restoreSize: Size,
    private val densityDpi: Int,
    private val handler: Handler,
    private val onDone: (Result) -> Unit
) {
    data class Result(
        val success: Boolean,
        val uri: Uri? = null,
        val thumbnail: Bitmap? = null
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val duringRecording = restoreSurface != null

    private var reader: ImageReader? = null
    private var pendingBitmap: Bitmap? = null
    private val finishedLatch = CountDownLatch(1)

    @Volatile
    private var done = false

    @Volatile
    private var readyToSave = false

    fun start() {
        // Sembunyikan kontrol RekaminAja sendiri agar bola/timer tidak ikut
        // masuk hasil screenshot. Overlay gambar/facecam tidak disentuh.
        FloatingBubbleService.setHiddenForScreenshot(true)
        ScreenshotBubbleService.setHiddenForScreenshot(true)

        handler.postDelayed({ setupReader() }, hideDelayMs())
        handler.postDelayed({
            readyToSave = true
            savePendingIfAvailable()
        }, readyDelayMs())
        handler.postDelayed({
            if (!done) finish(Result(false))
        }, timeoutMs())
    }

    fun cancel() {
        handler.post { finish(Result(false)) }
    }

    /**
     * Dipakai saat stop rekaman/akhiri sesi agar encoder tidak dilepas
     * sebelum Surface VirtualDisplay dikembalikan oleh capturer.
     */
    fun cancelAndWait(timeoutMs: Long = 1_500L) {
        if (finishedLatch.count == 0L) return
        if (Looper.myLooper() == handler.looper) {
            finish(Result(false))
            return
        }
        handler.post { finish(Result(false)) }
        try {
            finishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun setupReader() {
        if (done) return
        try {
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
            // Screenshot saat merekam boleh memakai resolusi panel asli,
            // walau encoder merekam lebih kecil. Resize hanya terjadi saat
            // Surface encoder sedang dilepas sementara.
            if (restoreSize != size) {
                virtualDisplay.resize(size.width, size.height, densityDpi)
            }
            virtualDisplay.setSurface(imageReader.surface)
        } catch (e: Exception) {
            finish(Result(false))
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
        var thumbnail: Bitmap? = null
        val uri = try {
            thumbnail = makeThumbnail(bitmap)
            OutputStore.publishImage(
                appContext,
                bitmap,
                OutputStore.imageDisplayName()
            )
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
        if (uri != null) {
            toast(R.string.screenshot_saved)
            finish(Result(true, uri, thumbnail))
        } else {
            thumbnail?.recycle()
            finish(Result(false))
        }
    }

    private fun makeThumbnail(source: Bitmap): Bitmap? {
        return try {
            val maxDimension = 360
            val largest = max(source.width, source.height)
            if (largest <= maxDimension) {
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                val scale = maxDimension.toFloat() / largest.toFloat()
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).roundToInt().coerceAtLeast(1),
                    (source.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun finish(result: Result) {
        if (done) {
            if (result.thumbnail != null) result.thumbnail.recycle()
            return
        }
        done = true
        // Handler dipakai bersama ScreenRecordService; jangan hapus antrean
        // worker lain. Callback tertunda capturer aman karena dijaga `done`.

        // Kembalikan Surface perekam sebelum ImageReader ditutup. Pada mode
        // idle, VD persisten dikembalikan ke null agar tidak menerima frame.
        try {
            if (restoreSize != size) {
                virtualDisplay.resize(restoreSize.width, restoreSize.height, densityDpi)
            }
            virtualDisplay.setSurface(restoreSurface)
        } catch (e: Exception) {
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null
        pendingBitmap?.recycle()
        pendingBitmap = null

        FloatingBubbleService.setHiddenForScreenshot(false)
        ScreenshotBubbleService.setHiddenForScreenshot(false)
        if (!result.success) toast(R.string.screenshot_failed)
        try {
            onDone(result)
        } finally {
            finishedLatch.countDown()
        }
    }

    private fun toast(resId: Int) {
        mainHandler.post {
            Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideDelayMs(): Long = if (duringRecording) 120L else 180L
    private fun readyDelayMs(): Long = if (duringRecording) 320L else 520L
    private fun timeoutMs(): Long = if (duringRecording) 2_500L else 6_000L
}
