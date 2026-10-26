package id.rekaminaja.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Satu rect zona sensor dalam koordinat relatif 0..1 terhadap layar. */
data class CensorZone(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Semua pengaturan RekaminAja dalam satu SharedPreferences. */
object Prefs {

    const val AUDIO_NONE = "none"
    const val AUDIO_MIC = "mic"
    const val AUDIO_INTERNAL = "internal"
    const val AUDIO_MIX = "mix"

    const val CODEC_AVC = "avc"
    const val CODEC_HEVC = "hevc"

    /**
     * Resolusi = sisi pendek target dalam px. 0 = native, 2160 = UHD yang
     * dimuat ke bingkai 3840x2160 (boleh upscale demi metadata/kompatibilitas
     * platform; detail tetap berasal dari panel).
     */
    val RESOLUTION_OPTIONS = intArrayOf(2160, 0, 1440, 1080, 720, 540, 480)
    val FPS_OPTIONS = intArrayOf(15, 24, 30, 48, 60, 90, 120)

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("rekaminaja", Context.MODE_PRIVATE)
    }

    var resolution: Int
        get() = sp.getInt("resolution", 0)
        set(v) = sp.edit().putInt("resolution", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()

    /** Mbps. 0 = otomatis. */
    var bitrateMbps: Int
        get() = sp.getInt("bitrate", 0)
        set(v) = sp.edit().putInt("bitrate", v).apply()

    /** Codec video: avc (H.264) atau hevc (H.265). Otomatis fallback AVC bila encoder tidak mendukung. */
    var videoCodec: String
        get() = sp.getString("video_codec", CODEC_AVC) ?: CODEC_AVC
        set(v) = sp.edit().putString("video_codec", v).apply()

    /** Berhenti otomatis setelah N menit waktu rekaman aktif. 0 = mati. */
    var autoStopMinutes: Int
        get() = sp.getInt("auto_stop_min", 0)
        set(v) = sp.edit().putInt("auto_stop_min", v.coerceAtLeast(0)).apply()

    /** Sembunyikan bola selama merekam agar tidak masuk hasil video. */
    var hideBubbleWhileRecording: Boolean
        get() = sp.getBoolean("hide_bubble_rec", false)
        set(v) = sp.edit().putBoolean("hide_bubble_rec", v).apply()

    var audioSource: String
        get() = sp.getString("audio", AUDIO_MIC) ?: AUDIO_MIC
        set(v) = sp.edit().putString("audio", v).apply()

    // ---- Efek mic gaya podcast (semua opt-in, default mati = suara mentah) ----

    /** Efek sistem: NoiseSuppressor + AcousticEchoCanceler + AutomaticGainControl. */
    var fxNoiseSystem: Boolean
        get() = sp.getBoolean("fx_ns", false)
        set(v) = sp.edit().putBoolean("fx_ns", v).apply()

    /** Noise gate DSP sendiri: keheningan benar-benar hening. */
    var fxGate: Boolean
        get() = sp.getBoolean("fx_gate", false)
        set(v) = sp.edit().putBoolean("fx_gate", v).apply()

    /** Kompresor + limiter DSP sendiri: suara rata dan padat. */
    var fxCompressor: Boolean
        get() = sp.getBoolean("fx_comp", false)
        set(v) = sp.edit().putBoolean("fx_comp", v).apply()

    /** Makeup gain kompresor dalam dB (0..12). */
    var fxGainDb: Int
        get() = sp.getInt("fx_gain", 3)
        set(v) = sp.edit().putInt("fx_gain", v.coerceIn(0, 12)).apply()

    val anyAudioFx: Boolean
        get() = fxNoiseSystem || fxGate || fxCompressor

    // ---- Deck Efek Suara v1.2 (preset karakter suara, live dari floating) ----

    /** Id preset VoiceFx: normal, clean, bass, echo, reverb, alien, robot, chipmunk. */
    var voicePreset: String
        get() = sp.getString("voice_preset", "normal") ?: "normal"
        set(v) = sp.edit().putString("voice_preset", v).apply()

    /** Intensitas efek preset 0..100. */
    var fxIntensity: Int
        get() = sp.getInt("fx_intensity", 70)
        set(v) = sp.edit().putInt("fx_intensity", v.coerceIn(0, 100)).apply()

    /** Volume mikrofon (gain) 10..200 persen. 100 = apa adanya. */
    var micVolume: Int
        get() = sp.getInt("mic_volume", 100)
        set(v) = sp.edit().putInt("mic_volume", v.coerceIn(10, 200)).apply()

    /** True bila jalur codec+DSP diperlukan untuk mic pada rekaman berikutnya. */
    val micFxActive: Boolean
        get() = anyAudioFx || voicePreset != "normal" || micVolume != 100

    // ---- Posisi bola melayang (nempel kiri/kanan, diingat antar sesi) ----

    /** 0 = kiri, 1 = kanan. */
    var bubbleSide: Int
        get() = sp.getInt("bubble_side", 1)
        set(v) = sp.edit().putInt("bubble_side", v).apply()

    /** Posisi Y bola dalam px dari atas layar. -1 = belum pernah digeser. */
    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    /** Gaya XRecorder: setelah diam, setengah bola menyelip ke luar tepi layar. */
    var bubbleEdgeTuck: Boolean
        get() = sp.getBoolean("bubble_edge_tuck", true)
        set(v) = sp.edit().putBoolean("bubble_edge_tuck", v).apply()

    /** Ukuran bola dalam dp. Default tetap mungil 38dp seperti permintaan sebelumnya. */
    var bubbleSizeDp: Int
        get() = sp.getInt("bubble_size_dp", 38).coerceIn(34, 56)
        set(v) = sp.edit().putInt("bubble_size_dp", v.coerceIn(34, 56)).apply()

    /** Opasitas dasar bola sebelum efek redup/tuck saat idle. */
    var bubbleOpacityPercent: Int
        get() = sp.getInt("bubble_opacity", 92).coerceIn(35, 100)
        set(v) = sp.edit().putInt("bubble_opacity", v.coerceIn(35, 100)).apply()

    /** Chip waktu terpisah di samping bola selama merekam. */
    var bubbleShowTimer: Boolean
        get() = sp.getBoolean("bubble_show_timer", true)
        set(v) = sp.edit().putBoolean("bubble_show_timer", v).apply()

    /** Bola screenshot terpisah ala XRecorder, independen dari bola rekam. */
    var screenshotBubble: Boolean
        get() = sp.getBoolean("screenshot_bubble", true)
        set(v) = sp.edit().putBoolean("screenshot_bubble", v).apply()

    /** 0 = kiri, 1 = kanan. */
    var screenshotBubbleSide: Int
        get() = sp.getInt("screenshot_bubble_side", 1)
        set(v) = sp.edit().putInt("screenshot_bubble_side", v).apply()

    /** Posisi Y bola screenshot dalam px. -1 = posisi awal otomatis. */
    var screenshotBubbleY: Int
        get() = sp.getInt("screenshot_bubble_y", -1)
        set(v) = sp.edit().putInt("screenshot_bubble_y", v).apply()

    /** Petunjuk sekali tampil di samping bola screenshot baru. */
    var screenshotBubbleHintShown: Boolean
        get() = sp.getBoolean("screenshot_bubble_hint_shown", false)
        set(v) = sp.edit().putBoolean("screenshot_bubble_hint_shown", v).apply()

    /** Migrasi v1.5: beri jarak awal lebih lega antara dua bola tepi. */
    var bubbleSpacingV150: Boolean
        get() = sp.getBoolean("bubble_spacing_v150", false)
        set(v) = sp.edit().putBoolean("bubble_spacing_v150", v).apply()

    var countdownSec: Int
        get() = sp.getInt("countdown", 3)
        set(v) = sp.edit().putInt("countdown", v).apply()

    var floatingBubble: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) = sp.edit().putBoolean("bubble", v).apply()

    var facecamEnabled: Boolean
        get() = sp.getBoolean("facecam", false)
        set(v) = sp.edit().putBoolean("facecam", v).apply()

    var facecamSizeDp: Int
        get() = sp.getInt("facecam_size", 118)
        set(v) = sp.edit().putInt("facecam_size", v).apply()

    var facecamFront: Boolean
        get() = sp.getBoolean("facecam_front", true)
        set(v) = sp.edit().putBoolean("facecam_front", v).apply()

    var censorEnabled: Boolean
        get() = sp.getBoolean("censor", false)
        set(v) = sp.edit().putBoolean("censor", v).apply()

    var autoPauseSensitive: Boolean
        get() = sp.getBoolean("autopause", false)
        set(v) = sp.edit().putBoolean("autopause", v).apply()

    var sensitivePackages: Set<String>
        get() = sp.getStringSet("sensitive_pkgs", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("sensitive_pkgs", v).apply()

    var censorZones: List<CensorZone>
        get() {
            val raw = sp.getString("censor_zones", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val zones = List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    CensorZone(
                        o.getDouble("l").toFloat(), o.getDouble("t").toFloat(),
                        o.getDouble("r").toFloat(), o.getDouble("b").toFloat()
                    )
                }
                // Migrasi v1.2: zona bawaan lama adalah pita hitam penuh di
                // bagian atas layar. Pengguna mengira itu bug ("ada hitam di
                // atas pas rekam"), jadi pola persis zona bawaan lama dibuang
                // kecuali pengguna pernah mengedit zona sendiri.
                if (!sp.getBoolean("zones_user_edited", false)) {
                    zones.filterNot { isLegacyDefaultZone(it) }
                } else {
                    zones
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            for (z in v) {
                arr.put(
                    org.json.JSONObject()
                        .put("l", z.left.toDouble()).put("t", z.top.toDouble())
                        .put("r", z.right.toDouble()).put("b", z.bottom.toDouble())
                )
            }
            sp.edit()
                .putString("censor_zones", arr.toString())
                .putBoolean("zones_user_edited", true)
                .apply()
        }

    private fun isLegacyDefaultZone(z: CensorZone): Boolean =
        z.left == 0f && z.top == 0f && z.right == 1f && z.bottom == 0.045f
}
