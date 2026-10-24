package id.rekaminaja.app.recorder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import id.rekaminaja.app.MainActivity
import id.rekaminaja.app.R
import id.rekaminaja.app.RekaminApp
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.overlay.FacecamController
import id.rekaminaja.app.overlay.FloatingBubbleService
import id.rekaminaja.app.overlay.OverlayUtil
import id.rekaminaja.app.overlay.PrivacyShieldController
import id.rekaminaja.app.subtitle.SubtitleStore
import id.rekaminaja.app.util.Util
import java.io.File
import java.util.concurrent.Executors

/**
 * Service perekam utama.
 *
 * Urutan wajib Android 14+:
 * 1) startForeground(type=mediaProjection) SEBELUM memakai token,
 * 2) registerCallback() SEBELUM createVirtualDisplay(),
 * 3) token consent hanya berlaku 1 sesi -> selalu minta intent baru.
 */
class ScreenRecordService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var engine: RecorderEngine? = null
    private var outputFile: File? = null
    private var outputName: String? = null
    private var audioOverride: String? = null
    private var activeAudioSource: String? = null

    private lateinit var facecam: FacecamController
    private lateinit var shield: PrivacyShieldController
    private var shieldOn = false

    @Volatile private var stopping = false
    @Volatile private var countdownCancelled = false
    private var countdownView: TextView? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Direvokasi sistem/user (mis. dari notifikasi sistem Android 14+).
            if (!stopping) stopRecording(fromProjection = true)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (RecorderState.isRecording) {
                updateNotification()
                mainHandler.postDelayed(this, 1_000)
            }
        }
    }

    private val sensitiveWatcher = object : Runnable {
        override fun run() {
            checkSensitiveForeground()
            if (RecorderState.isRecording) mainHandler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("rekamin-worker").apply { start() }
        workerHandler = Handler(workerThread.looper)
        facecam = FacecamController(this) { reason ->
            val msg = if (reason == "camera") R.string.facecam_failed else R.string.overlay_needed
            mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
        shield = PrivacyShieldController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                if (RecorderState.state == RecorderState.State.COUNTDOWN) {
                    countdownCancelled = true
                    hideCountdown()
                    RecorderState.set(RecorderState.State.IDLE)
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    stopRecording()
                }
            }
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_TOGGLE_FACECAM -> toggleFacecam()
            ACTION_TOGGLE_SHIELD -> toggleShield()
            else -> {
                // Perintah tak dikenal: jangan biarkan service zombie.
                if (!RecorderState.isRecording) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // -------------------------------------------------------------- start

    private fun handleStart(intent: Intent) {
        if (RecorderState.isRecording || RecorderState.state == RecorderState.State.COUNTDOWN) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = dataExtra(intent)
        audioOverride = intent.getStringExtra(EXTRA_AUDIO_OVERRIDE)
        if (resultCode == 0 || data == null) {
            stopSelf()
            return
        }
        stopping = false
        countdownCancelled = false
        RecorderState.set(RecorderState.State.COUNTDOWN)
        // WAJIB foreground lebih dulu sebelum token dipakai.
        startForegroundNow(buildNotification())
        maybeStartBubble()

        val seconds = Prefs.countdownSec
        if (seconds <= 0) {
            executor.execute { beginRecord(resultCode, data) }
        } else {
            runCountdown(seconds, resultCode, data)
        }
    }

    private fun runCountdown(remain: Int, resultCode: Int, data: Intent) {
        if (countdownCancelled) return
        if (remain <= 0) {
            hideCountdown()
            executor.execute { beginRecord(resultCode, data) }
            return
        }
        showCountdown(remain)
        mainHandler.postDelayed({ runCountdown(remain - 1, resultCode, data) }, 1_000)
    }

    private fun beginRecord(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection null")
            projection = proj
            // WAJIB sebelum createVirtualDisplay (Android 14+).
            proj.registerCallback(projectionCallback, workerHandler)

            val display = Util.realDisplaySize(this)
            val capture = Util.scaledCaptureSize(display, Prefs.resolution)
            val dpi = resources.displayMetrics.densityDpi
            val fps = Prefs.fps
            val bitrate = if (Prefs.bitrateMbps > 0) {
                Prefs.bitrateMbps * 1_000_000
            } else {
                Util.autoBitrate(capture.width, capture.height, fps)
            }

            val name = OutputStore.videoDisplayName()
            outputName = name
            val file = OutputStore.newTempVideoFile(this, name)
            outputFile = file

            val audio = audioOverride ?: Prefs.audioSource
            activeAudioSource = audio
            val wantInternal = audio == Prefs.AUDIO_INTERNAL || audio == Prefs.AUDIO_MIX
            // Mic dengan efek podcast aktif wajib lewat jalur codec (PCM bisa diproses).
            val wantMicCodec = audio == Prefs.AUDIO_MIC && Prefs.anyAudioFx
            var eng: RecorderEngine
            var surface: android.view.Surface
            if ((wantInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) || wantMicCodec) {
                val codecEngine = CodecRecorderEngine(
                    proj, file, capture.width, capture.height, fps, bitrate,
                    includeInternal = wantInternal,
                    includeMic = audio == Prefs.AUDIO_MIX || audio == Prefs.AUDIO_MIC,
                    fxNoiseSystem = Prefs.fxNoiseSystem,
                    dsp = if (Prefs.fxGate || Prefs.fxCompressor) {
                        MicDsp(44_100, Prefs.fxGate, Prefs.fxCompressor, Prefs.fxGainDb)
                    } else {
                        null
                    }
                )
                try {
                    surface = codecEngine.start()
                    eng = codecEngine
                } catch (e: Exception) {
                    // Fallback aman: engine MediaRecorder.
                    try {
                        codecEngine.stop()
                    } catch (ignored: Exception) {
                    }
                    toast(
                        if (wantInternal) R.string.internal_audio_fallback
                        else R.string.fx_fallback
                    )
                    val fallback = MediaRecorderEngine(
                        this, file, capture.width, capture.height, fps, bitrate,
                        useMic = audio == Prefs.AUDIO_MIX || audio == Prefs.AUDIO_MIC
                    )
                    surface = fallback.start()
                    eng = fallback
                }
            } else {
                if (wantInternal) toast(R.string.internal_audio_needs_android10)
                val mr = MediaRecorderEngine(
                    this, file, capture.width, capture.height, fps, bitrate,
                    // Internal di Android <10 -> fallback mic.
                    useMic = audio != Prefs.AUDIO_NONE
                )
                surface = mr.start()
                eng = mr
            }
            engine = eng
            virtualDisplay = proj.createVirtualDisplay(
                "RekaminAja", capture.width, capture.height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, workerHandler
            )

            RecorderState.set(RecorderState.State.RECORDING)
            updateNotification()
            mainHandler.post(ticker)
            mainHandler.post(sensitiveWatcher)
            mainHandler.post {
                if (Prefs.facecamEnabled && hasPermission(android.Manifest.permission.CAMERA)) {
                    facecam.show()
                    RecorderState.facecamVisible = facecam.isShowing
                }
                if (Prefs.censorEnabled) {
                    shield.showZones(Prefs.censorZones)
                    shieldOn = true
                }
            }
        } catch (e: Exception) {
            toast(R.string.error_start_failed)
            cleanupAfterFailedStart()
        }
    }

    private fun cleanupAfterFailedStart() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null
        try {
            engine?.stop()
        } catch (e: Exception) {
        }
        engine = null
        releaseProjection()
        RecorderState.set(RecorderState.State.IDLE)
        stopForegroundCompat()
        stopSelf()
    }

    // -------------------------------------------------------------- stop/pause

    private fun stopRecording(fromProjection: Boolean = false) {
        if (stopping) return
        stopping = true
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(sensitiveWatcher)
        mainHandler.post {
            shield.hideAll()
            shieldOn = false
            facecam.hide()
        }
        executor.execute {
            try {
                virtualDisplay?.release()
            } catch (e: Exception) {
            }
            virtualDisplay = null
            val ok = try {
                engine?.stop() == true
            } catch (e: Exception) {
                false
            }
            engine = null
            if (!fromProjection) releaseProjection() else releaseProjectionCallbacksOnly()

            val file = outputFile
            val name = outputName
            var uri: android.net.Uri? = null
            if (ok && file != null && name != null) {
                uri = OutputStore.publishVideo(this, file, name)
                SubtitleStore.rememberAudioSource(
                    this,
                    name,
                    activeAudioSource ?: (audioOverride ?: Prefs.audioSource)
                )
            }
            val savedUri = uri
            RecorderState.set(RecorderState.State.IDLE)
            mainHandler.post {
                if (ok) {
                    Toast.makeText(this, R.string.recording_saved, Toast.LENGTH_SHORT).show()
                    sendDoneNotification(savedUri)
                } else {
                    Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_LONG).show()
                }
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun releaseProjection() {
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
        }
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
    }

    private fun releaseProjectionCallbacksOnly() {
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
        }
        projection = null
    }

    private fun togglePause() {
        when (RecorderState.state) {
            RecorderState.State.RECORDING -> {
                val e = engine
                executor.execute { e?.pause() }
                RecorderState.autoPausedBySensitiveApp = false
                RecorderState.set(RecorderState.State.PAUSED)
                updateNotification()
            }
            RecorderState.State.PAUSED -> {
                val e = engine
                executor.execute { e?.resume() }
                RecorderState.autoPausedBySensitiveApp = false
                RecorderState.set(RecorderState.State.RECORDING)
                updateNotification()
            }
            else -> Unit
        }
    }

    private fun toggleFacecam() {
        mainHandler.post {
            if (facecam.isShowing) {
                facecam.hide()
                RecorderState.facecamVisible = false
            } else {
                if (!hasPermission(android.Manifest.permission.CAMERA)) {
                    Toast.makeText(this, R.string.facecam_permission, Toast.LENGTH_SHORT).show()
                    return@post
                }
                facecam.show()
                RecorderState.facecamVisible = facecam.isShowing
            }
        }
    }

    private fun toggleShield() {
        mainHandler.post {
            if (shieldOn) {
                shield.hideZones()
                shieldOn = false
            } else {
                shield.showZones(Prefs.censorZones)
                shieldOn = true
            }
        }
    }

    // -------------------------------------------------------------- auto-pause sensitif

    private fun checkSensitiveForeground() {
        if (!Prefs.autoPauseSensitive) return
        if (Prefs.sensitivePackages.isEmpty()) return
        if (!Util.hasUsageAccess(this)) return
        val pkg = foregroundPackage() ?: return
        if (pkg == packageName) return
        val sensitive = Prefs.sensitivePackages.contains(pkg)
        if (sensitive && RecorderState.state == RecorderState.State.RECORDING) {
            val e = engine
            executor.execute { e?.pause() }
            RecorderState.autoPausedBySensitiveApp = true
            RecorderState.set(RecorderState.State.PAUSED)
            shield.showFullShield(getString(R.string.shield_active_text))
            updateNotification()
        } else if (!sensitive && RecorderState.autoPausedBySensitiveApp &&
            RecorderState.state == RecorderState.State.PAUSED
        ) {
            val e = engine
            executor.execute { e?.resume() }
            RecorderState.autoPausedBySensitiveApp = false
            RecorderState.set(RecorderState.State.RECORDING)
            shield.hideFullShield()
            updateNotification()
        }
    }

    private fun foregroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats: List<UsageStats> = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, now - 15_000, now
        ) ?: return null
        var best: UsageStats? = null
        for (s in stats) {
            if (best == null || s.lastTimeUsed > best.lastTimeUsed) best = s
        }
        return best?.packageName
    }

    // -------------------------------------------------------------- notifikasi

    private fun startForegroundNow(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val audio = audioOverride ?: Prefs.audioSource
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (audio != Prefs.AUDIO_NONE && hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (Prefs.facecamEnabled && hasPermission(android.Manifest.permission.CAMERA)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val state = RecorderState.state
        val title = when (state) {
            RecorderState.State.PAUSED -> getString(R.string.notif_paused)
            RecorderState.State.COUNTDOWN -> getString(R.string.notif_preparing)
            else -> getString(R.string.notif_recording)
        }
        val text = if (RecorderState.isRecording) {
            Util.formatDuration(RecorderState.elapsedMs())
        } else {
            getString(R.string.app_name)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pausedNow = state == RecorderState.State.PAUSED
        val pauseLabel = if (pausedNow) getString(R.string.resume) else getString(R.string.pause)
        val pauseIcon = if (pausedNow) R.drawable.ic_notif_play else R.drawable.ic_notif_pause
        return NotificationCompat.Builder(this, RekaminApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(pauseIcon, pauseLabel, pauseIntent)
            .addAction(R.drawable.ic_notif_stop, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun sendDoneNotification(uri: android.net.Uri?) {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, RekaminApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(getString(R.string.recording_saved))
            .setContentText(getString(R.string.tap_to_watch))
            .setAutoCancel(true)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 3, view,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        nm.notify(DONE_ID, builder.build())
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // -------------------------------------------------------------- helpers

    private fun maybeStartBubble() {
        if (!Prefs.floatingBubble || !Util.canOverlay(this)) return
        try {
            startService(Intent(this, FloatingBubbleService::class.java))
        } catch (e: Exception) {
            // Batasan background service di sebagian OEM — bubble bisa dinyalakan manual.
        }
    }

    private fun showCountdown(n: Int) {
        mainHandler.post {
            if (!Util.canOverlay(this)) return@post
            if (countdownView == null) {
                val tv = TextView(this).apply {
                    setTextColor(Color.WHITE)
                    textSize = 84f
                    setShadowLayer(16f, 0f, 0f, Color.BLACK)
                    gravity = Gravity.CENTER
                }
                val lp = OverlayUtil.params(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    gravity = Gravity.CENTER, touchable = false
                )
                try {
                    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv, lp)
                    countdownView = tv
                } catch (e: Exception) {
                }
            }
            countdownView?.text = n.toString()
        }
    }

    private fun hideCountdown() {
        mainHandler.post {
            countdownView?.let {
                try {
                    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
                } catch (e: Exception) {
                }
            }
            countdownView = null
        }
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) {
        mainHandler.post { Toast.makeText(this, resId, Toast.LENGTH_LONG).show() }
    }

    private fun dataExtra(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        try {
            engine?.stop()
        } catch (e: Exception) {
        }
        releaseProjection()
        facecam.hide()
        shield.hideAll()
        hideCountdown()
        executor.shutdownNow()
        try {
            workerThread.quitSafely()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "id.rekaminaja.app.recorder.START"
        const val ACTION_STOP = "id.rekaminaja.app.recorder.STOP"
        const val ACTION_TOGGLE_PAUSE = "id.rekaminaja.app.recorder.TOGGLE_PAUSE"
        const val ACTION_TOGGLE_FACECAM = "id.rekaminaja.app.recorder.TOGGLE_FACECAM"
        const val ACTION_TOGGLE_SHIELD = "id.rekaminaja.app.recorder.TOGGLE_SHIELD"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUDIO_OVERRIDE = "audio_override"
        private const val NOTIF_ID = 1001
        private const val DONE_ID = 2001
    }
}
