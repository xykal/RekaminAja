package id.rekaminaja.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import id.rekaminaja.app.subtitle.SubtitleCue
import id.rekaminaja.app.subtitle.SubtitleStore
import id.rekaminaja.app.subtitle.SubtitleUi
import id.rekaminaja.app.ui.RekDialogs
import id.rekaminaja.app.ui.RekPillButton
import id.rekaminaja.app.ui.RekSlider
import id.rekaminaja.app.ui.RekTopBar
import id.rekaminaja.app.util.Util
import java.io.File
import java.util.concurrent.Executors

class PlayerActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var uri: Uri
    private var isImage = false
    private var name: String = ""

    private lateinit var videoView: VideoView
    private lateinit var btnPlayPause: RekPillButton
    private lateinit var seekProgress: RekSlider
    private lateinit var tvTimeCurrent: TextView
    private lateinit var tvTimeTotal: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnSubtitle: RekPillButton
    private var userSeeking = false
    private var prepared = false
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var subtitlesEnabled = true
    private var currentSubtitleText: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!isImage) {
                try {
                    if (!userSeeking) {
                        seekProgress.setValueSilently(videoView.currentPosition / 1000)
                    }
                    val pos = videoView.currentPosition.toLong()
                    tvTimeCurrent.text = Util.formatDuration(pos)
                    updateSubtitleOverlay(pos)
                    updatePlayButton()
                } catch (e: Exception) {
                }
                handler.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        uri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: "")
        isImage = intent.getBooleanExtra(EXTRA_IS_IMAGE, false)
        name = intent.getStringExtra(EXTRA_NAME) ?: ""

        val topBar = findViewById<RekTopBar>(R.id.topBar)
        topBar.setTitleText(name)
        topBar.onBackClick = { finish() }

        videoView = findViewById(R.id.videoView)
        val imageView = findViewById<ImageView>(R.id.imageView)
        val btnTrim = findViewById<RekPillButton>(R.id.btnTrim)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekProgress = findViewById(R.id.seekProgress)
        tvTimeCurrent = findViewById(R.id.tvTimeCurrent)
        tvTimeTotal = findViewById(R.id.tvTimeTotal)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnSubtitle = findViewById(R.id.btnSubtitle)
        val controls = findViewById<View>(R.id.playerControls)

        if (isImage) {
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            btnTrim.visibility = View.GONE
            controls.visibility = View.GONE
            tvSubtitle.visibility = View.GONE
            btnSubtitle.visibility = View.GONE
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
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mp ->
                prepared = true
                seekProgress.maxValue = (mp.duration / 1000).coerceAtLeast(1)
                tvTimeTotal.text = Util.formatDuration(mp.duration.toLong())
                mp.start()
                updatePlayButton()
            }
            videoView.setOnCompletionListener {
                updatePlayButton()
                updateSubtitleOverlay(-1L)
            }
            videoView.setOnClickListener { togglePlay() }
            btnPlayPause.setOnClickListener { togglePlay() }
            seekProgress.onValueChange = { v, fromUser ->
                if (fromUser) {
                    userSeeking = true
                    tvTimeCurrent.text = Util.formatDuration(v * 1000L)
                }
            }
            seekProgress.onTouchEnd = {
                userSeeking = false
                try {
                    videoView.seekTo(seekProgress.value * 1000)
                    handler.postDelayed({ refreshSubtitleAtCurrentPosition() }, 150)
                } catch (e: Exception) {
                }
            }
            handler.post(ticker)
        }

        btnSubtitle.setOnClickListener { onSubtitleButton() }
        if (!isImage) refreshSubtitleUi()
        btnTrim.setOnClickListener {
            startActivity(
                Intent(this, TrimActivity::class.java)
                    .putExtra(TrimActivity.EXTRA_URI, uri.toString())
                    .putExtra(TrimActivity.EXTRA_NAME, name)
            )
        }
        findViewById<RekPillButton>(R.id.btnShare).setOnClickListener { share() }
        findViewById<RekPillButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }
    }


    private fun subtitleFile(): File = SubtitleStore.internalFile(this, name)

    private fun refreshSubtitleUi() {
        if (isImage) {
            btnSubtitle.visibility = View.GONE
            tvSubtitle.visibility = View.GONE
            return
        }
        val file = subtitleFile()
        if (file.isFile) {
            if (subtitleCues.isEmpty()) {
                subtitleCues = try {
                    SubtitleStore.parseSrt(file.readText(Charsets.UTF_8))
                } catch (e: Exception) {
                    emptyList()
                }
            }
            btnSubtitle.text = getString(
                if (subtitlesEnabled) R.string.subtitle_hide else R.string.subtitle_show
            )
        } else {
            subtitleCues = emptyList()
            currentSubtitleText = null
            tvSubtitle.visibility = View.GONE
            btnSubtitle.text = getString(R.string.auto_subtitle)
        }
        btnSubtitle.setIcon(R.drawable.ic_subtitle)
    }

    private fun onSubtitleButton() {
        if (isImage) return
        val file = subtitleFile()
        if (!file.isFile) {
            pauseForSubtitleGeneration()
            SubtitleUi.generate(this, uri, name) { _, cues ->
                subtitleCues = cues
                subtitlesEnabled = true
                currentSubtitleText = null
                refreshSubtitleUi()
                refreshSubtitleAtCurrentPosition()
            }
            return
        }
        refreshSubtitleUi()
        RekDialogs.showOptions(
            this,
            getString(R.string.auto_subtitle),
            listOf(
                getString(if (subtitlesEnabled) R.string.subtitle_hide else R.string.subtitle_show),
                getString(R.string.subtitle_share_srt),
                getString(R.string.subtitle_regenerate)
            )
        ) { index ->
            when (index) {
                0 -> {
                    subtitlesEnabled = !subtitlesEnabled
                    currentSubtitleText = null
                    refreshSubtitleUi()
                    refreshSubtitleAtCurrentPosition()
                }
                1 -> SubtitleUi.shareSrt(this, file)
                2 -> {
                    pauseForSubtitleGeneration()
                    SubtitleUi.generate(this, uri, name) { _, cues ->
                        subtitleCues = cues
                        subtitlesEnabled = true
                        currentSubtitleText = null
                        refreshSubtitleUi()
                        refreshSubtitleAtCurrentPosition()
                    }
                }
            }
        }
    }

    private fun pauseForSubtitleGeneration() {
        if (!isImage && prepared) {
            try {
                if (videoView.isPlaying) videoView.pause()
            } catch (e: Exception) {
            }
            updatePlayButton()
        }
    }

    private fun refreshSubtitleAtCurrentPosition() {
        if (isImage || !prepared) {
            tvSubtitle.visibility = View.GONE
            return
        }
        try {
            updateSubtitleOverlay(videoView.currentPosition.toLong())
        } catch (e: Exception) {
            tvSubtitle.visibility = View.GONE
        }
    }

    private fun updateSubtitleOverlay(positionMs: Long) {
        if (isImage || !subtitlesEnabled || subtitleCues.isEmpty() || positionMs < 0L) {
            if (tvSubtitle.visibility != View.GONE) tvSubtitle.visibility = View.GONE
            currentSubtitleText = null
            return
        }
        val cue = subtitleCues.firstOrNull {
            positionMs >= it.startMs && positionMs < it.endMs
        }
        if (cue == null) {
            if (tvSubtitle.visibility != View.GONE) tvSubtitle.visibility = View.GONE
            currentSubtitleText = null
            return
        }
        if (currentSubtitleText != cue.text || tvSubtitle.visibility != View.VISIBLE) {
            tvSubtitle.text = cue.text
            tvSubtitle.visibility = View.VISIBLE
            currentSubtitleText = cue.text
        }
    }

    private fun togglePlay() {
        try {
            if (videoView.isPlaying) videoView.pause() else videoView.start()
        } catch (e: Exception) {
        }
        handler.postDelayed({ updatePlayButton() }, 120)
    }

    private fun updatePlayButton() {
        val playing = try {
            videoView.isPlaying
        } catch (e: Exception) {
            false
        }
        btnPlayPause.text = getString(if (playing) R.string.pause else R.string.play)
        btnPlayPause.setIcon(if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play)
    }

    override fun onPause() {
        super.onPause()
        if (!isImage) {
            try {
                if (videoView.isPlaying) videoView.pause()
            } catch (e: Exception) {
            }
        }
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
        RekDialogs.showConfirm(
            this, getString(R.string.delete), name, getString(R.string.delete)
        ) {
            executor.execute {
                val ok = try {
                    if (uri.scheme == "file") File(uri.path!!).delete()
                    else contentResolver.delete(uri, null, null) > 0
                } catch (e: Exception) {
                    false
                }
                runOnUiThread {
                    if (ok) {
                        try {
                            subtitleFile().delete()
                        } catch (e: Exception) {
                        }
                        Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_IS_IMAGE = "is_image"
        const val EXTRA_NAME = "name"
    }
}
