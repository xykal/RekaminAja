package id.rekaminaja.app.subtitle

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import id.rekaminaja.app.R
import id.rekaminaja.app.ui.RekDialogs
import java.io.File

/** Jembatan UI untuk membuat dan membagikan Auto Subtitle dari Activity. */
object SubtitleUi {

    fun generate(
        activity: Activity,
        uri: Uri,
        videoName: String,
        onSuccess: (File, List<SubtitleCue>) -> Unit = { _, _ -> }
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        var handle: SubtitleGenerator.Handle? = null
        lateinit var progress: RekDialogs.ProgressController
        progress = RekDialogs.showProgress(
            context = activity,
            title = activity.getString(R.string.subtitle_generating),
            initialMessage = activity.getString(R.string.subtitle_stage_extract),
            note = activity.getString(R.string.subtitle_offline_note),
            onCancel = { handle?.cancel() }
        )
        handle = SubtitleGenerator.generate(
            activity,
            uri,
            videoName,
            object : SubtitleGenerator.Listener {
                override fun onProgress(percent: Int, stage: String) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        progress.update(percent, stage)
                    }
                }

                override fun onSuccess(result: SubtitleGenerator.Success) {
                    progress.dismiss()
                    if (activity.isFinishing || activity.isDestroyed) return
                    Toast.makeText(activity, R.string.subtitle_saved, Toast.LENGTH_SHORT).show()
                    onSuccess(result.file, result.cues)
                }

                override fun onError(messageRes: Int) {
                    progress.dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onCancelled() {
                    progress.dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, R.string.subtitle_cancelled, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        )
    }

    fun shareSrt(activity: Activity, file: File) {
        if (!file.isFile) {
            Toast.makeText(activity, R.string.subtitle_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = SubtitleStore.shareUri(activity, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-subrip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(
                Intent.createChooser(send, activity.getString(R.string.subtitle_share_srt))
            )
        } catch (e: ActivityNotFoundException) {
            try {
                val uri = SubtitleStore.shareUri(activity, file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(
                    Intent.createChooser(send, activity.getString(R.string.subtitle_share_srt))
                )
            } catch (ignored: Exception) {
                Toast.makeText(activity, R.string.subtitle_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.subtitle_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
