package id.rekaminaja.app.subtitle

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import id.rekaminaja.app.R
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mengubah trek AAC di MP4 menjadi potongan WAV PCM 16-bit mono 16 kHz.
 *
 * Potongan dibatasi sekitar 90 detik dan diusahakan dipotong saat hening.
 * Ini mencegah library Whisper memuat seluruh audio video panjang ke RAM
 * sekaligus, sekaligus memberi titik progres dan pembatalan yang masuk akal.
 */
class SubtitleAudioExtractor(context: Context) {

    private val appContext = context.applicationContext

    data class Chunk(
        val file: File,
        val startMs: Long,
        val durationMs: Long,
        val voicedRatio: Float,
        val peak: Float
    )

    data class Result(
        val chunks: List<Chunk>,
        val durationMs: Long,
        val voicedRatio: Float,
        val peak: Float
    )

    fun extract(
        uri: Uri,
        workDir: File,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Result {
        workDir.mkdirs()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val chunks = mutableListOf<Chunk>()

        var builder = ChunkBuilder(workDir, 0, 0L)
        var chunkIndex = 0
        var emittedSamples = 0L

        fun emit(sample: Short) {
            builder.accept(sample)
            emittedSamples++
            if (builder.shouldCut()) {
                builder.finish()?.let { chunks.add(it) }
                chunkIndex++
                builder = ChunkBuilder(workDir, chunkIndex, emittedSamples)
            }
        }

        var sampleRate = 44_100
        var channels = 2
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler = Resampler(sampleRate) { emit(it) }

        fun updateFormat(format: MediaFormat) {
            val newRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                sampleRate
            }
            val newChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                channels
            }
            val newEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                try {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } catch (e: Exception) {
                    encoding
                }
            } else {
                encoding
            }
            if (newRate != sampleRate) {
                sampleRate = newRate.coerceAtLeast(1)
                resampler = Resampler(sampleRate) { emit(it) }
            }
            channels = newChannels
            encoding = newEncoding
        }

        fun processPcm(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            val data = buffer.duplicate().apply {
                order(ByteOrder.LITTLE_ENDIAN)
                position(info.offset)
                limit(info.offset + info.size)
            }
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_8BIT -> 1
                else -> 2
            }
            val frameBytes = bytesPerSample * channels.coerceAtLeast(1)
            while (data.remaining() >= frameBytes) {
                if (isCancelled()) throw SubtitleCancelledException()
                var sum = 0f
                repeat(channels.coerceAtLeast(1)) {
                    sum += when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> data.float
                        AudioFormat.ENCODING_PCM_8BIT ->
                            ((data.get().toInt() and 0xFF) - 128) / 128f
                        else -> data.short / 32768f
                    }
                }
                resampler.accept(sum / channels.coerceAtLeast(1))
            }
        }

        try {
            if (uri.scheme == "file") {
                extractor.setDataSource(uri.path ?: throw SubtitleException(R.string.subtitle_no_audio))
            } else {
                extractor.setDataSource(appContext, uri, null)
            }

            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    audioFormat = format
                    break
                }
            }
            val format = audioFormat ?: throw SubtitleException(R.string.subtitle_no_audio)
            if (audioTrack < 0) throw SubtitleException(R.string.subtitle_no_audio)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw SubtitleException(R.string.subtitle_no_audio)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                try {
                    format.getLong(MediaFormat.KEY_DURATION)
                } catch (e: Exception) {
                    0L
                }
            } else {
                0L
            }

            updateFormat(format)
            extractor.selectTrack(audioTrack)
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            while (!outputEos) {
                if (isCancelled()) throw SubtitleCancelledException()

                if (!inputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                            ?: throw SubtitleException(R.string.subtitle_failed)
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(
                                inputIndex, 0, size, pts, extractor.sampleFlags
                            )
                            if (durationUs > 0) {
                                onProgress(((pts * 100L) / durationUs).toInt().coerceIn(0, 100))
                            }
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateFormat(decoder.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Decoder belum memberi output; lanjutkan memberi input.
                    }
                    else -> {
                        if (outputIndex >= 0) {
                            val output = decoder.getOutputBuffer(outputIndex)
                            if (output != null && info.size > 0) {
                                processPcm(output, info)
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                        }
                    }
                }
            }
        } catch (e: SubtitleException) {
            throw e
        } catch (e: SubtitleCancelledException) {
            throw e
        } catch (e: Exception) {
            if (isCancelled()) throw SubtitleCancelledException()
            throw SubtitleException(R.string.subtitle_failed, e)
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {
            }
            decoder?.release()
            extractor.release()
        }

        builder.finish()?.let { chunks.add(it) }
        if (chunks.isEmpty() || emittedSamples == 0L) {
            throw SubtitleException(R.string.subtitle_no_audio)
        }
        val durationMs = emittedSamples * 1000L / TARGET_SAMPLE_RATE
        val weightedVoiced = if (durationMs > 0) {
            chunks.sumOf { (it.voicedRatio * it.durationMs).toDouble() }.toFloat() / durationMs
        } else {
            0f
        }
        return Result(
            chunks = chunks,
            durationMs = durationMs,
            voicedRatio = weightedVoiced.coerceIn(0f, 1f),
            peak = chunks.maxOfOrNull { it.peak } ?: 0f
        )
    }

    /** Resampler linear streaming dari sample rate sumber ke 16 kHz. */
    private class Resampler(
        sourceRate: Int,
        private val emit: (Short) -> Unit
    ) {
        private val step = sourceRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        private var inputIndex = 0L
        private var previous = 0f
        private var havePrevious = false
        private var nextOutputAt = 0.0

        fun accept(value: Float) {
            val current = value.coerceIn(-1f, 1f)
            if (!havePrevious) {
                emit(toShort(current))
                nextOutputAt += step
                previous = current
                havePrevious = true
                inputIndex++
                return
            }
            while (nextOutputAt <= inputIndex.toDouble()) {
                val fraction = (nextOutputAt - (inputIndex - 1).toDouble()).toFloat()
                    .coerceIn(0f, 1f)
                emit(toShort(previous + (current - previous) * fraction))
                nextOutputAt += step
            }
            previous = current
            inputIndex++
        }

        private fun toShort(value: Float): Short =
            (value.coerceIn(-1f, 1f) * 32767f).toInt()
                .coerceIn(-32768, 32767).toShort()
    }

    private inner class ChunkBuilder(
        private val workDir: File,
        private val index: Int,
        private val startSample: Long
    ) {
        private val pcm = ByteArrayOutputStream()
        private var sampleCount = 0L
        private var sumSquares = 0.0
        private var peak = 0f

        private var frameSumSquares = 0.0
        private var frameSamples = 0
        private var totalFrames = 0
        private var voicedFrames = 0
        private var silenceRunFrames = 0
        private var hasSpeech = false

        fun accept(sample: Short) {
            pcm.write(sample.toInt() and 0xFF)
            pcm.write((sample.toInt() shr 8) and 0xFF)
            sampleCount++
            val v = sample / 32768f
            peak = maxOf(peak, abs(v))
            sumSquares += v * v
            frameSumSquares += v * v
            frameSamples++
            if (frameSamples >= FRAME_SAMPLES) {
                val rms = sqrt(frameSumSquares / frameSamples).toFloat()
                totalFrames++
                if (rms >= VOICE_RMS_THRESHOLD) {
                    voicedFrames++
                    hasSpeech = true
                    silenceRunFrames = 0
                } else {
                    silenceRunFrames++
                }
                frameSumSquares = 0.0
                frameSamples = 0
            }
        }

        fun shouldCut(): Boolean {
            if (sampleCount >= MAX_CHUNK_SAMPLES) return true
            return sampleCount >= MIN_CHUNK_SAMPLES &&
                hasSpeech &&
                silenceRunFrames >= SILENCE_CUT_FRAMES
        }

        fun finish(): Chunk? {
            if (sampleCount == 0L) return null
            val bytes = pcm.toByteArray()
            val count = min(sampleCount.toInt(), bytes.size / 2)
            val samples = ShortArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().get(samples)

            // Normalisasi lembut membantu mic yang pelan tanpa mengubah rekaman asli.
            if (count > 0 && peak > 0.001f) {
                val rms = sqrt(sumSquares / count).toFloat()
                var gain = 1f
                if (rms > 0.0005f) {
                    gain = (0.13f / rms).coerceIn(1f, 4f)
                }
                gain = min(gain, 0.92f / peak)
                if (gain > 1.03f || gain < 0.99f) {
                    for (i in samples.indices) {
                        samples[i] = (samples[i] * gain).toInt()
                            .coerceIn(-32768, 32767).toShort()
                    }
                }
            }

            val file = File(workDir, "subtitle_audio_$index.wav")
            writeWav(file, samples)
            return Chunk(
                file = file,
                startMs = startSample * 1000L / TARGET_SAMPLE_RATE,
                durationMs = count * 1000L / TARGET_SAMPLE_RATE,
                voicedRatio = if (totalFrames > 0) {
                    voicedFrames.toFloat() / totalFrames
                } else {
                    0f
                },
                peak = peak
            )
        }

        private fun writeWav(file: File, samples: ShortArray) {
            val dataBytes = samples.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataBytes)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(1) // mono
                putInt(TARGET_SAMPLE_RATE)
                putInt(TARGET_SAMPLE_RATE * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes)
            }.array()
            BufferedOutputStream(FileOutputStream(file)).use { output ->
                output.write(header)
                val buffer = ByteArray(8192)
                var offset = 0
                while (offset < samples.size) {
                    var p = 0
                    while (p + 1 < buffer.size && offset < samples.size) {
                        val s = samples[offset++].toInt()
                        buffer[p++] = (s and 0xFF).toByte()
                        buffer[p++] = ((s shr 8) and 0xFF).toByte()
                    }
                    output.write(buffer, 0, p)
                }
            }
        }
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val FRAME_SAMPLES = 320 // 20 ms pada 16 kHz
        private const val VOICE_RMS_THRESHOLD = 0.007f
        private const val SILENCE_CUT_FRAMES = 30 // sekitar 600 ms
        // v1.2: potongan maks 30 d (jendela native Whisper). Versi lama 90 d
        // membuat progres seolah stuck ber menit-menit di HP lama.
        private const val MIN_CHUNK_SAMPLES = TARGET_SAMPLE_RATE * 12L
        private const val MAX_CHUNK_SAMPLES = TARGET_SAMPLE_RATE * 30L
    }
}
