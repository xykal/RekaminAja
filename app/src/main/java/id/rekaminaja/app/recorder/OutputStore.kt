package id.rekaminaja.app.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rekaman selalu ditulis ke file sementara di folder khusus-app,
 * lalu diterbitkan ke Movies/RekaminAja (MediaStore modern / folder publik lawas).
 */
object OutputStore {

    const val FOLDER = "RekaminAja"

    private fun stamp(prefix: String, ext: String): String =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ext

    fun videoDisplayName(): String = stamp("REC_", ".mp4")
    fun imageDisplayName(): String = stamp("SHOT_", ".png")
    fun trimmedDisplayName(): String = stamp("TRIM_", ".mp4")

    fun newTempVideoFile(context: Context, displayName: String): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(base, ".pending").apply { mkdirs() }
        return File(dir, displayName)
    }

    /** Folder fallback yang dipindai Galeri saat publish ke MediaStore gagal. */
    fun fallbackFolder(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, FOLDER).apply { mkdirs() }
    }

    /** @return Uri hasil publish, atau null bila gagal (file dipindah ke folder fallback). */
    fun publishVideo(context: Context, temp: File, displayName: String): Uri? {
        if (!temp.exists() || temp.length() == 0L) {
            temp.delete()
            return null
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(
                    context, temp, displayName,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    "video/mp4", Environment.DIRECTORY_MOVIES
                )
            } else {
                publishLegacy(context, temp, displayName, Environment.DIRECTORY_MOVIES)
            }
        } catch (e: Exception) {
            moveToFallback(temp, displayName, context)
            null
        }
    }

    fun publishImage(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val dir = File(base, ".pending").apply { mkdirs() }
        val temp = File(dir, displayName)
        FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(
                    context, temp, displayName,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "image/png", Environment.DIRECTORY_PICTURES
                )
            } else {
                publishLegacy(context, temp, displayName, Environment.DIRECTORY_PICTURES)
            }
        } catch (e: Exception) {
            moveToFallback(temp, displayName, context)
            null
        }
    }

    private fun publishViaMediaStore(
        context: Context, temp: File, displayName: String,
        collection: Uri, mime: String, dirType: String
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirType/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            temp.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        temp.delete()
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(
        context: Context, temp: File, displayName: String, dirType: String
    ): Uri? {
        val pub = File(Environment.getExternalStoragePublicDirectory(dirType), FOLDER)
            .apply { mkdirs() }
        val dest = File(pub, displayName)
        temp.inputStream().use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        temp.delete()
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return Uri.fromFile(dest)
    }

    private fun moveToFallback(temp: File, displayName: String, context: Context) {
        try {
            val dest = File(fallbackFolder(context), displayName)
            if (temp.renameTo(dest).not()) {
                temp.inputStream().use { i -> FileOutputStream(dest).use { i.copyTo(it) } }
                temp.delete()
            }
        } catch (e: Exception) {
            // Biarkan; pemanggil akan memberi tahu user publish gagal.
        }
    }
}
