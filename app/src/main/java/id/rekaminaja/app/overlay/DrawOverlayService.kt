package id.rekaminaja.app.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import id.rekaminaja.app.R
import id.rekaminaja.app.util.Util

/**
 * Kuas di atas layar: menggambar di overlay full-screen sehingga coretan ikut terekam.
 * Saat aktif, seluruh sentuhan layar ditangkap kanvas — gunakan tombol Tutup untuk selesai.
 */
class DrawOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var canvasView: DrawingView? = null
    private var toolbar: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Util.canOverlay(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val drawing = DrawingView(this)
        canvasView = drawing
        try {
            wm.addView(
                drawing,
                OverlayUtil.params(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    gravity = Gravity.TOP or Gravity.START
                )
            )
        } catch (e: Exception) {
            stopSelf()
            return
        }
        createToolbar(drawing)
    }

    private fun createToolbar(drawing: DrawingView) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = Util.dp(this@DrawOverlayService, 8)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = Util.dp(this@DrawOverlayService, 22).toFloat()
                setColor(Color.argb(235, 28, 28, 32))
            }
        }
        fun btn(labelRes: Int, action: () -> Unit) {
            val b = Button(this).apply {
                setText(labelRes)
                isAllCaps = false
                setOnClickListener { action() }
            }
            bar.addView(b)
        }
        btn(R.string.draw_color) { drawing.cycleColor() }
        btn(R.string.draw_undo) { drawing.undo() }
        btn(R.string.draw_clear) { drawing.clear() }
        btn(R.string.close) { stopSelf() }
        try {
            wm.addView(
                bar,
                OverlayUtil.params(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    y = Util.dp(this, 28),
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
            toolbar = bar
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        canvasView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        toolbar?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        canvasView = null
        toolbar = null
        super.onDestroy()
    }

    class DrawingView(context: Context) : View(context) {
        private val strokes = mutableListOf<Pair<Path, Paint>>()
        private var currentPath: Path? = null
        private val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.WHITE, Color.BLACK
        )
        private var colorIdx = 0

        private fun newPaint(): Paint = Paint().apply {
            color = colors[colorIdx]
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        private var currentPaint: Paint = newPaint()

        fun cycleColor() {
            colorIdx = (colorIdx + 1) % colors.size
            currentPaint = newPaint()
        }

        fun undo() {
            if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
            invalidate()
        }

        fun clear() {
            strokes.clear()
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentPath = Path().apply { moveTo(event.x, event.y) }
                }
                MotionEvent.ACTION_MOVE -> {
                    currentPath?.lineTo(event.x, event.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentPath?.let { strokes.add(it to currentPaint) }
                    currentPath = null
                    currentPaint = newPaint()
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for ((path, paint) in strokes) canvas.drawPath(path, paint)
            currentPath?.let { canvas.drawPath(it, currentPaint) }
        }
    }
}
