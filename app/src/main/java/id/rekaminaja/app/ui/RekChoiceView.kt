package id.rekaminaja.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import id.rekaminaja.app.R
import id.rekaminaja.app.util.Util

/**
 * Baris pilihan pengganti Spinner: label kecil di atas, nilai terpilih,
 * chevron di kanan. Ketuk membuka dialog pilihan custom (RekDialogs).
 */
class RekChoiceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val labelView = TextView(context)
    private val valueView = TextView(context)

    var valueText: String
        get() = valueView.text.toString()
        set(v) {
            valueView.text = v
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = Util.dp(context, 58)
        val padH = Util.dp(context, 16)
        val padV = Util.dp(context, 10)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            cornerRadius = Util.dp(context, 16).toFloat()
            setColor(ContextCompat.getColor(context, R.color.bg_soft))
            setStroke(Util.dp(context, 1), ContextCompat.getColor(context, R.color.line))
        }

        var initLabel: String? = null
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.RekChoiceView)
            try {
                initLabel = ta.getString(R.styleable.RekChoiceView_rekLabel)
            } finally {
                ta.recycle()
            }
        }

        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        labelView.textSize = 11.5f
        labelView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        labelView.setTextColor(ContextCompat.getColor(context, R.color.muted))
        labelView.text = initLabel ?: ""
        col.addView(labelView)

        valueView.textSize = 15f
        valueView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        valueView.setTextColor(ContextCompat.getColor(context, R.color.ink))
        col.addView(valueView)
        addView(col)

        val chev = ImageView(context).apply {
            setImageResource(R.drawable.ic_chev_down)
            setColorFilter(ContextCompat.getColor(context, R.color.muted))
            val s = Util.dp(context, 18)
            layoutParams = LayoutParams(s, s)
        }
        addView(chev)
    }

    fun setLabel(textRes: Int) {
        labelView.setText(textRes)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN ->
                animate().scaleX(0.985f).scaleY(0.985f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(event)
    }
}
