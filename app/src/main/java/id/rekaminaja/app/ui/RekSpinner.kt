package id.rekaminaja.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import id.rekaminaja.app.R
import id.rekaminaja.app.util.Util

/** Indikator memuat: busur merah berputar, digambar sendiri (bukan ProgressBar stok). */
class RekSpinner @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = Util.dp(context, 36)
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = Util.dp(context, 3).toFloat()
        paint.strokeWidth = stroke
        val inset = stroke + Util.dp(context, 2)
        rect.set(inset, inset, width - inset, height - inset)
        // Rel samar.
        paint.color = ContextCompat.getColor(context, R.color.bg_soft_pressed)
        canvas.drawArc(rect, 0f, 360f, false, paint)
        // Busur berputar.
        val start = (SystemClock.uptimeMillis() % 1200L) / 1200f * 360f
        paint.color = ContextCompat.getColor(context, R.color.brand)
        canvas.drawArc(rect, start, 260f, false, paint)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
