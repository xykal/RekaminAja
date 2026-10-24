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

    /** Resolusi = sisi pendek layar dalam px. 0 = native. */
    val RESOLUTION_OPTIONS = intArrayOf(0, 1080, 720, 540, 480)
    val FPS_OPTIONS = intArrayOf(15, 24, 30, 48, 60)

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
            val raw = sp.getString("censor_zones", null) ?: return defaultZones()
            return try {
                val arr = JSONArray(raw)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    CensorZone(
                        o.getDouble("l").toFloat(), o.getDouble("t").toFloat(),
                        o.getDouble("r").toFloat(), o.getDouble("b").toFloat()
                    )
                }
            } catch (e: Exception) {
                defaultZones()
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
            sp.edit().putString("censor_zones", arr.toString()).apply()
        }

    private fun defaultZones(): List<CensorZone> = listOf(
        // Default: area notifikasi/status di bagian atas layar.
        CensorZone(0f, 0f, 1f, 0.045f)
    )
}
