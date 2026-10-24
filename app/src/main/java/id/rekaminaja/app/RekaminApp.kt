package id.rekaminaja.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import id.rekaminaja.app.data.Prefs

class RekaminApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // v1.1: tema Light Clean custom — Material You dynamic color dimatikan
        // agar tampilan konsisten dan tidak terlihat seperti UI bawaan.
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val recording = NotificationChannel(
            CHANNEL_RECORDING,
            getString(R.string.channel_recording),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_recording_desc) }
        val done = NotificationChannel(
            CHANNEL_DONE,
            getString(R.string.channel_done),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(recording)
        nm.createNotificationChannel(done)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_DONE = "done"
    }
}
