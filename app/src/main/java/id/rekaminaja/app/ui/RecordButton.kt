package id.rekaminaja.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import id.rekaminaja.app.R
import id.rekaminaja.app.util.Util
import kotlin.math.min

/**
 * Tombol rekam signature RekaminAja: lingkaran merah besar dengan halo berdenyut
 * saat merekam. Digambar penuh dengan Canvas, bukan tombol bawaan.
 */
class RecordButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Visual { IDLE, RECORDING, PAUSED, COUNTDOWN }

    var visual: Visual = Visual.IDLE
        set(v) {
            field = v
            contentDescription = when (v) {
                Visual.IDLE -> context.getString(R.string.start_recording)
                Visual.COUNTDOWN -> context.getString(R.string.notif_preparing)
                else -> context.getString(R.string.stop)
            }
            invalidate()
        }

    private val brand = ContextCompat.getColor(context, R.color.brand)
    private val brandTint = ContextCompat.getColor(context, R.color.brand_tint)
    private val amber = ContextCompat.getColor(context, R.color.bubble_paused)
    private val faint = ContextCompat.getColor(context, R.color.faint)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val def = Util.dp(context, 176)
        val w = resolveSize(def, widthMeasureSpec)
        val h = resolveSize(def, heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseR = min(width, height) / 2f - Util.dp(context, 20)

        // Halo berdenyut saat merekam.
        if (visual == Visual.RECORDING) {
            val t = (SystemClock.uptimeMillis() % 1600L) / 1600f
            for (off in floatArrayOf(0f, 0.5f)) {
                val p = (t + off) % 1f
                paint.color = brand
                paint.alpha = (70 * (1f - p)).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, baseR + Util.dp(context, 6) + Util.dp(context, 14) * p, paint)
            }
            paint.alpha = 255
            postInvalidateOnAnimation()
        }

        // Ring tint statis.
        paint.color = brandTint
        canvas.drawCircle(cx, cy, baseR + Util.dp(context, 9), paint)

        // Badan utama + bayangan lembut.
        paint.setShadowLayer(Util.dp(context, 10).toFloat(), 0f, Util.dp(context, 5).toFloat(), 0x33000000)
        paint.color = when (visual) {
            Visual.PAUSED -> amber
            Visual.COUNTDOWN -> faint
            else -> brand
        }
        canvas.drawCircle(cx, cy, baseR, paint)
        paint.clearShadowLayer()

        // Simbol tengah.
        paint.color = Color.WHITE
        when (visual) {
            Visual.IDLE, Visual.COUNTDOWN ->
                canvas.drawCircle(cx, cy, baseR * 0.34f, paint)
            Visual.RECORDING -> {
                val s = baseR * 0.52f
                rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
                val r = Util.dp(context, 9).toFloat()
                canvas.drawRoundRect(rect, r, r, paint)
            }
            Visual.PAUSED -> {
                val barW = baseR * 0.18f
                val barH = baseR * 0.62f
                val gap = baseR * 0.16f
                val r = Util.dp(context, 6).toFloat()
                rect.set(cx - gap - barW, cy - barH / 2, cx - gap, cy + barH / 2)
                canvas.drawRoundRect(rect, r, r, paint)
                rect.set(cx + gap, cy - barH / 2, cx + gap + barW, cy + barH / 2)
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        return super.onTouchEvent(event)
    }
}
