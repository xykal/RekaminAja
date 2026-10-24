package id.rekaminaja.app.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.view.Surface
import java.io.File

/**
 * Engine paling andal: MediaRecorder bawaan Android.
 * Dipakai untuk mode audio Tanpa Suara & Mikrofon (pause/resume native sejak API 24).
 */
class MediaRecorderEngine(
    private val context: Context,
    override val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val useMic: Boolean
) : RecorderEngine {

    override val supportsPause: Boolean = true

    private var recorder: MediaRecorder? = null

    override fun start(): Surface {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        recorder = mr
        if (useMic) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setOutputFile(outputFile.absolutePath)
        mr.setVideoSize(width, height)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (useMic) {
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(128_000)
        }
        mr.setVideoEncodingBitRate(bitrate)
        mr.setVideoFrameRate(fps)
        mr.prepare()
        val surface = mr.surface
        mr.start()
        return surface
    }

    override fun pause() {
        try {
            recorder?.pause()
        } catch (e: Exception) {
            // Abaikan — state UI tetap konsisten lewat RecorderState.
        }
    }

    override fun resume() {
        try {
            recorder?.resume()
        } catch (e: Exception) {
            // Abaikan.
        }
    }

    override fun stop(): Boolean {
        val mr = recorder ?: return false
        recorder = null
        var ok = true
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            // Rekaman terlalu pendek / tidak ada frame valid.
            ok = false
        } finally {
            try {
                mr.reset()
            } catch (e: Exception) {
            }
            try {
                mr.release()
            } catch (e: Exception) {
            }
        }
        if (!ok || !outputFile.exists() || outputFile.length() == 0L) {
            outputFile.delete()
            return false
        }
        return true
    }
}
