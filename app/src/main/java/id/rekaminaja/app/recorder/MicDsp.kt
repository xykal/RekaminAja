package id.rekaminaja.app.recorder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Preset karakter suara mikrofon (Deck Efek Suara). */
enum class VoicePreset(val id: String) {
    NORMAL("normal"),
    CLEAN("clean"),
    BASS("bass"),
    ECHO("echo"),
    REVERB("reverb"),
    ALIEN("alien"),
    ROBOT("robot"),
    CHIPMUNK("chipmunk");

    companion object {
        fun fromId(id: String?): VoicePreset =
            values().firstOrNull { it.id == id } ?: NORMAL
    }
}

/**
 * Keadaan efek suara yang bisa diubah langsung (live) dari floating deck /
 * notifikasi selagi merekam. Thread audio membaca nilai volatile ini sekali
 * per buffer, jadi tidak ada lock di jalur audio.
 */
object VoiceFxState {
    @Volatile
    var preset: VoicePreset = VoicePreset.NORMAL

    /** 0.0..1.0 */
    @Volatile
    var intensity: Float = 0.7f

    /** Gain linear volume mic. 1.0 = 100%. */
    @Volatile
    var micGain: Float = 1f

    fun sync(presetId: String, intensityPercent: Int, micVolumePercent: Int) {
        preset = VoicePreset.fromId(presetId)
        intensity = (intensityPercent.coerceIn(0, 100)) / 100f
        micGain = (micVolumePercent.coerceIn(10, 200)) / 100f
    }
}

/**
 * DSP mikrofon gaya podcast + Deck Efek Suara, ditulis sendiri untuk PCM
 * 16-bit little-endian. Thread audio saja yang memanggil [process].
 *
 * Rantai per frame:
 * volume mic -> high-pass -> noise gate adaptif -> EQ (ngebas/presence) ->
 * kompresor -> karakter preset (echo/gema/alien/robot/cempreng) -> limiter.
 */
class MicDsp(
    private val sampleRate: Int,
    private val gateEnabled: Boolean,
    private val compressorEnabled: Boolean,
    makeupDb: Int
) {
    private val makeupLinear: Float = 10.0.pow(makeupDb / 20.0).toFloat()

    private inner class Biquad(private val b0: Float, private val b1: Float, private val b2: Float,
                               private val a1: Float, private val a2: Float) {
        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f
        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun lowShelf(freq: Double, gainDb: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * freq / sampleRate
        val alpha = sin(w) / 2.0 * sqrt(2.0)
        val cw = cos(w)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val b0 = a * ((a + 1) - (a - 1) * cw + twoSqrtAAlpha)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cw)
        val b2 = a * ((a + 1) - (a - 1) * cw - twoSqrtAAlpha)
        val a0 = (a + 1) + (a - 1) * cw + twoSqrtAAlpha
        val a1 = -2 * ((a - 1) + (a + 1) * cw)
        val a2 = (a + 1) + (a - 1) * cw - twoSqrtAAlpha
        return Biquad(
            (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
            (a1 / a0).toFloat(), (a2 / a0).toFloat()
        )
    }

    private fun peaking(freq: Double, gainDb: Double, q: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * freq / sampleRate
        val alpha = sin(w) / (2.0 * q)
        val cw = cos(w)
        val b0 = 1 + alpha * a
        val b1 = -2 * cw
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cw
        val a2 = 1 - alpha / a
        return Biquad(
            (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
            (a1 / a0).toFloat(), (a2 / a0).toFloat()
        )
    }

    /** State per kanal. Buffer efek karakter dialokasikan malas (lazy). */
    private inner class Channel {
        var hpPrevIn = 0f
        var hpPrevOut = 0f
        val shelf = lowShelf(160.0, 7.0)
        val presence = peaking(3200.0, 3.5, 0.9)

        var echoBuf: FloatArray? = null
        var echoIdx = 0

        var combBufs: Array<FloatArray>? = null
        var combIdx: IntArray? = null
        var combDamp: FloatArray? = null
        var apBufs: Array<FloatArray>? = null
        var apIdx: IntArray? = null

        var pitchBuf: FloatArray? = null
        var pitchWrite = 0
        var pitchPhase = 0f
    }

    private val channelsState = arrayOf(Channel(), Channel())
    private val hpAlpha: Float = run {
        val rc = 1.0 / (2.0 * PI * 80.0)
        val dt = 1.0 / sampleRate
        (rc / (rc + dt)).toFloat()
    }

    private var envelope = 0f
    private var noiseFloor = 0.004f
    private var gateGain = 1f
    private var compGain = 1f
    private var ringPhase = 0.0

    private fun echoProcess(ch: Channel, x: Float, wet: Float): Float {
        val size = (sampleRate * 0.17f).toInt().coerceAtLeast(1)
        var buf = ch.echoBuf
        if (buf == null || buf.size != size) {
            buf = FloatArray(size)
            ch.echoBuf = buf
            ch.echoIdx = 0
        }
        val delayed = buf[ch.echoIdx]
        buf[ch.echoIdx] = x + delayed * 0.34f
        ch.echoIdx = (ch.echoIdx + 1) % size
        return x * (1f - wet * 0.4f) + delayed * wet
    }

    private fun reverbProcess(ch: Channel, x: Float, wet: Float): Float {
        if (ch.combBufs == null) {
            val scale = sampleRate / 44100f
            val combSizes = intArrayOf(1116, 1188, 1277, 1356)
                .map { (it * scale).toInt().coerceAtLeast(16) }
            val apSizes = intArrayOf(556, 441)
                .map { (it * scale).toInt().coerceAtLeast(16) }
            ch.combBufs = Array(combSizes.size) { FloatArray(combSizes[it]) }
            ch.combIdx = IntArray(combSizes.size)
            ch.combDamp = FloatArray(combSizes.size)
            ch.apBufs = Array(apSizes.size) { FloatArray(apSizes[it]) }
            ch.apIdx = IntArray(apSizes.size)
        }
        val combs = ch.combBufs!!
        val cIdx = ch.combIdx!!
        val damp = ch.combDamp!!
        var acc = 0f
        for (i in combs.indices) {
            val buf = combs[i]
            val idx = cIdx[i]
            val out = buf[idx]
            damp[i] = out * 0.25f + damp[i] * 0.75f // peredam tinggi di loop
            buf[idx] = x + damp[i] * 0.76f
            cIdx[i] = (idx + 1) % buf.size
            acc += out
        }
        acc *= 0.25f
        val aps = ch.apBufs!!
        val aIdx = ch.apIdx!!
        for (i in aps.indices) {
            val buf = aps[i]
            val idx = aIdx[i]
            val bufOut = buf[idx]
            val out = -acc + bufOut
            buf[idx] = acc + bufOut * 0.5f
            aIdx[i] = (idx + 1) % buf.size
            acc = out
        }
        return x * (1f - wet) + acc * wet * 1.4f
    }

    /** Pitch shifter ranah-waktu dua tap (crossfade) untuk efek cempreng. */
    private fun pitchProcess(ch: Channel, x: Float, ratio: Float): Float {
        val size = 4096
        val window = 1024f
        var buf = ch.pitchBuf
        if (buf == null) {
            buf = FloatArray(size)
            ch.pitchBuf = buf
            ch.pitchWrite = 0
            ch.pitchPhase = 0f
        }
        buf[ch.pitchWrite] = x
        ch.pitchPhase += (1f - ratio)
        while (ch.pitchPhase < 0f) ch.pitchPhase += window
        while (ch.pitchPhase >= window) ch.pitchPhase -= window

        fun read(pos: Float): Float {
            val p = ch.pitchWrite - pos
            val i0 = kotlin.math.floor(p).toInt()
            val frac = p - i0
            val a = buf[i0 and (size - 1)]
            val b = buf[(i0 + 1) and (size - 1)]
            return a + (b - a) * frac
        }

        val phase2 = if (ch.pitchPhase >= window / 2f) {
            ch.pitchPhase - window / 2f
        } else {
            ch.pitchPhase + window / 2f
        }
        val w1 = 1f - (ch.pitchPhase / window)
        val out = read(ch.pitchPhase) * w1 + read(phase2) * (1f - w1)
        ch.pitchWrite = (ch.pitchWrite + 1) and (size - 1)
        return out
    }

    /**
     * Proses buffer di tempat.
     * @param length jumlah byte valid dalam data
     * @param channels 1 (mono) atau 2 (stereo), interleaved
     */
    fun process(data: ByteArray, length: Int, channels: Int) {
        val frameBytes = 2 * channels
        if (frameBytes <= 0) return

        val preset = VoiceFxState.preset
        val inten = VoiceFxState.intensity.coerceIn(0f, 1f)
        val inGain = VoiceFxState.micGain
        val useHp = preset != VoicePreset.NORMAL || gateEnabled || compressorEnabled
        val useGate = gateEnabled || preset == VoicePreset.CLEAN || preset == VoicePreset.BASS
        val useComp = compressorEnabled || preset == VoicePreset.CLEAN || preset == VoicePreset.BASS
        val compMakeup = when (preset) {
            VoicePreset.CLEAN -> makeupLinear * (1f + 0.6f * inten)
            VoicePreset.BASS -> makeupLinear * (1f + 0.4f * inten)
            else -> if (compressorEnabled) makeupLinear else 1f
        }
        val ringFreq = when (preset) {
            VoicePreset.ALIEN -> 28.0
            VoicePreset.ROBOT -> 52.0
            else -> 0.0
        }
        val ringInc = if (ringFreq > 0) 2.0 * PI * ringFreq / sampleRate else 0.0
        val pitchRatio = 1.25f + 0.55f * inten

        val scratch = FloatArray(max(2, channels))
        var i = 0
        while (i + frameBytes <= length) {
            // --- baca + volume mic + high-pass per kanal ---
            var peak = 0f
            for (chIdx in 0 until channels) {
                val idx = i + chIdx * 2
                var y = (((data[idx].toInt() and 0xFF) or (data[idx + 1].toInt() shl 8))
                    .toShort().toInt() / 32768f) * inGain
                if (useHp) {
                    val st = channelsState[minOf(chIdx, 1)]
                    val hp = hpAlpha * (st.hpPrevOut + y - st.hpPrevIn)
                    st.hpPrevIn = y
                    st.hpPrevOut = hp
                    y = hp
                }
                scratch[chIdx] = y
                if (abs(y) > peak) peak = abs(y)
            }

            // --- envelope follower ---
            envelope = if (peak > envelope) {
                envelope + (peak - envelope) * 0.4f
            } else {
                envelope * 0.9995f
            }

            // --- noise gate adaptif ---
            var frameGain = 1f
            if (useGate) {
                if (envelope < 0.02f) {
                    noiseFloor = noiseFloor * 0.998f + envelope * 0.002f
                }
                val threshold = max(0.008f, noiseFloor * 3f)
                val ratioEnv = envelope / threshold
                val target = ((ratioEnv - 0.7f) / 0.6f).coerceIn(0f, 1f)
                gateGain += (target - gateGain) * if (target > gateGain) 0.03f else 0.004f
                frameGain *= gateGain
            }

            // --- kompresor ---
            if (useComp) {
                val thr = 0.25f
                val targetGain = if (envelope > thr) {
                    (thr + (envelope - thr) / 3f) / envelope.coerceAtLeast(1e-6f)
                } else {
                    1f
                }
                compGain += (targetGain - compGain) * if (targetGain < compGain) 0.02f else 0.002f
                frameGain *= compGain * compMakeup
            }

            // --- modulasi cincin (alien/robot), fase dibagi antar kanal ---
            var ringMod = 1f
            if (ringInc > 0.0) {
                ringPhase += ringInc
                if (ringPhase > 2.0 * PI) ringPhase -= 2.0 * PI
                val s = sin(ringPhase).toFloat()
                ringMod = if (preset == VoicePreset.ROBOT) sign(s) * 0.85f else s
            }

            // --- EQ + karakter preset + limiter per kanal ---
            for (chIdx in 0 until channels) {
                val st = channelsState[minOf(chIdx, 1)]
                var y = scratch[chIdx] * frameGain
                when (preset) {
                    VoicePreset.CLEAN -> {
                        y = st.presence.process(y)
                    }
                    VoicePreset.BASS -> {
                        y = st.shelf.process(y)
                        val drive = 1.0 + 2.2 * inten
                        y = (atan(drive * y) / atan(drive * 0.8)).toFloat() * 0.85f
                    }
                    VoicePreset.ECHO -> y = echoProcess(st, y, 0.08f + 0.4f * inten)
                    VoicePreset.REVERB -> y = reverbProcess(st, y, 0.1f + 0.42f * inten)
                    VoicePreset.ALIEN -> {
                        val wet = 0.25f + 0.65f * inten
                        y = y * (1f - wet) + (y * ringMod * 1.5f) * wet
                    }
                    VoicePreset.ROBOT -> {
                        val wet = 0.3f + 0.6f * inten
                        y = y * (1f - wet) + (y * ringMod * 1.3f) * wet
                    }
                    VoicePreset.CHIPMUNK -> {
                        val wet = 0.45f + 0.55f * inten
                        y = y * (1f - wet) + pitchProcess(st, y, pitchRatio) * wet
                    }
                    VoicePreset.NORMAL -> Unit
                }
                // Soft limiter.
                val a = abs(y)
                if (a > 0.89f) {
                    y = sign(y) * (0.89f + (a - 0.89f) * 0.2f).coerceAtMost(0.98f)
                }
                val v = (y * 32767f).toInt().coerceIn(-32768, 32767)
                val idx = i + chIdx * 2
                data[idx] = (v and 0xFF).toByte()
                data[idx + 1] = ((v shr 8) and 0xFF).toByte()
            }
            i += frameBytes
        }
    }
}
