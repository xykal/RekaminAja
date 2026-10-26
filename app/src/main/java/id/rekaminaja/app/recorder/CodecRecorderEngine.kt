package id.rekaminaja.app.recorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Engine codec RekaminAja: Video H.264 via MediaCodec (surface input), audio AAC
 * via MediaCodec + MediaMuxer. Dipakai untuk:
 * - Audio internal (Android 10+, AudioPlaybackCapture)
 * - Internal + mic (mix PCM)
 * - Mic saja saat efek podcast aktif (efek sistem NS/AEC/AGC + DSP [MicDsp])
 *
 * Catatan: aplikasi yang melarang perekaman audionya (kebijakan capture Android)
 * akan menghasilkan audio internal kosong. Itu aturan platform.
 */
class CodecRecorderEngine(
    private val projection: MediaProjection?,
    override val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val includeInternal: Boolean,
    private val includeMic: Boolean,
    private val fxNoiseSystem: Boolean = false,
    private val dsp: MicDsp? = null,
    private val videoMime: String = MediaFormat.MIMETYPE_VIDEO_AVC
) : RecorderEngine {

    // Jeda diimplementasikan best-effort (frame/sampel saat jeda dibuang + timestamp digeser).
    override val supportsPause: Boolean = true

    private val sampleRate = 44_100
    private val channelCount = 2

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private val micEffects = mutableListOf<AudioEffect>()

    @Volatile private var feeding = false
    @Volatile private var muxing = false
    @Volatile private var paused = false
    @Volatile private var pausedTotalUs = 0L
    @Volatile private var videoEos = false
    @Volatile private var audioEos = false
    private var pauseStartNs = 0L

    private var muxerThread: Thread? = null
    private var audioThread: Thread? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    private var firstVideoPts = -1L
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L

    private data class Pending(val isVideo: Boolean, val data: ByteArray, val ptsUs: Long, val flags: Int)
    private val pending = mutableListOf<Pending>()

    @SuppressLint("MissingPermission")
    override fun start(): Surface {
        // --- Video encoder (surface input) ---
        val videoFormat = CaptureCapabilities.encoderFormat(
            videoMime, width, height, fps, bitrate
        )
        val encoderName = CaptureCapabilities.encoderNameFor(
            videoMime, width, height, fps, bitrate
        )
        videoCodec = (if (encoderName != null) {
            MediaCodec.createByCodecName(encoderName)
        } else {
            MediaCodec.createEncoderByType(videoMime)
        }).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = videoCodec!!.createInputSurface()
        videoCodec!!.start()

        // --- Audio encoder (AAC) ---
        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // --- Sumber audio ---
        if (includeInternal) {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Audio internal perlu Android 10+" }
            internalRecord = createInternalRecord()
        }
        if (includeMic) micRecord = createMicRecord()
        check(internalRecord != null || micRecord != null) { "Tidak ada sumber audio" }

        feeding = true
        muxing = true
        audioThread = Thread({ audioFeedLoop() }, "rekamin-audio").apply { start() }
        muxerThread = Thread({ muxLoop() }, "rekamin-mux").apply { start() }
        return inputSurface!!
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun createInternalRecord(): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(
            requireNotNull(projection) { "MediaProjection wajib untuk audio internal" }
        )
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = pcmFormat()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize())
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord internal gagal dibuat" }
        return record
    }

    @SuppressLint("MissingPermission")
    private fun createMicRecord(): AudioRecord {
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(pcmFormat())
            .setBufferSizeInBytes(bufferSize())
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord mic gagal dibuat" }
        if (fxNoiseSystem) attachSystemEffects(record.audioSessionId)
        return record
    }

    /**
     * Efek bawaan platform untuk suara gaya podcast. Semua best-effort:
     * perangkat tanpa dukungan efek akan tetap merekam tanpa efek.
     */
    private fun attachSystemEffects(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let {
                    it.enabled = true
                    micEffects.add(it)
                }
            }
        } catch (e: Exception) {
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let {
                    it.enabled = true
                    micEffects.add(it)
                }
            }
        } catch (e: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let {
                    it.enabled = true
                    micEffects.add(it)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun pcmFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    private fun bufferSize(): Int {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return max(if (minBuf > 0) minBuf * 2 else 0, 16_384)
    }

    override fun pause() {
        if (paused) return
        paused = true
        pauseStartNs = System.nanoTime()
    }

    override fun resume() {
        if (!paused) return
        pausedTotalUs += (System.nanoTime() - pauseStartNs) / 1_000L
        paused = false
    }

    override fun stop(): Boolean {
        // 1) hentikan umpan audio (thread akan mengantrekan EOS)
        feeding = false
        try {
            audioThread?.join(2_000)
        } catch (e: InterruptedException) {
        }
        // 2) EOS video
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
        }
        // 3) tunggu muxer menguras sisa buffer
        muxing = false
        try {
            muxerThread?.join(4_000)
        } catch (e: InterruptedException) {
        }
        val started = muxerStarted
        releaseAll()
        if (!started || !outputFile.exists() || outputFile.length() == 0L) {
            outputFile.delete()
            return false
        }
        return true
    }

    private fun releaseAll() {
        for (fx in micEffects) {
            try {
                fx.release()
            } catch (e: Exception) {
            }
        }
        micEffects.clear()
        try {
            internalRecord?.stop()
        } catch (e: Exception) {
        }
        try {
            micRecord?.stop()
        } catch (e: Exception) {
        }
        internalRecord?.release(); internalRecord = null
        micRecord?.release(); micRecord = null
        try {
            videoCodec?.stop()
        } catch (e: Exception) {
        }
        videoCodec?.release(); videoCodec = null
        try {
            audioCodec?.stop()
        } catch (e: Exception) {
        }
        audioCodec?.release(); audioCodec = null
        inputSurface?.release(); inputSurface = null
        try {
            if (muxerStarted) muxer?.stop()
        } catch (e: Exception) {
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
        }
        muxer = null
    }

    // ---------------------------------------------------------------- audio feed

    private fun audioFeedLoop() {
        val internal = internalRecord
        val mic = micRecord
        if (internal == null && mic == null) {
            audioEos = true
            return
        }
        try {
            internal?.startRecording()
            mic?.startRecording()
        } catch (e: Exception) {
            audioEos = true
            return
        }
        val chunkBytes = 8_192 // 2048 frame stereo 16-bit
        val bufInternal = ByteArray(chunkBytes)
        val bufMic = ByteArray(chunkBytes)
        var totalFrames = 0L
        var lastFedPts = -1L
        val codec = audioCodec ?: return
        while (feeding) {
            var out: ByteArray
            var outLen: Int
            if (internal != null) {
                val n = try {
                    internal.read(bufInternal, 0, bufInternal.size, AudioRecord.READ_BLOCKING)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) continue
                out = bufInternal
                outLen = n
                if (mic != null) {
                    val nMic = try {
                        mic.read(bufMic, 0, n, AudioRecord.READ_BLOCKING)
                    } catch (e: Exception) {
                        0
                    }
                    if (nMic > 0) {
                        // DSP podcast hanya menyentuh jalur mic, bukan audio internal.
                        dsp?.process(bufMic, nMic, channelCount)
                        outLen = min(n, nMic)
                        out = mixPcm(bufInternal, bufMic, outLen)
                    }
                }
            } else {
                val n = try {
                    mic!!.read(bufMic, 0, bufMic.size, AudioRecord.READ_BLOCKING)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) continue
                dsp?.process(bufMic, n, channelCount)
                out = bufMic
                outLen = n
            }
            val frameBase = totalFrames
            totalFrames += (outLen / 4).toLong()
            if (paused) continue // sampel saat jeda dibuang, timeline tetap dihitung

            var offset = 0
            while (offset < outLen && feeding) {
                val idx = try {
                    codec.dequeueInputBuffer(10_000)
                } catch (e: Exception) {
                    -1
                }
                if (idx < 0) break
                val input = codec.getInputBuffer(idx) ?: break
                val len = min(input.capacity(), outLen - offset)
                input.clear()
                input.put(out, offset, len)
                val segFrames = frameBase + (offset / 4)
                var pts = segFrames * 1_000_000L / sampleRate - pausedTotalUs
                if (pts < 0) pts = 0
                if (pts <= lastFedPts) pts = lastFedPts + 64
                lastFedPts = pts
                codec.queueInputBuffer(idx, 0, len, pts, 0)
                offset += len
            }
        }
        // Antrikan EOS audio
        try {
            val idx = codec.dequeueInputBuffer(500_000)
            if (idx >= 0) {
                codec.queueInputBuffer(
                    idx, 0, 0,
                    (totalFrames * 1_000_000L / sampleRate - pausedTotalUs).coerceAtLeast(0),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
        } catch (e: Exception) {
            audioEos = true
        }
    }

    /** Campur dua buffer PCM 16-bit little-endian dengan clipping. */
    private fun mixPcm(a: ByteArray, b: ByteArray, length: Int): ByteArray {
        val out = a.copyOf(length)
        var i = 0
        while (i + 1 < length) {
            val sa = (a[i].toInt() and 0xFF) or (a[i + 1].toInt() shl 8)
            val sb = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
            val s = (sa.toShort().toInt() + sb.toShort().toInt()).coerceIn(-32_768, 32_767)
            out[i] = (s and 0xFF).toByte()
            out[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    // ---------------------------------------------------------------- muxing

    private fun muxLoop() {
        val video = videoCodec ?: return
        val audio = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            var progress = false
            progress = drain(video, true, info) || progress
            progress = drain(audio, false, info) || progress
            if (videoEos && audioEos) break
            if (!muxing && !feeding && videoEos) break
            if (!progress) {
                try {
                    Thread.sleep(2)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean, info: MediaCodec.BufferInfo): Boolean {
        var progress = false
        while (true) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 1_000)
            } catch (e: Exception) {
                if (isVideo) videoEos = true else audioEos = true
                return progress
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return progress
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormatChanged(codec, isVideo)
                    progress = true
                }
                idx >= 0 -> {
                    val buffer = codec.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        val drop = isVideo && paused
                        if (!drop) writeSample(isVideo, buffer, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    progress = true
                    if (isEos) {
                        if (isVideo) videoEos = true else audioEos = true
                        return progress
                    }
                }
                else -> return progress
            }
        }
    }

    private fun onFormatChanged(codec: MediaCodec, isVideo: Boolean) {
        val m = muxer ?: return
        if (isVideo && videoTrack < 0) videoTrack = m.addTrack(codec.outputFormat)
        if (!isVideo && audioTrack < 0) audioTrack = m.addTrack(codec.outputFormat)
        if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
            m.start()
            muxerStarted = true
            // Flush sampel yang datang sebelum muxer siap (urut waktu).
            val copy = pending.sortedBy { it.ptsUs }
            pending.clear()
            for (p in copy) writeNow(p)
        }
    }

    private fun writeSample(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val data = ByteArray(info.size)
        buffer.get(data)
        var pts: Long = if (isVideo) {
            if (firstVideoPts < 0) firstVideoPts = info.presentationTimeUs
            info.presentationTimeUs - firstVideoPts - pausedTotalUs
        } else {
            info.presentationTimeUs
        }
        if (pts < 0) pts = 0
        if (isVideo) {
            if (lastVideoPts >= 0 && pts <= lastVideoPts) pts = lastVideoPts + 100
            lastVideoPts = pts
        } else {
            if (lastAudioPts >= 0 && pts <= lastAudioPts) pts = lastAudioPts + 100
            lastAudioPts = pts
        }
        val sample = Pending(isVideo, data, pts, info.flags)
        if (muxerStarted) writeNow(sample) else pending.add(sample)
    }

    private fun writeNow(p: Pending) {
        val m = muxer ?: return
        val track = if (p.isVideo) videoTrack else audioTrack
        if (track < 0) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, p.data.size, p.ptsUs, p.flags)
        }
        try {
            m.writeSampleData(track, ByteBuffer.wrap(p.data), info)
        } catch (e: Exception) {
            // Sample rusak/terlambat dibuang demi menjaga file tetap valid.
        }
    }
}
