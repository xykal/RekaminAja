package id.rekaminaja.app.recorder

import android.view.Surface
import java.io.File

/** Kontrak mesin perekam. start() mengembalikan Surface untuk VirtualDisplay. */
interface RecorderEngine {
    /** File keluaran MP4 (sementara) hasil rekaman. */
    val outputFile: File

    /** True jika jeda didukung penuh oleh engine ini. */
    val supportsPause: Boolean

    @Throws(Exception::class)
    fun start(): Surface

    fun pause()
    fun resume()

    /** @return true bila MP4 valid & layak diterbitkan. */
    fun stop(): Boolean
}
