package id.rekaminaja.app.subtitle

import android.content.Context
import android.os.Build
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import id.rekaminaja.app.R
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * Whisper offline untuk subtitle bahasa Indonesia.
 *
 * Model base-q5_1 dibundel sebagai asset saat build, disalin sekali ke
 * penyimpanan internal, lalu dipakai berulang. Proses berjalan per potongan
 * WAV agar video panjang tidak membebani RAM dan UI dapat memberi progres.
 */
object WhisperSubtitleEngine {

    private const val MODEL_FILE_NAME = "ggml-base-q5_1.bin"
    private const val MODEL_ASSET_PATH = "models/$MODEL_FILE_NAME"
    private const val MODEL_EXPECTED_SIZE = 59_707_625L
    private const val MODEL_SHA256 =
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"

    fun isSupportedDevice(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    fun ensureModel(
        context: Context,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): File {
        val dir = File(context.filesDir, "whisper").apply { mkdirs() }
        val target = File(dir, MODEL_FILE_NAME)
        val marker = File(dir, "$MODEL_FILE_NAME.ok")
        val markerOk = try {
            marker.isFile && marker.readText(Charsets.UTF_8).trim() == MODEL_SHA256
        } catch (e: Exception) {
            false
        }
        if (target.isFile && target.length() == MODEL_EXPECTED_SIZE && markerOk) {
            onProgress(100)
            return target
        }

        val temp = File(dir, "$MODEL_FILE_NAME.installing")
        temp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        if (isCancelled()) throw SubtitleCancelledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        onProgress(
                            ((copied * 100L) / MODEL_EXPECTED_SIZE)
                                .toInt().coerceIn(0, 100)
                        )
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            temp.delete()
            throw SubtitleException(R.string.subtitle_model_missing, e)
        } catch (e: SubtitleCancelledException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw SubtitleException(R.string.subtitle_failed, e)
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (temp.length() != MODEL_EXPECTED_SIZE || actual != MODEL_SHA256) {
            temp.delete()
            throw SubtitleException(R.string.subtitle_failed)
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        marker.writeText(actual, Charsets.UTF_8)
        onProgress(100)
        return target
    }

    fun transcribe(
        context: Context,
        modelFile: File,
        chunks: List<SubtitleAudioExtractor.Chunk>,
        isCancelled: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<SubtitleCue> {
        if (!isSupportedDevice()) {
            throw SubtitleException(R.string.subtitle_unsupported_device)
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val rawCues = mutableListOf<SubtitleCue>()
        try {
            runBlocking {
                val model = Whisper.loadModel(context.applicationContext, modelFile.absolutePath)
                try {
                    chunks.forEachIndexed { index, chunk ->
                        if (isCancelled()) throw SubtitleCancelledException()
                        // Potongan yang praktis hening dilewati agar Whisper tidak
                        // mengarang teks dari musik/noise yang sangat lemah.
                        if (chunk.voicedRatio < 0.004f && chunk.peak < 0.02f) {
                            onProgress(index + 1, chunks.size)
                            return@forEachIndexed
                        }
                        val result = Whisper.transcribe(
                            model,
                            chunk.file.absolutePath,
                            WhisperConfig(language = "id", translate = false, threads = threads)
                        )
                        if (isCancelled()) throw SubtitleCancelledException()

                        if (result.segments.isNotEmpty()) {
                            for (segment in result.segments) {
                                val text = cleanText(segment.text)
                                if (text.isBlank() || isSoundAnnotation(text)) continue
                                rawCues += splitSegment(
                                    startMs = chunk.startMs + segment.startMs.coerceAtLeast(0L),
                                    endMs = chunk.startMs + segment.endMs.coerceAtLeast(0L),
                                    text = text
                                )
                            }
                        } else {
                            val text = cleanText(result.text)
                            if (text.isNotBlank() && !isSoundAnnotation(text)) {
                                rawCues += splitSegment(
                                    startMs = chunk.startMs,
                                    endMs = chunk.startMs + chunk.durationMs,
                                    text = text
                                )
                            }
                        }
                        onProgress(index + 1, chunks.size)
                    }
                } finally {
                    Whisper.releaseModel(model)
                }
            }
        } catch (e: SubtitleCancelledException) {
            throw e
        } catch (e: UnsatisfiedLinkError) {
            throw SubtitleException(R.string.subtitle_unsupported_device, e)
        } catch (e: NoClassDefFoundError) {
            throw SubtitleException(R.string.subtitle_unsupported_device, e)
        } catch (e: Exception) {
            if (isCancelled()) throw SubtitleCancelledException()
            throw SubtitleException(R.string.subtitle_failed, e)
        }
        return postProcess(rawCues)
    }

    private fun cleanText(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun isSoundAnnotation(text: String): Boolean {
        val t = text.trim().lowercase()
        val bracketed = (t.startsWith("[") && t.endsWith("]")) ||
            (t.startsWith("(") && t.endsWith(")"))
        if (!bracketed) return false
        return listOf(
            "musik", "music", "suara", "tepuk", "applause", "hening", "silence",
            "tawa", "laughter", "noise", "kebisingan"
        ).any { it in t }
    }

    private fun splitSegment(startMs: Long, endMs: Long, text: String): List<SubtitleCue> {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = max(endMs, safeStart + 800L)
        val duration = safeEnd - safeStart
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val groups = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentChars = 0
        val totalChars = text.length.coerceAtLeast(1)
        for (word in words) {
            val added = if (current.isEmpty()) word.length else currentChars + 1 + word.length
            val projectedDuration = duration * added / totalChars
            if (current.isNotEmpty() && (added > MAX_CUE_CHARS || projectedDuration > MAX_CUE_MS)) {
                groups.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            if (current.isNotEmpty()) currentChars += 1
            current.add(word)
            currentChars += word.length
        }
        if (current.isNotEmpty()) groups.add(current)
        if (groups.isEmpty()) return emptyList()

        val cues = mutableListOf<SubtitleCue>()
        var consumedChars = 0
        val joinedLengths = groups.map { it.joinToString(" ").length }
        val joinedTotal = joinedLengths.sum().coerceAtLeast(1)
        for (i in groups.indices) {
            val groupText = groups[i].joinToString(" ")
            val cueStart = safeStart + duration * consumedChars / joinedTotal
            consumedChars += joinedLengths[i]
            val cueEnd = if (i == groups.lastIndex) {
                safeEnd
            } else {
                safeStart + duration * consumedChars / joinedTotal
            }
            cues.add(
                SubtitleCue(
                    startMs = cueStart,
                    endMs = max(cueEnd, cueStart + 700L),
                    text = wrapTwoLines(groupText)
                )
            )
            consumedChars += 1 // spasi antar grup
        }
        return cues
    }

    private fun wrapTwoLines(text: String): String {
        if (text.length <= MAX_LINE_CHARS) return text
        val words = text.split(" ")
        if (words.size < 2) return text
        var bestSplit = 1
        var bestScore = Int.MAX_VALUE
        for (split in 1 until words.size) {
            val first = words.subList(0, split).joinToString(" ")
            val second = words.subList(split, words.size).joinToString(" ")
            var score = kotlin.math.abs(first.length - second.length)
            if (first.length > MAX_LINE_CHARS) score += (first.length - MAX_LINE_CHARS) * 8
            if (second.length > MAX_LINE_CHARS) score += (second.length - MAX_LINE_CHARS) * 8
            if (score < bestScore) {
                bestScore = score
                bestSplit = split
            }
        }
        return words.subList(0, bestSplit).joinToString(" ") + "\n" +
            words.subList(bestSplit, words.size).joinToString(" ")
    }

    private fun postProcess(input: List<SubtitleCue>): List<SubtitleCue> {
        val sorted = input
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
        val out = mutableListOf<SubtitleCue>()
        for (cue in sorted) {
            val previous = out.lastOrNull()
            if (previous != null) {
                val sameText = previous.text.replace("\n", " ").trim()
                    .equals(cue.text.replace("\n", " ").trim(), ignoreCase = true)
                if (sameText && cue.startMs - previous.startMs < 1_500L) continue
                if (cue.startMs < previous.endMs) {
                    val adjustedPreviousEnd = max(
                        previous.startMs + 350L,
                        cue.startMs - 60L
                    )
                    out[out.lastIndex] = previous.copy(endMs = adjustedPreviousEnd)
                }
            }
            out.add(
                cue.copy(
                    startMs = cue.startMs.coerceAtLeast(0L),
                    endMs = max(cue.endMs, cue.startMs + 700L)
                )
            )
        }
        return out
    }

    private const val MAX_CUE_CHARS = 84
    private const val MAX_CUE_MS = 6_500L
    private const val MAX_LINE_CHARS = 42
}
