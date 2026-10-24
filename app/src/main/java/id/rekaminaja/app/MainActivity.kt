package id.rekaminaja.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import id.rekaminaja.app.data.GalleryLoader
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.overlay.DrawOverlayService
import id.rekaminaja.app.overlay.FloatingBubbleService
import id.rekaminaja.app.recorder.RecorderState
import id.rekaminaja.app.recorder.ScreenRecordService
import id.rekaminaja.app.util.Util
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPause: Button
    private lateinit var tvSummary: TextView
    private lateinit var cardOverlayWarn: View
    private lateinit var btnBubble: Button
    private lateinit var btnFacecam: Button
    private lateinit var tvStorage: TextView
    private lateinit var tvRecentEmpty: TextView
    private lateinit var recentAdapter: MediaAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val stateListener: (RecorderState.State) -> Unit = { updateUi() }

    private val uiTicker = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 500)
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.storage_denied_note, Toast.LENGTH_LONG).show()
        openConsent(ConsentActivity.MODE_RECORD)
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (RecorderState.isRecording) {
                sendRecorder(ScreenRecordService.ACTION_TOGGLE_FACECAM)
            } else {
                Prefs.facecamEnabled = true
            }
        } else {
            Toast.makeText(this, R.string.facecam_permission, Toast.LENGTH_LONG).show()
        }
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
        tvSummary = findViewById(R.id.tvSummary)
        cardOverlayWarn = findViewById(R.id.cardOverlayWarn)
        btnBubble = findViewById(R.id.btnBubble)
        btnFacecam = findViewById(R.id.btnFacecam)
        tvStorage = findViewById(R.id.tvStorage)
        tvRecentEmpty = findViewById(R.id.tvRecentEmpty)

        recentAdapter = MediaAdapter(
            onClick = { item ->
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_URI, item.uri.toString())
                        .putExtra(PlayerActivity.EXTRA_IS_IMAGE, item.isImage)
                        .putExtra(PlayerActivity.EXTRA_NAME, item.name)
                )
            },
            onLongClick = { startActivity(Intent(this, GalleryActivity::class.java)) }
        )
        val rvRecent = findViewById<RecyclerView>(R.id.rvRecent)
        rvRecent.layoutManager = GridLayoutManager(this, 3)
        rvRecent.adapter = recentAdapter
        rvRecent.isNestedScrollingEnabled = false

        btnRecord.setOnClickListener { onRecordClicked() }
        btnPause.setOnClickListener { sendRecorder(ScreenRecordService.ACTION_TOGGLE_PAUSE) }
        findViewById<Button>(R.id.btnScreenshot).setOnClickListener {
            if (RecorderState.isRecording) {
                Toast.makeText(this, R.string.screenshot_while_recording, Toast.LENGTH_SHORT).show()
            } else {
                openConsent(ConsentActivity.MODE_SCREENSHOT)
            }
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        btnBubble.setOnClickListener { toggleBubble() }
        btnFacecam.setOnClickListener { onFacecamClicked() }
        findViewById<Button>(R.id.btnDraw).setOnClickListener {
            if (ensureOverlay()) startService(Intent(this, DrawOverlayService::class.java))
        }
        findViewById<Button>(R.id.btnCensor).setOnClickListener {
            startActivity(Intent(this, CensorEditorActivity::class.java))
        }
        findViewById<Button>(R.id.btnSensitive).setOnClickListener {
            startActivity(Intent(this, SensitiveAppsActivity::class.java))
        }
        findViewById<Button>(R.id.btnOverlayAllow).setOnClickListener { openOverlaySettings() }
        tvSummary.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleAutoIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutoIntent(intent)
    }

    private fun handleAutoIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_AUTO)) {
            AUTO_RECORD -> openConsent(ConsentActivity.MODE_RECORD)
            AUTO_SCREENSHOT -> openConsent(ConsentActivity.MODE_SCREENSHOT)
            else -> Unit
        }
    }

    override fun onStart() {
        super.onStart()
        RecorderState.addListener(stateListener)
        handler.post(uiTicker)
    }

    override fun onStop() {
        RecorderState.removeListener(stateListener)
        handler.removeCallbacks(uiTicker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        loadRecent()
    }

    // ---------------------------------------------------------------- actions

    private fun onRecordClicked() {
        when {
            RecorderState.isRecording || RecorderState.state == RecorderState.State.COUNTDOWN -> {
                sendRecorder(ScreenRecordService.ACTION_STOP)
            }
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> openConsent(ConsentActivity.MODE_RECORD)
        }
    }

    private fun toggleBubble() {
        if (!ensureOverlay()) return
        if (FloatingBubbleService.running) {
            stopService(Intent(this, FloatingBubbleService::class.java))
        } else {
            startService(Intent(this, FloatingBubbleService::class.java))
        }
        handler.postDelayed({ updateUi() }, 300)
    }

    private fun onFacecamClicked() {
        if (RecorderState.isRecording) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                sendRecorder(ScreenRecordService.ACTION_TOGGLE_FACECAM)
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else {
            val target = !Prefs.facecamEnabled
            if (target && !hasPermission(Manifest.permission.CAMERA)) {
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                Prefs.facecamEnabled = target
            }
        }
        handler.postDelayed({ updateUi() }, 300)
    }

    private fun ensureOverlay(): Boolean {
        if (Util.canOverlay(this)) return true
        openOverlaySettings()
        return false
    }

    private fun openOverlaySettings() {
        Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_LONG).show()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openConsent(mode: String) {
        startActivity(
            Intent(this, ConsentActivity::class.java)
                .putExtra(ConsentActivity.EXTRA_MODE, mode)
        )
    }

    private fun sendRecorder(action: String) {
        try {
            startService(Intent(this, ScreenRecordService::class.java).setAction(action))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun updateUi() {
        val state = RecorderState.state
        tvStatus.text = when (state) {
            RecorderState.State.RECORDING ->
                "${getString(R.string.notif_recording)} • ${Util.formatDuration(RecorderState.elapsedMs())}"
            RecorderState.State.PAUSED ->
                "${getString(R.string.notif_paused)} • ${Util.formatDuration(RecorderState.elapsedMs())}"
            RecorderState.State.COUNTDOWN -> getString(R.string.notif_preparing)
            RecorderState.State.IDLE -> getString(R.string.ready_to_record)
        }
        btnRecord.text = if (RecorderState.isRecording ||
            state == RecorderState.State.COUNTDOWN
        ) {
            getString(R.string.stop)
        } else {
            getString(R.string.start_recording)
        }
        btnPause.visibility = if (RecorderState.isRecording) View.VISIBLE else View.GONE
        btnPause.text = if (state == RecorderState.State.PAUSED) {
            getString(R.string.resume)
        } else {
            getString(R.string.pause)
        }
        cardOverlayWarn.visibility = if (Util.canOverlay(this)) View.GONE else View.VISIBLE
        btnBubble.text = getString(
            if (FloatingBubbleService.running) R.string.bubble_on else R.string.bubble_off
        )
        btnFacecam.text = getString(
            if (RecorderState.facecamVisible || Prefs.facecamEnabled) {
                R.string.facecam_on
            } else {
                R.string.facecam_off
            }
        )
        val resLabel = if (Prefs.resolution == 0) getString(R.string.res_native)
        else "${Prefs.resolution}p"
        val bitLabel = if (Prefs.bitrateMbps == 0) getString(R.string.bitrate_auto)
        else "${Prefs.bitrateMbps} Mbps"
        val audioLabel = when (Prefs.audioSource) {
            Prefs.AUDIO_NONE -> getString(R.string.audio_none)
            Prefs.AUDIO_INTERNAL -> getString(R.string.audio_internal)
            Prefs.AUDIO_MIX -> getString(R.string.audio_mix)
            else -> getString(R.string.audio_mic)
        }
        tvSummary.text = "$resLabel • ${Prefs.fps} FPS • $bitLabel\n$audioLabel"
        tvStorage.text = getString(R.string.storage_free_format, Util.formatSize(freeBytes()))
    }

    private fun freeBytes(): Long {
        return try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun loadRecent() {
        executor.execute {
            val items = GalleryLoader.load(this, limit = 6)
            runOnUiThread {
                recentAdapter.submit(items)
                tvRecentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTO = "auto"
        const val AUTO_RECORD = "record"
        const val AUTO_SCREENSHOT = "screenshot"
    }
}
