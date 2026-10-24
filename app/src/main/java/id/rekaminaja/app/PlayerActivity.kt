package id.rekaminaja.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class PlayerActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var uri: Uri
    private var isImage = false
    private var name: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        uri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: "")
        isImage = intent.getBooleanExtra(EXTRA_IS_IMAGE, false)
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        supportActionBar?.title = name

        val videoView = findViewById<VideoView>(R.id.videoView)
        val imageView = findViewById<ImageView>(R.id.imageView)
        val btnTrim = findViewById<Button>(R.id.btnTrim)

        if (isImage) {
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            btnTrim.visibility = View.GONE
            executor.execute {
                val bmp = try {
                    val stream = if (uri.scheme == "file") File(uri.path!!).inputStream()
                    else contentResolver.openInputStream(uri)
                    stream?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
                runOnUiThread { if (bmp != null) imageView.setImageBitmap(bmp) }
            }
        } else {
            imageView.visibility = View.GONE
            val controller = MediaController(this)
            controller.setAnchorView(videoView)
            videoView.setMediaController(controller)
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { it.start() }
        }

        btnTrim.setOnClickListener {
            startActivity(
                Intent(this, TrimActivity::class.java)
                    .putExtra(TrimActivity.EXTRA_URI, uri.toString())
                    .putExtra(TrimActivity.EXTRA_NAME, name)
            )
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { share() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun share() {
        val out: Uri = try {
            if (uri.scheme == "file") {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", File(uri.path!!))
            } else {
                uri
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_start_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (isImage) "image/png" else "video/mp4"
            putExtra(Intent.EXTRA_STREAM, out)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                executor.execute {
                    val ok = try {
                        if (uri.scheme == "file") File(uri.path!!).delete()
                        else contentResolver.delete(uri, null, null) > 0
                    } catch (e: Exception) {
                        false
                    }
                    runOnUiThread {
                        if (ok) {
                            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_IS_IMAGE = "is_image"
        const val EXTRA_NAME = "name"
    }
}
