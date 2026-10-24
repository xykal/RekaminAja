package id.rekaminaja.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import id.rekaminaja.app.ConsentActivity
import id.rekaminaja.app.recorder.RecorderState
import id.rekaminaja.app.recorder.ScreenRecordService

/** Quick Settings Tile: ketuk untuk mulai/berhenti merekam dari panel notifikasi. */
class RecordTileService : TileService() {

    private val listener: (RecorderState.State) -> Unit = { updateTile() }

    override fun onStartListening() {
        super.onStartListening()
        RecorderState.addListener(listener)
        updateTile()
    }

    override fun onStopListening() {
        RecorderState.removeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (RecorderState.isRecording) {
            try {
                startService(
                    Intent(this, ScreenRecordService::class.java)
                        .setAction(ScreenRecordService.ACTION_STOP)
                )
            } catch (e: Exception) {
            }
        } else {
            val intent = Intent(this, ConsentActivity::class.java)
                .putExtra(ConsentActivity.EXTRA_MODE, ConsentActivity.MODE_RECORD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        updateTile()
    }

    @SuppressLint("WrongConstant")
    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (RecorderState.isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
