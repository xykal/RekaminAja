package id.rekaminaja.app.recorder

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign

/**
 * DSP mikrofon gaya podcast, ditulis sendiri untuk PCM 16-bit little-endian.
 *
 * Rantai proses per frame:
 * 1) High-pass 80 Hz — membuang dengung ruangan & getaran meja (rumble).
 * 2) Noise gate adaptif — memperkirakan lantai noise lalu menutup gain saat
 *    tidak ada suara, sehingga jeda bicara benar-benar hening.
 * 3) Kompresor lunak — meratakan volume bicara (yang pelan naik, yang keras ditahan).
 * 4) Makeup gain (dB dari pengaturan) + soft limiter mencegah clipping kasar.
 *
 * Dipakai hanya saat user mengaktifkan efek di Pengaturan (jalur CodecRecorderEngine).
 * Thread audio saja yang memanggil process(), jadi tidak perlu sinkronisasi berat.
 */
class MicDsp(
    sampleRate: Int,
    private val gateEnabled: Boolean,
    private val compressorEnabled: Boolean,
    makeupDb: Int
) {
    private val hpAlpha: Double = run {
        val rc = 1.0 / (2.0 * Math.PI * 80.0)
        val dt = 1.0 / sampleRate
        rc / (rc + dt)
    }
    private val makeupLinear: Double = 10.0.pow(makeupDb / 20.0)

    private val hpPrevIn = DoubleArray(2)
    private val hpPrevOut = DoubleArray(2)

    private val sampleScratch = DoubleArray(2)

    private var envelope = 0.0
    private var noiseFloor = 0.004
    private var gateGain = 1.0
    private var compGain = 1.0

    /**
     * Proses buffer di tempat.
     * @param length jumlah byte valid dalam data
     * @param channels 1 (mono) atau 2 (stereo), interleaved
     */
    fun process(data: ByteArray, length: Int, channels: Int) {
        val frameBytes = 2 * channels
        if (frameBytes <= 0) return
        var i = 0
        while (i + frameBytes <= length) {
            // --- baca + high-pass per kanal, catat puncak frame ---
            val samples = if (channels <= sampleScratch.size) sampleScratch else DoubleArray(channels)
            var peak = 0.0
            for (ch in 0 until channels) {
                val idx = i + ch * 2
                val raw = ((data[idx].toInt() and 0xFF) or (data[idx + 1].toInt() shl 8))
                    .toShort().toInt() / 32768.0
                val chIdx = if (ch < 2) ch else 0
                val y = hpAlpha * (hpPrevOut[chIdx] + raw - hpPrevIn[chIdx])
                hpPrevIn[chIdx] = raw
                hpPrevOut[chIdx] = y
                samples[ch] = y
                peak = max(peak, abs(y))
            }

            // --- envelope follower (serang cepat, lepas lambat) ---
            envelope = if (peak > envelope) {
                envelope + (peak - envelope) * 0.4
            } else {
                envelope * 0.9995
            }

            // --- noise gate adaptif ---
            var frameGain = 1.0
            if (gateEnabled) {
                if (envelope < 0.02) {
                    noiseFloor = noiseFloor * 0.998 + envelope * 0.002
                }
                val threshold = max(0.008, noiseFloor * 3.0)
                val ratio = if (threshold > 0.0) envelope / threshold else 1.0
                val target = ((ratio - 0.7) / 0.6).coerceIn(0.0, 1.0)
                gateGain += (target - gateGain) * if (target > gateGain) 0.03 else 0.004
                frameGain *= gateGain
            }

            // --- kompresor (gain dihaluskan dari envelope) ---
            if (compressorEnabled) {
                val thr = 0.25 // sekitar -12 dBFS
                val targetGain = if (envelope > thr) {
                    (thr + (envelope - thr) / 3.0) / envelope.coerceAtLeast(1e-6)
                } else {
                    1.0
                }
                compGain += (targetGain - compGain) * if (targetGain < compGain) 0.02 else 0.002
                frameGain *= compGain * makeupLinear
            }

            // --- tulis kembali + soft limiter ---
            for (ch in 0 until channels) {
                var y = samples[ch] * frameGain
                val a = abs(y)
                if (a > 0.89) {
                    y = sign(y) * (0.89 + (a - 0.89) * 0.2).coerceAtMost(0.98)
                }
                val v = (y * 32767.0).toInt().coerceIn(-32768, 32767)
                val idx = i + ch * 2
                data[idx] = (v and 0xFF).toByte()
                data[idx + 1] = ((v shr 8) and 0xFF).toByte()
            }
            i += frameBytes
        }
    }
}
