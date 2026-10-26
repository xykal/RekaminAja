package id.rekaminaja.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import id.rekaminaja.app.data.GalleryLoader
import id.rekaminaja.app.data.MediaItem
import id.rekaminaja.app.ui.RekDialogs
import id.rekaminaja.app.ui.RekTopBar
import java.io.File
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private lateinit var adapter: MediaAdapter
    private lateinit var emptyView: View
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        findViewById<RekTopBar>(R.id.topBar).onBackClick = { finish() }

        emptyView = findViewById(R.id.emptyView)
        adapter = MediaAdapter(
            onClick = { openPlayer(it) },
            onLongClick = { showOptions(it) }
        )
        val rv = findViewById<RecyclerView>(R.id.galleryList)
        rv.layoutManager = GridLayoutManager(this, 2)
        rv.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        executor.execute {
            val items = GalleryLoader.load(this)
            runOnUiThread {
                adapter.submit(items)
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openPlayer(item: MediaItem) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URI, item.uri.toString())
                .putExtra(PlayerActivity.EXTRA_IS_IMAGE, item.isImage)
                .putExtra(PlayerActivity.EXTRA_NAME, item.name)
        )
    }

    private fun showOptions(item: MediaItem) {
        val labels = mutableListOf(
            getString(R.string.play),
            getString(R.string.share),
            getString(R.string.delete)
        )
        if (!item.isImage) {
            labels.add(1, getString(R.string.trim_video))
        }
        RekDialogs.showOptions(this, item.name, labels) { which ->
            when (labels[which]) {
                getString(R.string.play) -> openPlayer(item)
                getString(R.string.trim_video) -> openTrim(item)
                getString(R.string.share) -> share(item)
                getString(R.string.delete) -> confirmDelete(item)
            }
        }
    }

    private fun openTrim(item: MediaItem) {
        startActivity(
            Intent(this, TrimActivity::class.java)
                .putExtra(TrimActivity.EXTRA_URI, item.uri.toString())
                .putExtra(TrimActivity.EXTRA_NAME, item.name)
        )
    }

    private fun share(item: MediaItem) {
        val uri = shareableUri(item) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (item.isImage) "image/png" else "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun shareableUri(item: MediaItem): Uri? {
        return try {
            if (item.isFileUri) {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", File(item.uri.path!!))
            } else {
                item.uri
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_start_failed, Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun confirmDelete(item: MediaItem) {
        RekDialogs.showConfirm(
            this, getString(R.string.delete), item.name, getString(R.string.delete)
        ) { delete(item) }
    }

    private fun delete(item: MediaItem) {
        executor.execute {
            val ok = try {
                if (item.isFileUri) {
                    File(item.uri.path!!).delete()
                } else {
                    contentResolver.delete(item.uri, null, null) > 0
                }
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    adapter.remove(item)
                    Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
