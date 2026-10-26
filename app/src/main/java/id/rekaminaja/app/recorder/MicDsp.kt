package id.rekaminaja.app.recorder

import id.rekaminaja.app.data.Prefs
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

    @Volatile
    var podcastGate: Boolean = false

    @Volatile
    var podcastCompressor: Boolean = false

    @Volatile
    var makeupDb: Int = 3

    /** Naik setiap ada perubahan yang perlu diperhatikan thread audio. */
    @Volatile
    var revision: Long = 0L
        private set

    fun sync(presetId: String, intensityPercent: Int, micVolumePercent: Int) {
        val newPreset = VoicePreset.fromId(presetId)
        val newIntensity = (intensityPercent.coerceIn(0, 100)) / 100f
        val newGain = (micVolumePercent.coerceIn(10, 200)) / 100f
        if (preset != newPreset || intensity != newIntensity || micGain != newGain) {
            preset = newPreset
            intensity = newIntensity
            micGain = newGain
            revision++
        }
    }

    fun syncFromPrefs() {
        sync(Prefs.voicePreset, Prefs.fxIntensity, Prefs.micVolume)
        syncPodcastFromPrefs()
    }

    fun syncPodcastFromPrefs() {
        val gate = Prefs.fxGate
        val comp = Prefs.fxCompressor
        val gain = Prefs.fxGainDb.coerceIn(0, 12)
        if (podcastGate != gate || podcastCompressor != comp || makeupDb != gain) {
            podcastGate = gate
            podcastCompressor = comp
            makeupDb = gain
            revision++
        }
    }

    fun applyPreset(newPreset: VoicePreset) {
        Prefs.voicePreset = newPreset.id
        if (preset != newPreset) {
            preset = newPreset
            revision++
        }
    }

    fun applyPresetId(presetId: String) = applyPreset(VoicePreset.fromId(presetId))

    fun applyIntensityPercent(percent: Int) {
        val safe = percent.coerceIn(0, 100)
        Prefs.fxIntensity = safe
        val value = safe / 100f
        if (intensity != value) {
            intensity = value
            revision++
        }
    }

    fun applyMicVolumePercent(percent: Int) {
        val safe = percent.coerceIn(10, 200)
        Prefs.micVolume = safe
        val value = safe / 100f
        if (micGain != value) {
            micGain = value
            revision++
        }
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
    gateEnabled: Boolean,
    compressorEnabled: Boolean,
    makeupDb: Int
) {
    init {
        // Nilai konstruktor adalah snapshot awal. Setelah itu VoiceFxState
        // menjadi satu-satunya sumber nilai live untuk semua permukaan UI.
        VoiceFxState.podcastGate = gateEnabled
        VoiceFxState.podcastCompressor = compressorEnabled
        VoiceFxState.makeupDb = makeupDb.coerceIn(0, 12)
    }

    private inner class Biquad(private val b0: Float, private val b1: Float, private val b2: Float,
                               private val a1: Float, private val a2: Float) {
        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f
        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
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
    private var postEnvelope = 0f
    private var noiseFloor = 0.004f
    private var gateGain = 1f
    private var gateHoldSamples = 0
    private var compGain = 1f
    private var ringPhase = 0.0

    // Transisi live dibuat halus agar mengganti preset/volume tidak berbunyi
    // klik, melonjak, atau membawa ekor efek lama ke preset berikutnya.
    private var activePreset: VoicePreset = VoiceFxState.preset
    private var smoothedGain: Float = VoiceFxState.micGain
    private var smoothedIntensity: Float = VoiceFxState.intensity
    private var transitionMix = 1f
    private val frameIn = FloatArray(2)
    private val frameOut = FloatArray(2)

    private fun resetChannel(ch: Channel) {
        ch.hpPrevIn = 0f
        ch.hpPrevOut = 0f
        ch.shelf.reset()
        ch.presence.reset()
        ch.echoBuf?.fill(0f)
        ch.echoIdx = 0
        ch.combBufs?.forEach { it.fill(0f) }
        ch.combIdx?.fill(0)
        ch.combDamp?.fill(0f)
        ch.apBufs?.forEach { it.fill(0f) }
        ch.apIdx?.fill(0)
        ch.pitchBuf?.fill(0f)
        ch.pitchWrite = 0
        ch.pitchPhase = 0f
    }

    private fun resetForPresetSwitch() {
        channelsState.forEach(::resetChannel)
        ringPhase = 0.0
        transitionMix = 0f
    }

    private fun dbToLinear(db: Int): Float = 10.0.pow(db.coerceIn(0, 12) / 20.0).toFloat()

    private fun echoProcess(ch: Channel, x: Float, wet: Float): Float {
        val size = (sampleRate * 0.17f).toInt().coerceAtLeast(1)
        var buf = ch.echoBuf
        if (buf == null || buf.size != size) {
            buf = FloatArray(size)
            ch.echoBuf = buf
            ch.echoIdx = 0
        }
        val delayed = buf[ch.echoIdx]
        val feedback = 0.20f + 0.42f * wet.coerceIn(0f, 1f)
        buf[ch.echoIdx] = x + delayed * feedback
        ch.echoIdx = (ch.echoIdx + 1) % size
        return x * (1f - wet * 0.22f) + delayed * wet
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
        return x * (1f - wet * 0.45f) + acc * wet * 1.15f
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
        if (preset != activePreset) {
            activePreset = preset
            resetForPresetSwitch()
        }
        val targetIntensity = VoiceFxState.intensity.coerceIn(0f, 1f)
        val targetGain = VoiceFxState.micGain.coerceIn(0.1f, 2f)
        val podcastGate = VoiceFxState.podcastGate
        val podcastCompressor = VoiceFxState.podcastCompressor

        // Normal + volume 100% + podcast mati adalah bypass byte-per-byte.
        // Ini penting: memilih Normal sekarang benar-benar berarti mentah,
        // bukan diam-diam melewati EQ/gate/kompresor sisa preset lain.
        if (preset == VoicePreset.NORMAL && !podcastGate && !podcastCompressor &&
            targetGain == 1f && smoothedGain == 1f && transitionMix >= 1f
        ) {
            return
        }

        val useHp = preset != VoicePreset.NORMAL || podcastGate || podcastCompressor
        val useGate = podcastGate || preset == VoicePreset.CLEAN || preset == VoicePreset.BASS
        val useComp = podcastCompressor || preset == VoicePreset.CLEAN || preset == VoicePreset.BASS
        val makeupLinear = dbToLinear(VoiceFxState.makeupDb)
        val compMakeup = when {
            podcastCompressor -> makeupLinear
            preset == VoicePreset.CLEAN -> 1.12f
            preset == VoicePreset.BASS -> 1.06f
            else -> 1f
        }
        val presetTrim = when (preset) {
            VoicePreset.CLEAN -> 0.96f
            VoicePreset.BASS -> 0.92f
            VoicePreset.ECHO -> 0.94f
            VoicePreset.REVERB -> 0.90f
            VoicePreset.ALIEN -> 1.08f
            VoicePreset.ROBOT -> 1.05f
            VoicePreset.CHIPMUNK -> 0.98f
            VoicePreset.NORMAL -> 1f
        }
        val ringFreq = when (preset) {
            VoicePreset.ALIEN -> 28.0
            VoicePreset.ROBOT -> 46.0
            else -> 0.0
        }
        val ringInc = if (ringFreq > 0) 2.0 * PI * ringFreq / sampleRate else 0.0
        val transitionStep = 1f / (sampleRate * 0.035f).coerceAtLeast(1f)

        var i = 0
        while (i + frameBytes <= length) {
            // Ramping per sampel: perubahan UI tidak menimbulkan klik dan
            // intensitas 0 sekarang benar-benar kering untuk efek karakter.
            smoothedGain += (targetGain - smoothedGain) * 0.0018f
            if (abs(targetGain - smoothedGain) < 0.0005f) smoothedGain = targetGain
            smoothedIntensity += (targetIntensity - smoothedIntensity) * 0.0025f
            if (abs(targetIntensity - smoothedIntensity) < 0.0005f) {
                smoothedIntensity = targetIntensity
            }
            if (transitionMix < 1f) {
                transitionMix = (transitionMix + transitionStep).coerceAtMost(1f)
            }
            val inten = (smoothedIntensity * transitionMix).coerceIn(0f, 1f)
            val pitchRatio = 1.10f + 0.65f * inten

            // --- baca + volume mic + high-pass per kanal ---
            var peak = 0f
            for (chIdx in 0 until channels) {
                val idx = i + chIdx * 2
                var y = (((data[idx].toInt() and 0xFF) or (data[idx + 1].toInt() shl 8))
                    .toShort().toInt() / 32768f) * smoothedGain
                if (useHp) {
                    val st = channelsState[minOf(chIdx, 1)]
                    val hp = hpAlpha * (st.hpPrevOut + y - st.hpPrevIn)
                    st.hpPrevIn = y
                    st.hpPrevOut = hp
                    y = hp
                }
                frameIn[minOf(chIdx, 1)] = y
                if (abs(y) > peak) peak = abs(y)
            }

            // --- envelope follower ---
            envelope = if (peak > envelope) {
                envelope + (peak - envelope) * 0.4f
            } else {
                envelope * 0.9995f
            }

            // --- noise gate adaptif + hold agar ujung kata tidak terpotong ---
            var frameGain = 1f
            if (useGate) {
                if (envelope < 0.015f) {
                    noiseFloor = noiseFloor * 0.9985f + envelope * 0.0015f
                }
                val threshold = max(0.0045f, noiseFloor * 2.8f)
                if (envelope > threshold) {
                    gateHoldSamples = (sampleRate * 0.14f).toInt()
                } else if (gateHoldSamples > 0) {
                    gateHoldSamples--
                }
                val target = if (gateHoldSamples > 0) {
                    1f
                } else {
                    ((envelope / threshold - 0.55f) / 0.5f).coerceIn(0f, 1f)
                }
                gateGain += (target - gateGain) * if (target > gateGain) 0.025f else 0.00045f
                frameGain *= gateGain
            } else {
                gateGain = 1f
                gateHoldSamples = 0
            }

            // --- modulasi cincin (alien/robot), fase dibagi antar kanal ---
            var ringMod = 1f
            if (ringInc > 0.0) {
                ringPhase += ringInc
                if (ringPhase > 2.0 * PI) ringPhase -= 2.0 * PI
                ringMod = sin(ringPhase).toFloat()
            }

            // --- EQ + karakter preset per kanal ---
            var postPeak = 0f
            for (chIdx in 0 until channels) {
                val stateIdx = minOf(chIdx, 1)
                val st = channelsState[stateIdx]
                var y = frameIn[stateIdx] * frameGain
                when (preset) {
                    VoicePreset.CLEAN -> {
                        val wetY = st.presence.process(y)
                        y = y * (1f - inten) + wetY * inten
                    }
                    VoicePreset.BASS -> {
                        val dry = y
                        var wetY = st.shelf.process(y)
                        val drive = 1.0 + 1.5 * inten
                        wetY = (atan(drive * wetY) / atan(drive * 0.75)).toFloat() * 0.82f
                        y = dry * (1f - inten) + wetY * inten
                    }
                    VoicePreset.ECHO -> y = echoProcess(st, y, 0.48f * inten)
                    VoicePreset.REVERB -> y = reverbProcess(st, y, 0.52f * inten)
                    VoicePreset.ALIEN -> {
                        val wet = 0.88f * inten
                        y = y * (1f - wet) + (y * ringMod * 1.45f) * wet
                    }
                    VoicePreset.ROBOT -> {
                        val wet = 0.88f * inten
                        y = y * (1f - wet) + (y * ringMod * 1.25f) * wet
                    }
                    VoicePreset.CHIPMUNK -> {
                        // Untuk pitch, intensitas mengatur tinggi nada. Dry
                        // tidak dicampur agar tidak terdengar chorus/phasing.
                        if (inten >= 0.04f) y = pitchProcess(st, y, pitchRatio)
                    }
                    VoicePreset.NORMAL -> Unit
                }
                y *= presetTrim
                frameOut[stateIdx] = y
                if (abs(y) > postPeak) postPeak = abs(y)
            }

            // --- kompresor setelah karakter: level antar-preset lebih rata ---
            postEnvelope = if (postPeak > postEnvelope) {
                postEnvelope + (postPeak - postEnvelope) * 0.35f
            } else {
                postEnvelope * 0.9997f
            }
            var outputGain = 1f
            if (useComp) {
                val thr = 0.25f
                val targetCompGain = if (postEnvelope > thr) {
                    (thr + (postEnvelope - thr) / 3f) / postEnvelope.coerceAtLeast(1e-6f)
                } else {
                    1f
                }
                compGain += (targetCompGain - compGain) *
                    if (targetCompGain < compGain) 0.003f else 0.0003f
                outputGain = compGain * compMakeup
            } else {
                compGain = 1f
            }

            // --- limiter + tulis PCM ---
            for (chIdx in 0 until channels) {
                var y = frameOut[minOf(chIdx, 1)] * outputGain
                val a = abs(y)
                if (a > 0.89f) {
                    y = sign(y) * (0.89f + (a - 0.89f) * 0.18f).coerceAtMost(0.98f)
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
