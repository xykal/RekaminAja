package id.rekaminaja.app.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import id.rekaminaja.app.R
import id.rekaminaja.app.util.Util

/** Bar atas custom pengganti Toolbar/ActionBar: tombol kembali bulat + judul. */
class RekTopBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val titleView = TextView(context)
    private val subtitleView = TextView(context)

    var onBackClick: (() -> Unit)? = null

    init {
        val padH = Util.dp(context, 16)
        setPadding(padH, Util.dp(context, 10), padH, Util.dp(context, 6))
        minimumHeight = Util.dp(context, 64)

        var initTitle: String? = null
        var initSub: String? = null
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.RekTopBar)
            try {
                initTitle = ta.getString(R.styleable.RekTopBar_rekTitle)
                initSub = ta.getString(R.styleable.RekTopBar_rekSubtitle)
            } finally {
                ta.recycle()
            }
        }

        val back = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(ContextCompat.getColor(context, R.color.ink))
            background = ContextCompat.getDrawable(context, R.drawable.bg_back_circle)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.close)
            val s = Util.dp(context, 42)
            val p = Util.dp(context, 10)
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.START or Gravity.CENTER_VERTICAL)
            setOnClickListener { onBackClick?.invoke() }
        }
        addView(back)

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            ).apply {
                marginStart = Util.dp(context, 56)
                marginEnd = Util.dp(context, 8)
            }
        }
        titleView.textSize = 19f
        titleView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        titleView.setTextColor(ContextCompat.getColor(context, R.color.ink))
        titleView.text = initTitle ?: ""
        titleView.maxLines = 1
        col.addView(titleView)

        subtitleView.textSize = 12.5f
        subtitleView.setTextColor(ContextCompat.getColor(context, R.color.muted))
        subtitleView.maxLines = 1
        if (initSub.isNullOrEmpty()) {
            subtitleView.visibility = GONE
        } else {
            subtitleView.text = initSub
        }
        col.addView(subtitleView)
        addView(col)
    }

    fun setTitleText(text: String) {
        titleView.text = text
    }

    fun setSubtitleText(text: String?) {
        if (text.isNullOrEmpty()) {
            subtitleView.visibility = GONE
        } else {
            subtitleView.visibility = VISIBLE
            subtitleView.text = text
        }
    }
}
