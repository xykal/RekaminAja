package id.rekaminaja.app.subtitle

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/** Satu cue subtitle. Waktu dalam milidetik dari awal video. */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/** Error Auto Subtitle yang pesannya diambil dari string resource agar tetap bilingual. */
class SubtitleException(
    val messageRes: Int,
    cause: Throwable? = null
) : Exception("Auto subtitle error: $messageRes", cause)

class SubtitleCancelledException : Exception("Auto subtitle cancelled")

/**
 * Penyimpanan SRT dan metadata sumber audio rekaman.
 *
 * SRT selalu disimpan di filesDir agar Player dapat menampilkannya dan
 * membagikannya lewat FileProvider. Setelah itu aplikasi mencoba menerbitkan
 * salinan publik ke Movies/RekaminAja (atau Download/RekaminAja sebagai cadangan).
 */
object SubtitleStore {

    private const val SOURCE_PREFS = "subtitle_audio_sources"

    fun subtitleName(videoName: String): String {
        val base = videoName.substringBeforeLast('.', videoName)
            .ifBlank { "RekaminAja" }
        return "$base.srt"
    }

    fun internalFile(context: Context, videoName: String): File {
        val dir = File(context.filesDir, "subtitles").apply { mkdirs() }
        return File(dir, subtitleName(videoName))
    }

    fun existingInternalFile(context: Context, videoName: String): File? {
        val file = internalFile(context, videoName)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun deleteInternal(context: Context, videoName: String) {
        try {
            internalFile(context, videoName).delete()
        } catch (e: Exception) {
        }
    }

    fun rememberAudioSource(context: Context, videoName: String, source: String) {
        try {
            val prefs = context.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit().putString(videoName, source)
            // Peta ini hanya petunjuk agar subtitle tidak dipaksakan ke audio
            // internal murni. Batasi ukurannya kasar agar tidak tumbuh selamanya.
            if (prefs.all.size > 350) {
                edit.clear().putString(videoName, source)
            }
            edit.apply()
        } catch (e: Exception) {
        }
    }

    fun audioSourceFor(context: Context, videoName: String): String? {
        return try {
            context.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
                .getString(videoName, null)
        } catch (e: Exception) {
            null
        }
    }

    fun writeInternal(context: Context, videoName: String, cues: List<SubtitleCue>): File {
        val target = internalFile(context, videoName)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(formatSrt(cues), Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    fun readCues(file: File): List<SubtitleCue> {
        if (!file.isFile) return emptyList()
        return parseSrt(file.readText(Charsets.UTF_8))
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun formatSrt(cues: List<SubtitleCue>): String {
        val out = StringBuilder()
        cues.forEachIndexed { index, cue ->
            out.append(index + 1).append("\n")
            out.append(formatTime(cue.startMs))
                .append(" --> ")
                .append(formatTime(cue.endMs.coerceAtLeast(cue.startMs + 1)))
                .append("\n")
            out.append(cue.text.trim()).append("\n\n")
        }
        return out.toString()
    }

    fun parseSrt(text: String): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        val timeRegex = Regex(
            """(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s+-->\s+(\d+):(\d{2}):(\d{2})[,.](\d{1,3})"""
        )
        val blocks = text.replace("\r\n", "\n").split(Regex("""\n\s*\n"""))
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size < 2) continue
            val timeIndex = lines.indexOfFirst { timeRegex.containsMatchIn(it) }
            if (timeIndex < 0 || timeIndex >= lines.lastIndex) continue
            val m = timeRegex.find(lines[timeIndex]) ?: continue
            val start = toMs(m.groupValues, 1)
            val end = toMs(m.groupValues, 5)
            val cueText = lines.drop(timeIndex + 1).joinToString("\n").trim()
            if (cueText.isNotBlank() && end > start) {
                result.add(SubtitleCue(start, end, cueText))
            }
        }
        return result.sortedBy { it.startMs }
    }

    private fun toMs(groups: List<String>, offset: Int): Long {
        val h = groups[offset].toLongOrNull() ?: 0L
        val min = groups[offset + 1].toLongOrNull() ?: 0L
        val sec = groups[offset + 2].toLongOrNull() ?: 0L
        val ms = groups[offset + 3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return (((h * 60 + min) * 60) + sec) * 1000L + ms
    }

    private fun formatTime(msIn: Long): String {
        val ms = msIn.coerceAtLeast(0L)
        val h = ms / 3_600_000L
        val min = (ms % 3_600_000L) / 60_000L
        val sec = (ms % 60_000L) / 1000L
        val milli = ms % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, min, sec, milli)
    }

    /** Terbitkan salinan SRT yang mudah ditemukan user. Kegagalan di sini tidak fatal. */
    fun publishPublic(context: Context, internal: File, videoName: String): Uri? {
        val displayName = subtitleName(videoName)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(
                    context, internal, displayName,
                    MediaStore.Files.getContentUri("external"),
                    "${Environment.DIRECTORY_MOVIES}/RekaminAja"
                ) ?: publishViaMediaStore(
                    context, internal, displayName,
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${Environment.DIRECTORY_DOWNLOADS}/RekaminAja"
                )
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "RekaminAja"
                ).apply { mkdirs() }
                val dest = File(dir, displayName)
                internal.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(
                    context, arrayOf(dest.absolutePath), arrayOf("application/x-subrip"), null
                )
                Uri.fromFile(dest)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun publishViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        collection: Uri,
        relativePath: String
    ): Uri? {
        val resolver = context.contentResolver
        deleteExisting(resolver, collection, displayName, relativePath)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        val copied = try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            false
        }
        if (!copied) {
            try {
                resolver.delete(uri, null, null)
            } catch (e: Exception) {
            }
            return null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun deleteExisting(
        resolver: android.content.ContentResolver,
        collection: Uri,
        displayName: String,
        relativePath: String
    ) {
        val paths = listOf(relativePath, "$relativePath/")
        for (path in paths) {
            try {
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(displayName, path)
                )
            } catch (e: Exception) {
            }
        }
    }
}
