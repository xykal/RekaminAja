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
import id.rekaminaja.app.overlay.ScreenshotBubbleService
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
    private var screenshotCapturer: ScreenshotCapturer? = null
    @Volatile private var screenshotInProgress = false
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
            countdownCancelled = true
            RecorderState.projectionReady = false
            if (RecorderState.isRecording || RecorderState.state == RecorderState.State.COUNTDOWN) {
                if (!stopping) stopRecording(fromProjection = true)
            } else {
                // Sesi siaga dicabut: bersihkan tanpa menyentuh file rekaman.
                endSession(fromRevocation = true)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (RecorderState.isRecording) {
                val limitMin = Prefs.autoStopMinutes
                if (limitMin > 0 && RecorderState.elapsedMs() >= limitMin * 60_000L) {
                    stopRecording()
                    return
                }
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
            ACTION_SCREENSHOT -> handleScreenshot(intent)
            ACTION_END_SESSION -> {
                // Bola disembunyikan / tombol Akhiri sesi: matikan izin tangkap
                // yang disimpan. Saat sedang merekam, sesi berakhir otomatis
                // begitu rekaman berhenti (lihat stopRecording).
                if (!RecorderState.isRecording &&
                    RecorderState.state != RecorderState.State.COUNTDOWN
                ) {
                    endSession()
                }
            }
            ACTION_STOP -> {
                if (RecorderState.state == RecorderState.State.COUNTDOWN) {
                    countdownCancelled = true
                    hideCountdown()
                    returnToReadyOrStop()
                } else if (RecorderState.isRecording) {
                    stopRecording()
                } else {
                    endSession()
                }
            }
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_TOGGLE_FACECAM -> toggleFacecam()
            ACTION_TOGGLE_SHIELD -> toggleShield()
            ACTION_MIC_VOL_UP -> adjustMicVolume(+10)
            ACTION_MIC_VOL_DOWN -> adjustMicVolume(-10)
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
        val useStored = (resultCode == 0 || data == null) &&
            projection != null && RecorderState.projectionReady
        if (!useStored && (resultCode == 0 || data == null)) {
            // Tidak ada token baru dan tidak ada sesi aktif: mulai harus
            // lewat ConsentActivity. Jangan tinggalkan service zombie.
            if (!RecorderState.projectionReady) stopSelf()
            return
        }
        stopping = false
        countdownCancelled = false
        RecorderState.set(RecorderState.State.COUNTDOWN)
        // WAJIB foreground lebih dulu sebelum token dipakai. Pada sesi yang
        // dipakai ulang, ini juga menyegarkan tipe foreground (mic/camera)
        // mengikuti pengaturan audio sesi rekaman kali ini.
        startForegroundNow(buildNotification())
        maybeStartBubble()

        val seconds = Prefs.countdownSec
        if (seconds <= 0) {
            if (useStored) {
                executor.execute { beginRecordWithStored() }
            } else {
                executor.execute { beginRecord(resultCode, data!!) }
            }
        } else {
            runCountdown(seconds, if (useStored) null else resultCode to data)
        }
    }

    // ---------------------------------------------------------- screenshot

    private fun handleScreenshot(intent: Intent) {
        if (RecorderState.isRecording || RecorderState.state == RecorderState.State.COUNTDOWN) {
            toast(R.string.screenshot_while_recording)
            return
        }
        if (screenshotInProgress) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = dataExtra(intent)
        val useStored = (resultCode == 0 || data == null) &&
            projection != null && RecorderState.projectionReady
        if (!useStored && (resultCode == 0 || data == null)) {
            toast(R.string.screenshot_failed)
            if (!RecorderState.projectionReady) stopSelf()
            return
        }

        screenshotInProgress = true
        // Token segar hanya boleh dipakai sesudah service foreground.
        startForegroundNow(buildNotification())
        executor.execute {
            if (useStored) {
                beginScreenshotWithStored()
            } else {
                beginScreenshotFresh(resultCode, data!!)
            }
        }
    }

    private fun beginScreenshotWithStored() {
        val proj = projection
        if (proj == null || !RecorderState.projectionReady) {
            screenshotInProgress = false
            toast(R.string.screenshot_failed)
            returnToReadyOrStop()
            return
        }
        startScreenshotCapture(proj)
    }

    private fun beginScreenshotFresh(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection null")
            if (projection != null && projection !== proj) releaseProjection()
            projection = proj
            RecorderState.projectionReady = true
            proj.registerCallback(projectionCallback, workerHandler)
            startScreenshotCapture(proj)
        } catch (e: Exception) {
            screenshotInProgress = false
            toast(R.string.screenshot_failed)
            cleanupAfterFailedStart()
        }
    }

    private fun startScreenshotCapture(proj: MediaProjection) {
        val capturer = ScreenshotCapturer(this, proj, workerHandler) {
            screenshotCapturer = null
            screenshotInProgress = false
            mainHandler.post {
                // Pertahankan satu izin tangkapan selama salah satu bola
                // masih tampil; screenshot/rekaman berikutnya jadi instan.
                if (FloatingBubbleService.running || ScreenshotBubbleService.running) {
                    returnToReadyOrStop()
                } else {
                    endSession()
                }
            }
        }
        screenshotCapturer = capturer
        capturer.start()
    }

    private fun runCountdown(remain: Int, fresh: Pair<Int, Intent?>?) {
        if (countdownCancelled) return
        if (remain <= 0) {
            hideCountdown()
            if (fresh == null) {
                executor.execute { beginRecordWithStored() }
            } else {
                val code = fresh.first
                val data = fresh.second
                if (data == null) {
                    returnToReadyOrStop()
                } else {
                    executor.execute { beginRecord(code, data) }
                }
            }
            return
        }
        showCountdown(remain)
        mainHandler.postDelayed({ runCountdown(remain - 1, fresh) }, 1_000)
    }

    /** Mulai rekam memakai sesi MediaProjection yang sudah diberikan izinnya. */
    private fun beginRecordWithStored() {
        val proj = projection
        if (proj == null || !RecorderState.projectionReady) {
            returnToReadyOrStop()
            return
        }
        beginRecordInternal(proj, registerCallback = false)
    }

    private fun beginRecord(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection null")
            // Token segar menggantikan sesi lama: lepas yang lama dulu agar
            // tidak ada dua MediaProjection hidup bersamaan.
            if (projection != null && projection !== proj) releaseProjection()
            projection = proj
            RecorderState.projectionReady = true
            beginRecordInternal(proj, registerCallback = true)
        } catch (e: Exception) {
            toast(R.string.error_start_failed)
            cleanupAfterFailedStart()
        }
    }

    private fun beginRecordInternal(proj: MediaProjection, registerCallback: Boolean) {
        try {
            // WAJIB sebelum createVirtualDisplay (Android 14+). Sesi pakai
            // ulang sudah terdaftar sejak pertama dibuat.
            if (registerCallback) {
                proj.registerCallback(projectionCallback, workerHandler)
            }

            // Target kualitas selalu divalidasi terhadap refresh layar dan
            // encoder hardware. UHD/120 FPS diturunkan aman bila HP tidak kuat.
            val plan = CaptureCapabilities.plan(this)
            val capture = plan.size
            val dpi = resources.displayMetrics.densityDpi
            val fps = plan.fps
            val bitrate = plan.bitrate
            if (plan.adjusted) {
                toastText(
                    getString(
                        R.string.quality_adjusted_format,
                        capture.width, capture.height, fps
                    )
                )
            }

            val name = OutputStore.videoDisplayName()
            outputName = name
            val file = OutputStore.newTempVideoFile(this, name)
            outputFile = file

            val audio = audioOverride ?: Prefs.audioSource
            activeAudioSource = audio
            // Sinkronkan Deck Efek Suara sebelum engine dibuat. MicDsp membaca
            // VoiceFxState agar preset/volume bisa diganti live dari floating.
            VoiceFxState.syncFromPrefs()
            val wantInternal = audio == Prefs.AUDIO_INTERNAL || audio == Prefs.AUDIO_MIX
            val internalSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            if (wantInternal && !internalSupported) {
                toast(R.string.internal_audio_needs_android10)
            }
            // v1.4: semua mode yang mengandung mic memakai jalur codec yang
            // sama, termasuk Normal 100%. Dulu Normal mulai dari
            // MediaRecorder, sehingga preset yang dipilih di tengah rekaman
            // kadang live dan kadang baru berlaku rekaman berikutnya.
            val wantMic = audio == Prefs.AUDIO_MIX || audio == Prefs.AUDIO_MIC ||
                (wantInternal && !internalSupported)
            val useCodec = (wantInternal && internalSupported) || wantMic
            var eng: RecorderEngine
            var surface: android.view.Surface
            if (useCodec) {
                val codecEngine = CodecRecorderEngine(
                    proj, file, capture.width, capture.height, fps, bitrate,
                    includeInternal = wantInternal && internalSupported,
                    includeMic = wantMic,
                    fxNoiseSystem = Prefs.fxNoiseSystem,
                    // Selalu pasang DSP bila ada mic di jalur codec, supaya
                    // volume mic + preset bisa diubah live (Normal = nyaris
                    // lolos apa adanya).
                    dsp = if (wantMic) {
                        MicDsp(44_100, Prefs.fxGate, Prefs.fxCompressor, Prefs.fxGainDb)
                    } else {
                        null
                    },
                    videoMime = plan.mime
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
                        if (wantMic) R.string.voice_fx_fallback
                        else R.string.internal_audio_fallback
                    )
                    val fallback = MediaRecorderEngine(
                        this, file, capture.width, capture.height, fps, bitrate,
                        useMic = wantMic,
                        videoMime = plan.mime
                    )
                    surface = fallback.start()
                    eng = fallback
                }
            } else {
                // Hanya mode tanpa suara yang sampai ke jalur sederhana ini.
                val mr = MediaRecorderEngine(
                    this, file, capture.width, capture.height, fps, bitrate,
                    useMic = false,
                    videoMime = plan.mime
                )
                surface = mr.start()
                eng = mr
            }
            engine = eng
            RecorderState.fxLive = eng is CodecRecorderEngine && wantMic
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

    /**
     * Kembali ke keadaan "siap merekam" bila sesi proyeksi masih hidup
     * (notifikasi siaga + izin tetap aktif); bila tidak, matikan service.
     */
    private fun returnToReadyOrStop() {
        RecorderState.set(RecorderState.State.IDLE)
        if (projection != null && RecorderState.projectionReady) {
            try {
                startForegroundNow(buildNotification())
            } catch (e: Exception) {
                endSession()
            }
        } else {
            stopForegroundCompat()
            stopSelf()
        }
    }

    /** Akhiri sesi tangkapan layar sepenuhnya (izin disimpan dilepas). */
    private fun endSession(fromRevocation: Boolean = false) {
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(sensitiveWatcher)
        hideCountdown()
        mainHandler.post {
            shield.hideAll()
            shieldOn = false
            facecam.hide()
        }
        if (fromRevocation) releaseProjectionCallbacksOnly() else releaseProjection()
        RecorderState.projectionReady = false
        RecorderState.set(RecorderState.State.IDLE)
        stopForegroundCompat()
        stopSelf()
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
            // Sesi persisten: selama bola melayang masih tampil, izin tangkap
            // disimpan agar rekaman berikutnya mulai tanpa dialog izin lagi.
            val keepSession = !fromProjection && projection != null &&
                RecorderState.projectionReady &&
                (FloatingBubbleService.running || ScreenshotBubbleService.running)
            if (!keepSession) {
                if (!fromProjection) releaseProjection() else releaseProjectionCallbacksOnly()
                RecorderState.projectionReady = false
            }

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
                if (keepSession) {
                    // Tetap foreground dalam mode siap; rekaman berikutnya dari
                    // bola mulai seketika tanpa masuk app / izin ulang.
                    returnToReadyOrStop()
                } else {
                    stopForegroundCompat()
                    stopSelf()
                }
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
        RecorderState.projectionReady = false
    }

    private fun releaseProjectionCallbacksOnly() {
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
        }
        projection = null
        RecorderState.projectionReady = false
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

    /** Volume mic dari tombol notifikasi: tersimpan + berlaku live via DSP. */
    private fun adjustMicVolume(deltaPercent: Int) {
        VoiceFxState.applyMicVolumePercent(Prefs.micVolume + deltaPercent)
        if (RecorderState.isRecording) {
            updateNotification()
        } else if (RecorderState.state != RecorderState.State.COUNTDOWN) {
            // Service dibangunkan hanya untuk tombol ini; jangan jadi zombie.
            stopSelf()
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
        // Mode siaga sesi persisten: izin sudah aktif, belum merekam.
        val ready = state == RecorderState.State.IDLE && RecorderState.projectionReady
        val title = when {
            ready -> getString(R.string.app_name)
            state == RecorderState.State.PAUSED -> getString(R.string.notif_paused)
            state == RecorderState.State.COUNTDOWN -> getString(R.string.notif_preparing)
            else -> getString(R.string.notif_recording)
        }
        val text = when {
            ready -> getString(R.string.notif_ready_text)
            RecorderState.isRecording -> Util.formatDuration(RecorderState.elapsedMs())
            else -> getString(R.string.app_name)
        }
        val customTimerText = if (ready) {
            getString(R.string.notif_ready)
        } else {
            text
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
        // Di mode siaga, tombol utama menjadi Mulai dan tombol stop menjadi
        // Akhiri sesi (melepas izin tangkap yang disimpan).
        val startIntent = PendingIntent.getService(
            this, 6,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endSessionIntent = PendingIntent.getService(
            this, 7,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_END_SESSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val primaryIntent = if (ready) startIntent else pauseIntent
        val secondaryIntent = if (ready) endSessionIntent else stopIntent
        val pauseLabel = when {
            ready -> getString(R.string.start_recording)
            pausedNow -> getString(R.string.resume)
            else -> getString(R.string.pause)
        }
        val stopLabel = if (ready) getString(R.string.end_session) else getString(R.string.stop)
        val pauseIcon = if (ready || pausedNow) {
            R.drawable.ic_notif_play
        } else {
            R.drawable.ic_notif_pause
        }
        val volDownIntent = PendingIntent.getService(
            this, 4,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_MIC_VOL_DOWN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val volUpIntent = PendingIntent.getService(
            this, 5,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_MIC_VOL_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Kartu notifikasi custom (Light Clean): status + timer, jeda/stop,
        // dan pengatur volume mic langsung dari notifikasi yang diperluas.
        fun fillCustom(layoutRes: Int): android.widget.RemoteViews {
            val v = android.widget.RemoteViews(packageName, layoutRes)
            v.setTextViewText(R.id.notif_title, title)
            v.setTextViewText(R.id.notif_timer, customTimerText)
            v.setImageViewResource(R.id.notif_pause, pauseIcon)
            v.setContentDescription(R.id.notif_pause, pauseLabel)
            v.setContentDescription(R.id.notif_stop, stopLabel)
            v.setOnClickPendingIntent(R.id.notif_pause, primaryIntent)
            v.setOnClickPendingIntent(R.id.notif_stop, secondaryIntent)
            return v
        }
        val collapsed = fillCustom(R.layout.notification_recording)
        val big = fillCustom(R.layout.notification_recording_big).apply {
            setTextViewText(
                R.id.notif_mic_percent,
                getString(R.string.mic_volume_format, Prefs.micVolume)
            )
            setOnClickPendingIntent(R.id.notif_vol_down, volDownIntent)
            setOnClickPendingIntent(R.id.notif_vol_up, volUpIntent)
        }

        return NotificationCompat.Builder(this, RekaminApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(big)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            // Aksi teks tetap disediakan untuk permukaan lain (lock screen dll).
            .addAction(pauseIcon, pauseLabel, primaryIntent)
            .addAction(R.drawable.ic_notif_stop, stopLabel, secondaryIntent)
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

    private fun toastText(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
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
        screenshotCapturer?.cancel()
        screenshotCapturer = null
        screenshotInProgress = false
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
        const val ACTION_SCREENSHOT = "id.rekaminaja.app.recorder.SCREENSHOT"
        const val ACTION_STOP = "id.rekaminaja.app.recorder.STOP"
        const val ACTION_END_SESSION = "id.rekaminaja.app.recorder.END_SESSION"
        const val ACTION_TOGGLE_PAUSE = "id.rekaminaja.app.recorder.TOGGLE_PAUSE"
        const val ACTION_TOGGLE_FACECAM = "id.rekaminaja.app.recorder.TOGGLE_FACECAM"
        const val ACTION_TOGGLE_SHIELD = "id.rekaminaja.app.recorder.TOGGLE_SHIELD"
        const val ACTION_MIC_VOL_UP = "id.rekaminaja.app.recorder.MIC_VOL_UP"
        const val ACTION_MIC_VOL_DOWN = "id.rekaminaja.app.recorder.MIC_VOL_DOWN"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUDIO_OVERRIDE = "audio_override"
        private const val NOTIF_ID = 1001
        private const val DONE_ID = 2001
    }
}
