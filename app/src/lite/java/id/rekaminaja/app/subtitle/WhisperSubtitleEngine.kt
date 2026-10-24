package id.rekaminaja.app.subtitle

import android.content.Context
import id.rekaminaja.app.R
import java.io.File

/**
 * Stub edisi Universal Lite.
 *
 * Edisi ini sengaja tidak membawa library native Whisper maupun model 57 MB
 * agar tetap kecil dan dapat dipasang di perangkat 32-bit/x86. Seluruh fitur
 * perekam tetap sama; hanya Auto Subtitle yang menunjuk ke edisi Full ARM64.
 */
object WhisperSubtitleEngine {

    fun isSupportedDevice(): Boolean = false

    fun unsupportedMessageRes(): Int = R.string.subtitle_unsupported_edition

    fun ensureModel(
        context: Context,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): File {
        throw SubtitleException(unsupportedMessageRes())
    }

    fun transcribe(
        context: Context,
        modelFile: File,
        chunks: List<SubtitleAudioExtractor.Chunk>,
        isCancelled: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<SubtitleCue> {
        throw SubtitleException(unsupportedMessageRes())
    }
}
