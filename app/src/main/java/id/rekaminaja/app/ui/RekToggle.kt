package id.rekaminaja.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.Checkable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import id.rekaminaja.app.R
import id.rekaminaja.app.util.Util

/** Saklar on/off buatan sendiri: rel pil + kenop geser beranimasi. */
class RekToggle @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle), Checkable {

    private var checkedState = false
    private var fraction = 0f // 0 = off, 1 = on
    private var animator: ValueAnimator? = null

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val offColor = ContextCompat.getColor(context, R.color.bg_soft_pressed)
    private val onColor = ContextCompat.getColor(context, R.color.brand)
    private val lineColor = ContextCompat.getColor(context, R.color.line)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun isChecked(): Boolean = checkedState

    override fun setChecked(checked: Boolean) {
        if (checkedState == checked) {
            fraction = if (checked) 1f else 0f
            invalidate()
            return
        }
        checkedState = checked
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, if (checked) 1f else 0f).apply {
            duration = 180
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Set tanpa animasi (untuk binding RecyclerView). */
    fun setCheckedSilently(checked: Boolean) {
        animator?.cancel()
        checkedState = checked
        fraction = if (checked) 1f else 0f
        invalidate()
    }

    override fun toggle() {
        setChecked(!checkedState)
        onCheckedChange?.invoke(checkedState)
    }

    override fun performClick(): Boolean {
        if (isEnabled) toggle()
        return super.performClick()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(Util.dp(context, 52), widthMeasureSpec),
            resolveSize(Util.dp(context, 32), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = Util.dp(context, 3).toFloat()
        // Rel.
        paint.color = ColorUtils.blendARGB(offColor, onColor, fraction)
        paint.alpha = if (isEnabled) 255 else 140
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = height / 2f
        canvas.drawRoundRect(rect, r, r, paint)
        // Kenop.
        val knobR = height / 2f - pad
        val startX = pad + knobR
        val endX = width - pad - knobR
        val knobX = startX + (endX - startX) * fraction
        paint.setShadowLayer(Util.dp(context, 3).toFloat(), 0f, Util.dp(context, 1).toFloat(), 0x33000000)
        paint.color = 0xFFFFFFFF.toInt()
        paint.alpha = 255
        canvas.drawCircle(knobX, height / 2f, knobR, paint)
        paint.clearShadowLayer()
        if (fraction < 0.5f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Util.dp(context, 1).toFloat()
            paint.color = lineColor
            canvas.drawCircle(knobX, height / 2f, knobR, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
