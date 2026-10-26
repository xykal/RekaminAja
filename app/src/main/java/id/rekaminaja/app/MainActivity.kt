package id.rekaminaja.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import id.rekaminaja.app.ui.RecordButton
import id.rekaminaja.app.ui.RekPillButton
import id.rekaminaja.app.util.Util
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRecord: RecordButton
    private lateinit var tvRecordLabel: TextView
    private lateinit var btnPause: RekPillButton
    private lateinit var tvSummary: TextView
    private lateinit var cardOverlayWarn: View
    private lateinit var tvStorage: TextView
    private lateinit var tvRecentEmpty: TextView
    private lateinit var recentAdapter: MediaAdapter

    private enum class ToolKind { BUBBLE, FACECAM, DRAW, CENSOR, SENSITIVE, PODCAST }
    private data class TileRef(val kind: ToolKind, val stateView: TextView)
    private val tileRefs = mutableListOf<TileRef>()

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
        tvTimer = findViewById(R.id.tvTimer)
        btnRecord = findViewById(R.id.btnRecord)
        tvRecordLabel = findViewById(R.id.tvRecordLabel)
        btnPause = findViewById(R.id.btnPause)
        tvSummary = findViewById(R.id.tvSummary)
        cardOverlayWarn = findViewById(R.id.cardOverlayWarn)
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
        findViewById<RekPillButton>(R.id.btnScreenshot).setOnClickListener {
            if (RecorderState.isRecording) {
                Toast.makeText(this, R.string.screenshot_while_recording, Toast.LENGTH_SHORT).show()
            } else {
                openConsent(ConsentActivity.MODE_SCREENSHOT)
            }
        }
        findViewById<RekPillButton>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<RekPillButton>(R.id.btnOverlayAllow).setOnClickListener { openOverlaySettings() }
        findViewById<View>(R.id.cardSummary).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        buildToolTiles()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleAutoIntent(intent)
    }

    // ---------------------------------------------------------------- ubin alat

    private fun buildToolTiles() {
        val grid = findViewById<GridLayout>(R.id.toolsGrid)
        data class Def(
            val kind: ToolKind, val icon: Int, val tint: Int, val tile: Int, val title: Int
        )
        val defs = listOf(
            Def(ToolKind.BUBBLE, R.drawable.ic_bubble, R.color.accent_slate, R.color.accent_slate_tint, R.string.tool_bubble),
            Def(ToolKind.FACECAM, R.drawable.ic_camera, R.color.accent_blue, R.color.accent_blue_tint, R.string.facecam),
            Def(ToolKind.DRAW, R.drawable.ic_pen, R.color.accent_violet, R.color.accent_violet_tint, R.string.draw_on_screen),
            Def(ToolKind.CENSOR, R.drawable.ic_shield, R.color.accent_green, R.color.accent_green_tint, R.string.censor_zones),
            Def(ToolKind.SENSITIVE, R.drawable.ic_lock, R.color.accent_amber, R.color.accent_amber_tint, R.string.sensitive_apps),
            Def(ToolKind.PODCAST, R.drawable.ic_wave, R.color.brand, R.color.brand_tint, R.string.podcast_fx)
        )
        defs.forEachIndexed { idx, def ->
            val tile = makeToolTile(def.icon, def.tint, def.tile, def.title, def.kind)
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(idx / 3), GridLayout.spec(idx % 3, 1f)
            ).apply {
                width = 0
                val m = Util.dp(this@MainActivity, 4)
                setMargins(m, m, m, m)
            }
            grid.addView(tile, lp)
        }
    }

    private fun makeToolTile(
        iconRes: Int, tintRes: Int, tileRes: Int, titleRes: Int, kind: ToolKind
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            isClickable = true
            isFocusable = true
            minimumHeight = Util.dp(this@MainActivity, 108)
            val p = Util.dp(this@MainActivity, 12)
            setPadding(p, p, p, p)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            elevation = Util.dp(this@MainActivity, 1).toFloat()
        }
        val iconWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            val s = Util.dp(this@MainActivity, 38)
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = GradientDrawable().apply {
                cornerRadius = Util.dp(this@MainActivity, 12).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, tileRes))
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(this@MainActivity, tintRes))
            val s = Util.dp(this@MainActivity, 20)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        iconWrap.addView(icon)
        tile.addView(iconWrap)

        val title = TextView(this).apply {
            setText(titleRes)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(this@MainActivity, 10) }
        }
        tile.addView(title)

        val state = TextView(this).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            maxLines = 1
        }
        tile.addView(state)
        tileRefs.add(TileRef(kind, state))

        tile.setOnClickListener {
            when (kind) {
                ToolKind.BUBBLE -> toggleBubble()
                ToolKind.FACECAM -> onFacecamClicked()
                ToolKind.DRAW -> {
                    if (ensureOverlay()) startService(Intent(this, DrawOverlayService::class.java))
                }
                ToolKind.CENSOR -> startActivity(Intent(this, CensorEditorActivity::class.java))
                ToolKind.SENSITIVE -> startActivity(Intent(this, SensitiveAppsActivity::class.java))
                ToolKind.PODCAST -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        return tile
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
        autoShowBubble()
        updateUi()
        loadRecent()
    }

    /**
     * Bola melayang langsung muncul setiap app dibuka (pengaturan bola aktif
     * + izin overlay sudah diberikan), tanpa perlu mengetuk tile Bola dulu.
     * onResume juga menutup kasus user baru pulang dari halaman izin overlay.
     */
    private fun autoShowBubble() {
        if (!Prefs.floatingBubble || FloatingBubbleService.running) return
        if (!Util.canOverlay(this)) return
        try {
            startService(Intent(this, FloatingBubbleService::class.java))
            handler.postDelayed({ updateUi() }, 300)
        } catch (e: Exception) {
            // Batasan OEM: bola tetap bisa dinyalakan manual lewat tile Bola.
        }
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
            RecorderState.State.RECORDING -> getString(R.string.notif_recording)
            RecorderState.State.PAUSED -> getString(R.string.notif_paused)
            RecorderState.State.COUNTDOWN -> getString(R.string.notif_preparing)
            RecorderState.State.IDLE -> getString(R.string.ready_to_record)
        }
        tvStatus.background = ContextCompat.getDrawable(
            this,
            if (state == RecorderState.State.RECORDING) R.drawable.bg_chip_brand else R.drawable.bg_chip
        )
        tvTimer.text = Util.formatDuration(RecorderState.elapsedMs())
        tvTimer.setTextColor(
            ContextCompat.getColor(
                this,
                if (state == RecorderState.State.IDLE) R.color.faint else R.color.ink
            )
        )
        btnRecord.visual = when (state) {
            RecorderState.State.RECORDING -> RecordButton.Visual.RECORDING
            RecorderState.State.PAUSED -> RecordButton.Visual.PAUSED
            RecorderState.State.COUNTDOWN -> RecordButton.Visual.COUNTDOWN
            RecorderState.State.IDLE -> RecordButton.Visual.IDLE
        }
        tvRecordLabel.text = if (RecorderState.isRecording ||
            state == RecorderState.State.COUNTDOWN
        ) {
            getString(R.string.stop)
        } else {
            getString(R.string.start_recording)
        }
        btnPause.visibility = if (RecorderState.isRecording) View.VISIBLE else View.GONE
        if (state == RecorderState.State.PAUSED) {
            btnPause.text = getString(R.string.resume)
            btnPause.setIcon(R.drawable.ic_play)
        } else {
            btnPause.text = getString(R.string.pause)
            btnPause.setIcon(R.drawable.ic_pause)
        }
        cardOverlayWarn.visibility = if (Util.canOverlay(this)) View.GONE else View.VISIBLE
        updateTiles()

        val resLabel = when (Prefs.resolution) {
            2160 -> getString(R.string.res_uhd)
            0 -> getString(R.string.res_native)
            1440 -> getString(R.string.res_qhd)
            else -> "${Prefs.resolution}p"
        }
        val bitLabel = if (Prefs.bitrateMbps == 0) getString(R.string.bitrate_auto)
        else "${Prefs.bitrateMbps} Mbps"
        val codecLabel = if (Prefs.videoCodec == Prefs.CODEC_HEVC) " - HEVC" else ""
        val audioLabel = when (Prefs.audioSource) {
            Prefs.AUDIO_NONE -> getString(R.string.audio_none)
            Prefs.AUDIO_INTERNAL -> getString(R.string.audio_internal)
            Prefs.AUDIO_MIX -> getString(R.string.audio_mix)
            else -> getString(R.string.audio_mic)
        }
        val voicePresetRes = when (Prefs.voicePreset) {
            "clean" -> R.string.fx_preset_clean
            "bass" -> R.string.fx_preset_bass
            "echo" -> R.string.fx_preset_echo
            "reverb" -> R.string.fx_preset_reverb
            "alien" -> R.string.fx_preset_alien
            "robot" -> R.string.fx_preset_robot
            "chipmunk" -> R.string.fx_preset_chipmunk
            else -> 0
        }
        val fxSuffix = when {
            voicePresetRes != 0 -> " + " + getString(voicePresetRes)
            Prefs.anyAudioFx -> " + " + getString(R.string.podcast_fx)
            Prefs.micVolume != 100 -> " + " + getString(R.string.notif_mic_format, Prefs.micVolume)
            else -> ""
        }
        tvSummary.text = "$resLabel - ${Prefs.fps} FPS - $bitLabel$codecLabel\n$audioLabel$fxSuffix"
        tvStorage.text = getString(R.string.storage_free_format, Util.formatSize(freeBytes()))
    }

    private fun updateTiles() {
        for (ref in tileRefs) {
            ref.stateView.text = when (ref.kind) {
                ToolKind.BUBBLE -> getString(
                    if (FloatingBubbleService.running) R.string.state_on else R.string.state_off
                )
                ToolKind.FACECAM -> getString(
                    if (RecorderState.facecamVisible || Prefs.facecamEnabled) {
                        R.string.state_on
                    } else {
                        R.string.state_off
                    }
                )
                ToolKind.DRAW -> getString(R.string.tap_to_open)
                ToolKind.CENSOR -> getString(
                    R.string.zones_count_format, Prefs.censorZones.size
                )
                ToolKind.SENSITIVE -> getString(
                    R.string.apps_count_format, Prefs.sensitivePackages.size
                )
                ToolKind.PODCAST -> getString(
                    if (Prefs.micFxActive) R.string.state_on else R.string.state_off
                )
            }
        }
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
