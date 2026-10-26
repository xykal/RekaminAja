package id.rekaminaja.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.recorder.RecorderState
import id.rekaminaja.app.recorder.ScreenRecordService
import id.rekaminaja.app.recorder.ScreenshotService

/**
 * Activity transparan satu pintu untuk izin MediaProjection.
 * Dipakai MainActivity, bola melayang, Quick Settings Tile, dan shortcut launcher.
 * Token consent Android 14+ hanya sekali pakai, jadi selalu lewat sini.
 */
class ConsentActivity : AppCompatActivity() {

    private var audioOverride: String? = null
    private var launched = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Tetap boleh merekam, tapi tanpa suara.
            audioOverride = Prefs.AUDIO_NONE
            Toast.makeText(this, R.string.mic_denied_silent, Toast.LENGTH_LONG).show()
        }
        launchProjection()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            if (mode() == MODE_RECORD) {
                if (RecorderState.isRecording) {
                    finish()
                    return@registerForActivityResult
                }
                val intent = android.content.Intent(this, ScreenRecordService::class.java)
                    .setAction(ScreenRecordService.ACTION_START)
                    .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenRecordService.EXTRA_DATA, data)
                    .putExtra(ScreenRecordService.EXTRA_AUDIO_OVERRIDE, audioOverride)
                ContextCompat.startForegroundService(this, intent)
            } else {
                val intent = android.content.Intent(this, ScreenshotService::class.java)
                    .putExtra(ScreenshotService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenshotService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
            }
        } else {
            Toast.makeText(this, R.string.capture_permission_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun mode(): String = intent.getStringExtra(EXTRA_MODE) ?: MODE_RECORD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (launched) return
        launched = true
        if (mode() == MODE_RECORD) {
            val needsAudio = Prefs.audioSource != Prefs.AUDIO_NONE
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (needsAudio && !granted) {
                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        launchProjection()
    }

    private fun launchProjection() {
        // Sesi tangkapan layar dari rekaman sebelumnya masih hidup (bola
        // masih tampil): mulai langsung, tanpa dialog izin Android lagi dan
        // tanpa membuka tampilan app. Sesi berakhir saat bola disembunyikan.
        if (mode() == MODE_RECORD && RecorderState.projectionReady &&
            !RecorderState.isRecording
        ) {
            val intent = android.content.Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_START)
                .putExtra(ScreenRecordService.EXTRA_AUDIO_OVERRIDE, audioOverride)
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.error_start_failed, Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            // Di Android 14+ intent ini menawarkan "satu app" atau "seluruh layar".
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_start_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_RECORD = "record"
        const val MODE_SCREENSHOT = "screenshot"
    }
}
