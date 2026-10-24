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
 * Tombol pil RekaminAja: komposit ikon+label dengan latar GradientDrawable sendiri.
 * variant: 0 primary (merah), 1 soft (abu), 2 outline (putih bergaris),
 * 3 dark (hitam), 4 dangerSoft (tint merah).
 */
class RekPillButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val iconView = ImageView(context)
    private val labelView = TextView(context)
    private var variantKind = 0

    var text: String
        get() = labelView.text.toString()
        set(v) {
            labelView.text = v
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumHeight = Util.dp(context, 48)
        val padH = Util.dp(context, 18)
        val padV = Util.dp(context, 12)
        setPadding(padH, padV, padH, padV)

        var initText: String? = null
        var initIcon = 0
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.RekPillButton)
            try {
                initText = ta.getString(R.styleable.RekPillButton_rekText)
                initIcon = ta.getResourceId(R.styleable.RekPillButton_rekIcon, 0)
                variantKind = ta.getInt(R.styleable.RekPillButton_rekVariant, 0)
            } finally {
                ta.recycle()
            }
        }

        val iconSize = Util.dp(context, 19)
        iconView.layoutParams = LayoutParams(iconSize, iconSize).apply {
            marginEnd = Util.dp(context, 8)
        }
        addView(iconView)

        labelView.textSize = 14.5f
        labelView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        labelView.isSingleLine = true
        addView(labelView)

        if (initText != null) text = initText
        if (initIcon != 0) setIcon(initIcon) else iconView.visibility = GONE
        applyVariant()
    }

    fun setIcon(resId: Int) {
        if (resId == 0) {
            iconView.visibility = GONE
            return
        }
        iconView.visibility = VISIBLE
        iconView.setImageResource(resId)
        applyIconTint()
    }

    fun setVariantKind(kind: Int) {
        variantKind = kind
        applyVariant()
    }

    private fun applyIconTint() {
        val tint = when (variantKind) {
            VARIANT_PRIMARY, VARIANT_DARK -> 0xFFFFFFFF.toInt()
            VARIANT_DANGER_SOFT -> ContextCompat.getColor(context, R.color.brand)
            else -> ContextCompat.getColor(context, R.color.ink)
        }
        iconView.setColorFilter(tint)
    }

    private fun applyVariant() {
        val bg = GradientDrawable().apply {
            cornerRadius = Util.dp(context, 999).toFloat()
        }
        when (variantKind) {
            VARIANT_PRIMARY -> {
                bg.setColor(ContextCompat.getColor(context, R.color.brand))
                labelView.setTextColor(0xFFFFFFFF.toInt())
            }
            VARIANT_SOFT -> {
                bg.setColor(ContextCompat.getColor(context, R.color.bg_soft))
                labelView.setTextColor(ContextCompat.getColor(context, R.color.ink))
            }
            VARIANT_OUTLINE -> {
                bg.setColor(ContextCompat.getColor(context, R.color.bg_surface))
                bg.setStroke(
                    Util.dp(context, 1),
                    ContextCompat.getColor(context, R.color.line)
                )
                labelView.setTextColor(ContextCompat.getColor(context, R.color.ink))
            }
            VARIANT_DARK -> {
                bg.setColor(ContextCompat.getColor(context, R.color.dark_bar))
                labelView.setTextColor(0xFFFFFFFF.toInt())
            }
            VARIANT_DANGER_SOFT -> {
                bg.setColor(ContextCompat.getColor(context, R.color.brand_tint))
                labelView.setTextColor(ContextCompat.getColor(context, R.color.brand_deep))
            }
        }
        background = bg
        alpha = if (isEnabled) 1f else 0.45f
        applyIconTint()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEnabled) {
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    animate().scaleX(0.965f).scaleY(0.965f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        const val VARIANT_PRIMARY = 0
        const val VARIANT_SOFT = 1
        const val VARIANT_OUTLINE = 2
        const val VARIANT_DARK = 3
        const val VARIANT_DANGER_SOFT = 4
    }
}
