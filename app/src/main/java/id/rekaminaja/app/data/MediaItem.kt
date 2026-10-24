package id.rekaminaja.app.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import id.rekaminaja.app.R
import id.rekaminaja.app.recorder.OutputStore
import java.io.File
import java.util.concurrent.Executors

data class MediaItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateMs: Long,
    val isImage: Boolean
) {
    val isFileUri: Boolean get() = uri.scheme == "file"
}

object GalleryLoader {

    /** Muat dari MediaStore (folder RekaminAja) + folder fallback khusus-app. */
    fun load(context: Context, limit: Int = 0): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        items += queryCollection(context, isImage = false)
        items += queryCollection(context, isImage = true)
        val knownNames = items.map { it.name }.toHashSet()
        val fallback = OutputStore.fallbackFolder(context)
        fallback.listFiles()?.forEach { f ->
            val lower = f.name.lowercase()
            if ((lower.endsWith(".mp4") || lower.endsWith(".png")) && f.name !in knownNames) {
                items.add(
                    MediaItem(
                        Uri.fromFile(f), f.name, 0L, f.length(),
                        f.lastModified(), lower.endsWith(".png")
                    )
                )
            }
        }
        val sorted = items.sortedByDescending { it.dateMs }
        return if (limit > 0) sorted.take(limit) else sorted
    }

    private fun queryCollection(context: Context, isImage: Boolean): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        if (!isImage) projection.add(MediaStore.Video.Media.DURATION)
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
            (MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?") to arrayOf("%${OutputStore.FOLDER}%")
        } else {
            // DATA deprecated di API 29+, masih jalur resmi untuk API < 29.
            @Suppress("DEPRECATION")
            val dataCol = MediaStore.MediaColumns.DATA
            (dataCol + " LIKE ?") to arrayOf("%${OutputStore.FOLDER}%")
        }
        try {
            context.contentResolver.query(
                collection, projection.toTypedArray(), selection, args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val durCol = if (isImage) -1 else c.getColumnIndex(MediaStore.Video.Media.DURATION)
                while (c.moveToNext()) {
                    out.add(
                        MediaItem(
                            ContentUris.withAppendedId(collection, c.getLong(idCol)),
                            c.getString(nameCol) ?: "?",
                            if (durCol >= 0) c.getLong(durCol) else 0L,
                            c.getLong(sizeCol),
                            c.getLong(dateCol) * 1_000L,
                            isImage
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // MediaStore belum siap di sebagian perangkat — abaikan.
        }
        return out
    }

    /** Durasi video dari Uri (untuk item fallback yang durasinya 0). */
    fun durationOf(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") r.setDataSource(uri.path)
            else r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) r.release()
            } catch (e: Exception) {
            }
        }
    }

    /** Salin konten Uri ke file lokal (kebutuhan trimmer). */
    fun copyToFile(context: Context, uri: Uri, dest: File) {
        if (uri.scheme == "file") {
            File(uri.path!!).inputStream().use { i -> dest.outputStream().use { i.copyTo(it) } }
        } else {
            context.contentResolver.openInputStream(uri)?.use { i ->
                dest.outputStream().use { i.copyTo(it) }
            }
        }
    }
}

object ThumbLoader {
    private val cache = LruCache<String, Bitmap>(60)
    private val executor = Executors.newFixedThreadPool(3)

    fun load(context: Context, item: MediaItem, into: ImageView) {
        val key = item.uri.toString()
        into.tag = key
        cache.get(key)?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageResource(R.drawable.ic_gallery)
        val appContext = context.applicationContext
        executor.execute {
            val bmp = decode(appContext, item)
            if (bmp != null) {
                cache.put(key, bmp)
                into.post { if (into.tag == key) into.setImageBitmap(bmp) }
            }
        }
    }

    private fun decode(context: Context, item: MediaItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.uri.scheme == "content") {
                return context.contentResolver.loadThumbnail(item.uri, Size(360, 360), null)
            }
            if (item.isImage) {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                val stream = if (item.uri.scheme == "file") {
                    File(item.uri.path!!).inputStream()
                } else {
                    context.contentResolver.openInputStream(item.uri)
                }
                stream?.use { BitmapFactory.decodeStream(it, null, options) }
            } else {
                val r = MediaMetadataRetriever()
                try {
                    if (item.uri.scheme == "file") r.setDataSource(item.uri.path)
                    else r.setDataSource(context, item.uri)
                    r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) r.release()
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
