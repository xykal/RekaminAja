package id.rekaminaja.app.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Size
import android.view.Display
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.util.Util
import kotlin.math.roundToInt

/**
 * Perencana kualitas berbasis kemampuan perangkat.
 *
 * Android tidak menjamin semua HP bisa meng-encode 4K/120 FPS: batasnya ada
 * di refresh layar dan encoder hardware. Object ini mengubah preferensi user
 * menjadi target yang benar-benar didukung (ukuran, FPS, codec, bitrate),
 * agar mode UHD tidak gagal mulai atau memaksa encoder software tanpa sadar.
 */
object CaptureCapabilities {

    data class Plan(
        val size: Size,
        val fps: Int,
        val bitrate: Int,
        val mime: String,
        val encoderName: String?,
        val displaySize: Size,
        val maxRefreshHz: Int,
        val requestedSize: Size,
        val requestedFps: Int,
        val requestedMime: String
    ) {
        val adjusted: Boolean
            get() = size != requestedSize || fps != requestedFps || mime != requestedMime

        val codecLabel: String
            get() = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"
    }

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedPlan: Plan? = null

    // MediaCodecList dibuat sekali; planner dipanggil dari Settings dan saat
    // mulai rekam, jadi tidak perlu memindai daftar codec berulang-ulang.
    private val codecList: MediaCodecList by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
    }

    fun plan(context: Context): Plan {
        val displaySize = Util.realDisplaySize(context)
        val key = listOf(
            displaySize.width, displaySize.height,
            Prefs.resolution, Prefs.fps, Prefs.bitrateMbps, Prefs.videoCodec
        ).joinToString("|")
        cachedPlan?.let { if (cachedKey == key) return it }

        val requestedSize = Util.scaledCaptureSize(displaySize, Prefs.resolution)
        val requestedMime = if (Prefs.videoCodec == Prefs.CODEC_HEVC) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val refresh = maxDisplayRefreshHz(context)
        var mime = requestedMime

        // Turunkan ukuran bertahap sampai ada encoder yang sanggup. HEVC
        // diberi kesempatan pada ukuran lebih kecil dulu; fallback ke AVC
        // hanya bila seluruh tangga ukuran HEVC tidak didukung.
        var selection = selectSupportedSize(mime, requestedSize)
        if (!selection.second && mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            mime = MediaFormat.MIMETYPE_VIDEO_AVC
            selection = selectSupportedSize(mime, requestedSize)
        }
        val selectedSize = selection.first

        // FPS dibatasi refresh layar lalu kemampuan encoder pada ukuran final.
        val fpsCeiling = minOf(Prefs.fps, refresh).coerceAtLeast(15)
        var selectedFps = minOf(30, fpsCeiling)
        var encoderName: String? = null
        val fpsCandidates = Prefs.FPS_OPTIONS
            .filter { it <= fpsCeiling }
            .sortedDescending()
            .ifEmpty { listOf(selectedFps) }
        for (candidate in fpsCandidates) {
            val name = encoderNameFor(
                mime, selectedSize.width, selectedSize.height, candidate
            )
            if (name != null) {
                selectedFps = candidate
                encoderName = name
                break
            }
        }

        val bitrate = cappedBitrateFor(mime, selectedSize, selectedFps)
        if (encoderName == null) {
            encoderName = encoderNameFor(
                mime, selectedSize.width, selectedSize.height, selectedFps, bitrate
            )
        }

        val result = Plan(
            size = selectedSize,
            fps = selectedFps,
            bitrate = bitrate,
            mime = mime,
            encoderName = encoderName,
            displaySize = displaySize,
            maxRefreshHz = refresh,
            requestedSize = requestedSize,
            requestedFps = Prefs.fps,
            requestedMime = requestedMime
        )
        cachedKey = key
        cachedPlan = result
        return result
    }

    fun fpsOptions(context: Context): IntArray {
        val refresh = maxDisplayRefreshHz(context)
        val values = Prefs.FPS_OPTIONS.filter { it <= 30 || it <= refresh }
        return values.toIntArray().ifEmpty { intArrayOf(15, 24, 30) }
    }

    fun maxDisplayRefreshHz(context: Context): Int {
        val display = try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (e: Exception) {
            null
        } ?: return 60
        var maxRefresh = try {
            display.refreshRate
        } catch (e: Exception) {
            60f
        }
        try {
            @Suppress("NewApi")
            for (mode in display.supportedModes) {
                if (mode.refreshRate > maxRefresh) maxRefresh = mode.refreshRate
            }
        } catch (e: Exception) {
            // supportedModes tidak tersedia di sebagian perangkat lama.
        }
        return maxRefresh.roundToInt().coerceIn(24, 240)
    }

    fun encoderNameFor(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int = cappedBitrateFor(mime, Size(width, height), fps)
    ): String? {
        return try {
            val name = codecList.findEncoderForFormat(
                encoderFormat(mime, width, height, fps, bitrate)
            ) ?: return null
            // Target di atas 1080p60 tidak layak dipaksakan ke encoder
            // software: frame akan drop berat. Perlakukan software-only
            // sebagai tidak didukung agar planner menurunkan target.
            val demanding = width.toLong() * height.toLong() * fps.toLong() >
                1920L * 1080L * 60L
            if (!demanding || isHardwareEncoder(name)) name else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isHardwareEncoder(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith("omx.google.") || lower.startsWith("c2.android.")) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            codecList.codecInfos
                .firstOrNull { it.name == name }
                ?.isHardwareAccelerated ?: true
        } catch (e: Exception) {
            true
        }
    }

    fun encoderFormat(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): MediaFormat {
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            try {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            } catch (e: Exception) {
                // Mode bitrate bersifat opsional; default encoder tetap OK.
            }
        }
    }

    private fun rawBitrateFor(mime: String, size: Size, fps: Int): Int {
        return if (Prefs.bitrateMbps > 0) {
            Prefs.bitrateMbps * 1_000_000
        } else {
            Util.autoBitrate(
                size.width,
                size.height,
                fps,
                hevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
            )
        }
    }

    private fun cappedBitrateFor(mime: String, size: Size, fps: Int): Int {
        val raw = rawBitrateFor(mime, size, fps)
        val cap = bitrateCapFor(mime, size)
        return if (cap != null && cap > 0) {
            raw.coerceIn(1_000_000, cap)
        } else {
            raw
        }
    }

    private fun bitrateCapFor(mime: String, size: Size): Int? {
        var best: Int? = null
        try {
            val infos = codecList.codecInfos
            for (info in infos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val caps = try {
                    info.getCapabilitiesForType(mime)
                } catch (e: Exception) {
                    continue
                }
                val video = caps.videoCapabilities ?: continue
                val supports = try {
                    video.isSizeSupported(size.width, size.height)
                } catch (e: Exception) {
                    false
                }
                if (!supports) continue
                val upper = caps.bitrateRange.upper
                if (upper > 0 && (best == null || upper > best)) best = upper
            }
        } catch (e: Exception) {
            return null
        }
        return best
    }

    /** Ukuran terpilih + apakah ada encoder yang mendukungnya pada 30 FPS. */
    private fun selectSupportedSize(mime: String, requested: Size): Pair<Size, Boolean> {
        val scales = doubleArrayOf(1.0, 0.85, 0.75, 2.0 / 3.0, 0.5)
        var last = requested
        for (scale in scales) {
            val candidate = scaleSize(requested, scale)
            last = candidate
            if (encoderNameFor(mime, candidate.width, candidate.height, 30) != null) {
                return candidate to true
            }
        }
        return last to false
    }

    private fun scaleSize(size: Size, scale: Double): Size {
        if (scale >= 1.0) return size
        return Size(
            even((size.width * scale).roundToInt()),
            even((size.height * scale).roundToInt())
        )
    }

    private fun even(v: Int): Int = (v / 2 * 2).coerceAtLeast(2)
}
