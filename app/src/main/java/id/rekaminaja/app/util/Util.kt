package id.rekaminaja.app.util

import android.app.AppOpsManager
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

object Util {

    /** Ukuran layar asli (termasuk area sistem) untuk VirtualDisplay. */
    fun realDisplaySize(context: Context): Size {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Size(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            val p = Point().also { wm.defaultDisplay.getRealSize(it) }
            Size(p.x, p.y)
        }
    }

    /**
     * Terapkan preferensi resolusi (sisi pendek target, 0=native).
     * Khusus 2160 (UHD) hasilnya dimuat ke bingkai 3840x2160 dengan rasio
     * layar dipertahankan; ini satu-satunya mode yang boleh upscale.
     * Hasil diganjilkan ke kelipatan 2 (syarat encoder H.264).
     */
    fun scaledCaptureSize(display: Size, targetShortSide: Int): Size {
        val w = display.width
        val h = display.height
        if (targetShortSide <= 0) return Size(even(w), even(h))
        val shortSide = min(w, h)
        val longSide = maxOf(w, h)
        val scale = if (targetShortSide >= 2160) {
            min(2160.0 / shortSide.toDouble(), 3840.0 / longSide.toDouble())
        } else {
            if (shortSide <= targetShortSide) return Size(even(w), even(h))
            targetShortSide.toDouble() / shortSide.toDouble()
        }
        return Size(even((w * scale).roundToInt()), even((h * scale).roundToInt()))
    }

    private fun even(v: Int): Int = (v / 2 * 2).coerceAtLeast(2)

    /**
     * Bitrate otomatis berdasarkan beban pixel-frame.
     * AVC memakai ~0,10 bit/pixel-frame; HEVC ~0,06. Dibatasi 1-100 Mbps
     * lalu CaptureCapabilities dapat menurunkannya sesuai encoder hardware.
     */
    fun autoBitrate(width: Int, height: Int, fps: Int, hevc: Boolean = false): Int {
        val hundredths = if (hevc) 6L else 10L
        val v = width.toLong() * height.toLong() * fps.toLong() * hundredths / 100L
        return v.coerceIn(1_000_000L, 100_000_000L).toInt()
    }

    fun canOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** Cek izin khusus "Akses penggunaan" (untuk deteksi app sensitif di latar depan). */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024; i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).roundToInt()
}
