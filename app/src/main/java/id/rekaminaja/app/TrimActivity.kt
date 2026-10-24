package id.rekaminaja.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import id.rekaminaja.app.data.GalleryLoader
import id.rekaminaja.app.ui.RekPillButton
import id.rekaminaja.app.ui.RekSlider
import id.rekaminaja.app.ui.RekSpinner
import id.rekaminaja.app.ui.RekTopBar
import id.rekaminaja.app.recorder.OutputStore
import id.rekaminaja.app.util.Util
import id.rekaminaja.app.util.VideoTrimmer
import java.io.File
import java.util.concurrent.Executors

/**
 * Editor dasar: potong awal/akhir video tanpa re-encode (cepat, lossless,
 * presisi keyframe) — tanpa FFmpeg.
 */
class TrimActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var uri: Uri
    private lateinit var videoView: VideoView
    private lateinit var seekStart: RekSlider
    private lateinit var seekEnd: RekSlider
    private lateinit var labelRange: TextView
    private lateinit var tvStartValue: TextView
    private lateinit var tvEndValue: TextView
    private lateinit var progress: RekSpinner
    private var durationMs = 0L

    private val endWatcher = object : Runnable {
        override fun run() {
            if (videoView.isPlaying && videoView.currentPosition >= endMs()) {
                videoView.pause()
            } else if (videoView.isPlaying) {
                handler.postDelayed(this, 200)
            }
        }
    }

    private fun startMs(): Long = seekStart.value * 1_000L
    private fun endMs(): Long = seekEnd.value * 1_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trim)
        findViewById<RekTopBar>(R.id.topBar).onBackClick = { finish() }

        uri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: "")
        videoView = findViewById(R.id.trimVideo)
        seekStart = findViewById(R.id.seekStart)
        seekEnd = findViewById(R.id.seekEnd)
        labelRange = findViewById(R.id.labelRange)
        tvStartValue = findViewById(R.id.tvStartValue)
        tvEndValue = findViewById(R.id.tvEndValue)
        progress = findViewById(R.id.trimProgress)

        videoView.setVideoURI(uri)
        videoView.setOnClickListener {
            videoView.seekTo(startMs().toInt())
            videoView.start()
            handler.removeCallbacks(endWatcher)
            handler.post(endWatcher)
        }

        executor.execute {
            val dur = GalleryLoader.durationOf(this, uri)
            runOnUiThread {
                durationMs = dur
                val totalSec = (dur / 1_000).toInt().coerceAtLeast(1)
                seekStart.maxValue = totalSec
                seekEnd.maxValue = totalSec
                seekStart.setValueSilently(0)
                seekEnd.setValueSilently(totalSec)
                updateLabel()
            }
        }

        seekStart.onValueChange = { _, _ ->
            if (seekEnd.value <= seekStart.value) {
                seekEnd.setValueSilently((seekStart.value + 1).coerceAtMost(seekEnd.maxValue))
            }
            updateLabel()
        }
        seekEnd.onValueChange = { _, _ ->
            if (seekEnd.value <= seekStart.value) {
                seekStart.setValueSilently((seekEnd.value - 1).coerceAtLeast(0))
            }
            updateLabel()
        }

        findViewById<RekPillButton>(R.id.btnPreview).setOnClickListener {
            videoView.seekTo(startMs().toInt())
            videoView.start()
            handler.removeCallbacks(endWatcher)
            handler.post(endWatcher)
        }
        findViewById<RekPillButton>(R.id.btnSaveTrim).setOnClickListener { save() }
    }

    private fun updateLabel() {
        tvStartValue.text = Util.formatDuration(startMs())
        tvEndValue.text = Util.formatDuration(endMs())
        labelRange.text = getString(
            R.string.trim_range_format,
            Util.formatDuration(startMs()),
            Util.formatDuration(endMs()),
            Util.formatDuration((endMs() - startMs()).coerceAtLeast(0))
        )
    }

    private fun save() {
        if (durationMs <= 0) return
        progress.visibility = View.VISIBLE
        val start = startMs()
        val end = endMs()
        executor.execute {
            var ok = false
            try {
                val srcFile = File(cacheDir, "trim_source.mp4")
                GalleryLoader.copyToFile(this, uri, srcFile)
                val outName = OutputStore.trimmedDisplayName()
                val dest = OutputStore.newTempVideoFile(this, outName)
                if (VideoTrimmer.trim(srcFile, dest, start, end)) {
                    ok = OutputStore.publishVideo(this, dest, outName) != null ||
                        File(OutputStore.fallbackFolder(this), outName).exists()
                }
                srcFile.delete()
            } catch (e: Exception) {
                ok = false
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (ok) {
                    Toast.makeText(this, R.string.trim_saved, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, R.string.trim_failed, Toast.LENGTH_LONG).show()
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
        const val EXTRA_NAME = "name"
    }
}
