package id.rekaminaja.app.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Potong video tanpa re-encode (stream copy via MediaExtractor -> MediaMuxer).
 * Cepat & lossless, tapi akurat di level keyframe untuk titik awal.
 */
object VideoTrimmer {

    fun trim(src: File, dest: File, startMs: Long, endMs: Long): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(src.absolutePath)
            val trackMap = HashMap<Int, Int>()
            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(extractor.getTrackFormat(i))
                }
            }
            if (trackMap.isEmpty()) return false
            val startUs = startMs * 1_000
            val endUs = endMs * 1_000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer.start()
            val buffer = ByteBuffer.allocate(1_048_576)
            val info = MediaCodec.BufferInfo()
            var basePts = -1L
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val pts = extractor.sampleTime
                if (pts >= endUs) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val outTrack = trackMap[track]
                if (outTrack != null) {
                    if (basePts < 0) basePts = pts
                    val outPts = (pts - basePts).coerceAtLeast(0)
                    val isSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(
                        0, size, outPts,
                        if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    )
                    buffer.position(0)
                    buffer.limit(size)
                    try {
                        muxer.writeSampleData(outTrack, buffer, info)
                    } catch (e: Exception) {
                        // Sample tidak urut dsb — lewati.
                    }
                }
                if (!extractor.advance()) break
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                muxer?.stop()
            } catch (e: Exception) {
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
            }
            extractor.release()
        }
    }
}
