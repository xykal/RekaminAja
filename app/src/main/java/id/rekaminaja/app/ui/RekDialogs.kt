package id.rekaminaja.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import id.rekaminaja.app.R
import id.rekaminaja.app.util.Util

/**
 * Dialog RekaminAja: kartu putih melengkung dengan baris-baris pil lembut.
 * Pengganti AlertDialog stok Android.
 */
object RekDialogs {

    class ProgressController internal constructor(
        private val dialog: Dialog,
        private val messageView: TextView,
        private val percentView: TextView,
        private val fillView: View,
        private val spacerView: View,
        private val elapsedView: TextView? = null
    ) {
        // Timer berjalan supaya proses panjang tidak terasa stuck.
        private val startedAt = android.os.SystemClock.elapsedRealtime()
        private val ticker = object : Runnable {
            override fun run() {
                val v = elapsedView ?: return
                if (!dialog.isShowing) return
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                v.text = v.context.getString(
                    R.string.subtitle_elapsed_format, Util.formatDuration(elapsed)
                )
                v.postDelayed(this, 1_000)
            }
        }

        init {
            elapsedView?.post(ticker)
        }
        fun update(percent: Int, message: String) {
            val p = percent.coerceIn(0, 100)
            val update = Runnable {
                if (dialog.isShowing) {
                    messageView.text = message
                    percentView.text = percentView.context
                        .getString(R.string.subtitle_progress_format, p)
                    (fillView.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.weight = p.toFloat()
                        fillView.layoutParams = it
                    }
                    (spacerView.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.weight = (100 - p).toFloat()
                        spacerView.layoutParams = it
                    }
                }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                update.run()
            } else {
                messageView.post(update)
            }
        }

        fun dismiss() {
            elapsedView?.removeCallbacks(ticker)
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (e: Exception) {
            }
        }

        val isShowing: Boolean get() = dialog.isShowing
    }

    private fun card(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = Util.dp(context, 20)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = Util.dp(context, 24).toFloat()
                setColor(ContextCompat.getColor(context, R.color.bg_surface))
            }
        }
    }

    private fun titleView(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setPadding(0, 0, 0, Util.dp(context, 12))
        }
    }

    private fun show(context: Context, content: LinearLayout) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val w = (context.resources.displayMetrics.widthPixels * 0.88).toInt()
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun row(
        context: Context, label: String, selected: Boolean, danger: Boolean = false
    ): LinearLayout {
        val tintBg = ContextCompat.getColor(context, R.color.brand_tint)
        val softBg = ContextCompat.getColor(context, R.color.bg_soft)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = Util.dp(context, 50)
            val ph = Util.dp(context, 16)
            setPadding(ph, Util.dp(context, 10), ph, Util.dp(context, 10))
            background = GradientDrawable().apply {
                cornerRadius = Util.dp(context, 14).toFloat()
                setColor(if (selected) tintBg else softBg)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(context, 8) }
        }
        val tv = TextView(context).apply {
            text = label
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        danger -> R.color.brand_deep
                        selected -> R.color.brand_deep
                        else -> R.color.ink
                    }
                )
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tv)
        if (selected) {
            val check = ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(ContextCompat.getColor(context, R.color.brand))
                val s = Util.dp(context, 18)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            row.addView(check)
        }
        return row
    }

    fun showOptions(
        context: Context, title: String, labels: List<String>, onPick: (Int) -> Unit
    ) {
        showChoice(context, title, labels, selectedIndex = -1, onPick = onPick)
    }

    fun showChoice(
        context: Context,
        title: String,
        labels: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val content = card(context)
        content.addView(titleView(context, title))
        var dialogRef: Dialog? = null
        for (i in labels.indices) {
            val r = row(context, labels[i], selected = i == selectedIndex)
            r.setOnClickListener {
                dialogRef?.dismiss()
                onPick(i)
            }
            content.addView(r)
        }
        val cancel = RekPillButton(context).apply {
            setVariantKind(RekPillButton.VARIANT_SOFT)
            text = context.getString(R.string.cancel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(context, 14) }
            setOnClickListener { dialogRef?.dismiss() }
        }
        content.addView(cancel)
        // Tampilkan manual agar bisa memegang referensi dialog untuk dismiss.
        val dialog = Dialog(context)
        dialogRef = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val w = (context.resources.displayMetrics.widthPixels * 0.88).toInt()
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String,
        danger: Boolean = true,
        onConfirm: () -> Unit
    ) {
        val content = card(context)
        content.addView(titleView(context, title))
        val msg = TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            setPadding(0, 0, 0, Util.dp(context, 4))
        }
        content.addView(msg)
        var dialogRef: Dialog? = null
        val confirm = RekPillButton(context).apply {
            setVariantKind(
                if (danger) RekPillButton.VARIANT_DANGER_SOFT else RekPillButton.VARIANT_PRIMARY
            )
            text = confirmLabel
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(context, 12) }
            setOnClickListener {
                dialogRef?.dismiss()
                onConfirm()
            }
        }
        content.addView(confirm)
        val cancel = RekPillButton(context).apply {
            setVariantKind(RekPillButton.VARIANT_SOFT)
            text = context.getString(R.string.cancel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(context, 8) }
            setOnClickListener { dialogRef?.dismiss() }
        }
        content.addView(cancel)
        content.visibility = View.VISIBLE
        val dialog = Dialog(context)
        dialogRef = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val w = (context.resources.displayMetrics.widthPixels * 0.88).toInt()
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    fun showProgress(
        context: Context,
        title: String,
        initialMessage: String,
        note: String? = null,
        onCancel: () -> Unit
    ): ProgressController {
        val content = card(context)
        content.addView(titleView(context, title))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val spinner = RekSpinner(context).apply {
            val s = Util.dp(context, 40)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        row.addView(spinner)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = Util.dp(context, 12) }
        }
        val messageView = TextView(context).apply {
            text = initialMessage
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
        }
        column.addView(messageView)
        val percentView = TextView(context).apply {
            text = context.getString(R.string.subtitle_progress_format, 0)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.brand_deep))
        }
        column.addView(percentView)
        val elapsedView = TextView(context).apply {
            text = context.getString(
                R.string.subtitle_elapsed_format, Util.formatDuration(0L)
            )
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
        }
        column.addView(elapsedView)
        row.addView(column)
        content.addView(row)

        val fillView = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = Util.dp(context, 999).toFloat()
                setColor(ContextCompat.getColor(context, R.color.brand))
            }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0f
            )
        }
        val spacerView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 100f
            )
        }
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = Util.dp(context, 999).toFloat()
                setColor(ContextCompat.getColor(context, R.color.bg_soft_pressed))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Util.dp(context, 8)
            ).apply { topMargin = Util.dp(context, 16) }
        }
        track.addView(fillView)
        track.addView(spacerView)
        content.addView(track)

        if (!note.isNullOrBlank()) {
            val noteView = TextView(context).apply {
                text = note
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                setPadding(0, Util.dp(context, 12), 0, 0)
            }
            content.addView(noteView)
        }

        val dialog = Dialog(context)
        lateinit var controller: ProgressController
        val cancel = RekPillButton(context).apply {
            setVariantKind(RekPillButton.VARIANT_SOFT)
            text = context.getString(R.string.cancel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(context, 16) }
            setOnClickListener {
                controller.dismiss()
                onCancel()
            }
        }
        content.addView(cancel)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val w = (context.resources.displayMetrics.widthPixels * 0.88).toInt()
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        controller = ProgressController(
            dialog, messageView, percentView, fillView, spacerView, elapsedView
        )
        dialog.show()
        return controller
    }
}
