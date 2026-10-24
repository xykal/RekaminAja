package id.rekaminaja.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import id.rekaminaja.app.R
import id.rekaminaja.app.util.Util

/** Slider integer buatan sendiri: rel tipis, isi merah, ibu jari putih besar. */
class RekSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var minValue: Int = 0
        set(v) {
            field = v
            value = value
        }
    var maxValue: Int = 100
        set(v) {
            field = v.coerceAtLeast(minValue + 1)
            value = value
        }
    var value: Int = 0
        set(v) {
            field = v.coerceIn(minValue, maxValue)
            invalidate()
        }

    /** (value, fromUser) */
    var onValueChange: ((Int, Boolean) -> Unit)? = null
    var onTouchEnd: (() -> Unit)? = null

    private val brand = ContextCompat.getColor(context, R.color.brand)
    private val trackColor = ContextCompat.getColor(context, R.color.bg_soft_pressed)
    private val lineColor = ContextCompat.getColor(context, R.color.line)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var dragging = false

    init {
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setValueSilently(v: Int) {
        value = v
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(Util.dp(context, 200), widthMeasureSpec),
            resolveSize(Util.dp(context, 36), heightMeasureSpec)
        )
    }

    private fun thumbR(): Float = Util.dp(context, if (dragging) 13 else 11).toFloat()

    private fun xForValue(v: Int): Float {
        val r = thumbR()
        val span = (maxValue - minValue).toFloat().coerceAtLeast(1f)
        return r + (width - 2 * r) * ((v - minValue) / span)
    }

    private fun valueForX(x: Float): Int {
        val r = thumbR()
        val t = ((x - r) / (width - 2 * r)).coerceIn(0f, 1f)
        return minValue + Math.round(t * (maxValue - minValue))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val r = thumbR()
        val trackH = Util.dp(context, 5).toFloat()
        val end = xForValue(value)
        // Rel latar.
        paint.color = trackColor
        rect.set(r, cy - trackH / 2, width - r, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
        // Isi aktif.
        paint.color = brand
        rect.set(r, cy - trackH / 2, end, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
        // Ibu jari.
        paint.setShadowLayer(Util.dp(context, 4).toFloat(), 0f, Util.dp(context, 2).toFloat(), 0x33000000)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(end, cy, r, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Util.dp(context, 1).toFloat()
        paint.color = lineColor
        canvas.drawCircle(end, cy, r, paint)
        paint.style = Paint.Style.FILL
        // Titik tengah.
        paint.color = brand
        canvas.drawCircle(end, cy, Util.dp(context, 3).toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
            }
            MotionEvent.ACTION_MOVE -> updateFromTouch(event.x)
            MotionEvent.ACTION_UP -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                onTouchEnd?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onTouchEnd?.invoke()
                invalidate()
            }
        }
        return true
    }

    private fun updateFromTouch(x: Float) {
        val v = valueForX(x)
        if (v != value) {
            value = v
            onValueChange?.invoke(v, true)
        } else {
            invalidate()
        }
    }

    override fun performClick(): Boolean = super.performClick()
}
