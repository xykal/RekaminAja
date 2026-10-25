package id.rekaminaja.app.subtitle

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import id.rekaminaja.app.R
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.recorder.RecorderState
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orkestrator Auto Subtitle.
 *
 * Didesain khusus suara mikrofon/manusia:
 * - rekaman yang diketahui internal-only atau tanpa audio langsung ditolak,
 * - pembuatan tidak boleh berjalan saat perekaman berlangsung,
 * - audio diekstrak per potongan dan ditranskrip sepenuhnya offline.
 */
object SubtitleGenerator {

    data class Success(
        val file: File,
        val cues: List<SubtitleCue>,
        val publicUri: Uri?
    )

    interface Listener {
        fun onProgress(percent: Int, stage: String)
        fun onSuccess(result: Success)
        fun onError(messageRes: Int)
        fun onCancelled()
    }

    class Handle internal constructor() {
        internal val cancelled = AtomicBoolean(false)
        fun cancel() {
            cancelled.set(true)
        }
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rekamin-subtitle")
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeHandle: Handle? = null

    fun isBusy(): Boolean = activeHandle != null

    fun generate(context: Context, uri: Uri, videoName: String, listener: Listener): Handle {
        val handle = Handle()
        val appContext = context.applicationContext
        synchronized(this) {
            if (activeHandle != null) {
                mainHandler.post { listener.onError(R.string.subtitle_busy) }
                return handle
            }
            activeHandle = handle
        }

        executor.execute {
            var workDir: File? = null
            try {
                if (RecorderState.isRecording ||
                    RecorderState.state == RecorderState.State.COUNTDOWN
                ) {
                    throw SubtitleException(R.string.subtitle_unavailable_recording)
                }

                val knownSource = SubtitleStore.audioSourceFor(appContext, videoName)
                if (knownSource == Prefs.AUDIO_INTERNAL || knownSource == Prefs.AUDIO_NONE) {
                    throw SubtitleException(R.string.subtitle_not_mic)
                }
                if (!WhisperSubtitleEngine.isSupportedDevice()) {
                    throw SubtitleException(WhisperSubtitleEngine.unsupportedMessageRes())
                }

                postProgress(listener, 3, appContext.getString(R.string.subtitle_stage_extract))
                // Bersihkan sisa folder kerja dari proses yang mati paksa agar
                // cache tidak menumpuk dari waktu ke waktu.
                try {
                    appContext.cacheDir.listFiles()?.forEach { f ->
                        if (f.isDirectory && f.name.startsWith("subtitle_work_")) {
                            f.deleteRecursively()
                        }
                    }
                } catch (e: Exception) {
                }
                workDir = File(appContext.cacheDir, "subtitle_work_${System.currentTimeMillis()}")
                val extraction = SubtitleAudioExtractor(appContext).extract(
                    uri = uri,
                    workDir = workDir,
                    isCancelled = { handle.cancelled.get() },
                    onProgress = { percent ->
                        postProgress(
                            listener,
                            5 + percent * 22 / 100,
                            appContext.getString(R.string.subtitle_stage_extract)
                        )
                    }
                )
                if (handle.cancelled.get()) throw SubtitleCancelledException()

                if (extraction.peak < 0.0025f ||
                    (extraction.voicedRatio < 0.003f && extraction.peak < 0.02f)
                ) {
                    throw SubtitleException(R.string.subtitle_no_voice)
                }

                postProgress(listener, 30, appContext.getString(R.string.subtitle_stage_prepare))
                val modelFile = WhisperSubtitleEngine.ensureModel(
                    context = appContext,
                    isCancelled = { handle.cancelled.get() },
                    onProgress = { percent ->
                        postProgress(
                            listener,
                            30 + percent * 8 / 100,
                            appContext.getString(R.string.subtitle_stage_prepare)
                        )
                    }
                )
                if (handle.cancelled.get()) throw SubtitleCancelledException()

                postProgress(listener, 39, appContext.getString(R.string.subtitle_stage_transcribe))
                val cues = WhisperSubtitleEngine.transcribe(
                    context = appContext,
                    modelFile = modelFile,
                    chunks = extraction.chunks,
                    isCancelled = { handle.cancelled.get() },
                    onProgress = { completed, total ->
                        val percent = if (total <= 0) {
                            94
                        } else {
                            39 + completed * 55 / total
                        }
                        postProgress(
                            listener,
                            percent,
                            appContext.getString(
                                R.string.subtitle_stage_transcribe_chunk,
                                completed.coerceAtLeast(1), total.coerceAtLeast(1)
                            )
                        )
                    }
                )
                if (cues.isEmpty()) {
                    throw SubtitleException(R.string.subtitle_no_voice)
                }
                if (handle.cancelled.get()) throw SubtitleCancelledException()

                postProgress(listener, 96, appContext.getString(R.string.subtitle_stage_save))
                val file = SubtitleStore.writeInternal(appContext, videoName, cues)
                val publicUri = SubtitleStore.publishPublic(appContext, file, videoName)
                postProgress(listener, 100, appContext.getString(R.string.subtitle_stage_save))
                mainHandler.post {
                    listener.onSuccess(Success(file, cues, publicUri))
                }
            } catch (e: SubtitleCancelledException) {
                mainHandler.post { listener.onCancelled() }
            } catch (e: SubtitleException) {
                if (handle.cancelled.get()) {
                    mainHandler.post { listener.onCancelled() }
                } else {
                    mainHandler.post { listener.onError(e.messageRes) }
                }
            } catch (e: Exception) {
                if (handle.cancelled.get()) {
                    mainHandler.post { listener.onCancelled() }
                } else {
                    mainHandler.post { listener.onError(R.string.subtitle_failed) }
                }
            } finally {
                synchronized(this) {
                    if (activeHandle === handle) activeHandle = null
                }
                try {
                    workDir?.deleteRecursively()
                } catch (e: Exception) {
                }
            }
        }
        return handle
    }

    private fun postProgress(listener: Listener, percent: Int, stage: String) {
        mainHandler.post {
            listener.onProgress(percent.coerceIn(0, 100), stage)
        }
    }
}
