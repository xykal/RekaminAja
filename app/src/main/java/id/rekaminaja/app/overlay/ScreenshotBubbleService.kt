package id.rekaminaja.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import id.rekaminaja.app.ConsentActivity
import id.rekaminaja.app.R
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.recorder.RecorderState
import id.rekaminaja.app.recorder.ScreenRecordService
import id.rekaminaja.app.util.Util
import kotlin.math.abs

/**
 * Bola screenshot terpisah ala XRecorder.
 *
 * Ketuk = screenshot, seret = pindah/snap ke tepi, tekan-tahan = sembunyikan.
 * Bola ini otomatis hilang saat merekam agar tidak masuk video, dan hilang
 * sesaat saat screenshot diambil agar tidak masuk foto.
 */
class ScreenshotBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var icon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var animator: ValueAnimator? = null

    private var bubbleSizePx = 0
    private var appliedOpacityPercent = -1
    private var tucked = false
    private var touchMoved = false
    private var touchActive = false
    private var longPressed = false
    private var hiddenForScreenshot = false

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { dockIdle() }
    private val longPressRunnable = Runnable {
        if (!touchMoved && bubble != null) {
            longPressed = true
            try {
                bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (e: Exception) {
            }
            Toast.makeText(this, R.string.screenshot_bubble_hidden, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }
    private val updater = object : Runnable {
        override fun run() {
            applyPrefsAndVisibility()
            handler.postDelayed(this, 300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Prefs.screenshotBubble || !Util.canOverlay(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        createBubble()
        running = true
        handler.post(updater)
        scheduleIdle()
    }

    override fun onDestroy() {
        // Bila tidak ada bola lain, bola screenshot terakhir yang pergi juga
        // mengakhiri sesi izin tangkapan demi privasi.
        if (!RecorderState.isRecording &&
            RecorderState.state != RecorderState.State.COUNTDOWN &&
            RecorderState.projectionReady &&
            !FloatingBubbleService.running
        ) {
            try {
                startService(
                    Intent(this, ScreenRecordService::class.java)
                        .setAction(ScreenRecordService.ACTION_END_SESSION)
                )
            } catch (e: Exception) {
            }
        }
        handler.removeCallbacksAndMessages(null)
        animator?.cancel()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble = null
        params = null
        if (instance === this) instance = null
        running = false
        super.onDestroy()
    }

    private fun dp(v: Int): Int = Util.dp(this, v)
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun restAlpha(): Float = Prefs.bubbleOpacityPercent / 100f

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun minY(): Int = statusBarHeight() + dp(4)
    private fun maxY(size: Int): Int {
        val display = Util.realDisplaySize(this)
        return (display.height - size - dp(10)).coerceAtLeast(minY())
    }

    private fun fullEdgeX(side: Int, size: Int): Int {
        val display = Util.realDisplaySize(this)
        return if (side == 0) 0 else display.width - size
    }

    private fun tuckedEdgeX(side: Int, size: Int): Int {
        val display = Util.realDisplaySize(this)
        return if (side == 0) {
            -(size * 0.48f).toInt()
        } else {
            display.width - (size * 0.52f).toInt()
        }
    }

    private fun currentSide(lp: WindowManager.LayoutParams, size: Int): Int {
        val display = Util.realDisplaySize(this)
        return if (lp.x + size / 2 < display.width / 2) 0 else 1
    }

    private fun targetSizePx(): Int = dp(Prefs.bubbleSizeDp.coerceIn(34, 46))

    private fun updateIconGravity() {
        val lp = params
        val side = if (lp == null) {
            Prefs.screenshotBubbleSide.coerceIn(0, 1)
        } else {
            currentSide(lp, bubbleSizePx)
        }
        val gravity = when {
            !tucked -> Gravity.CENTER
            side == 0 -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            else -> Gravity.LEFT or Gravity.CENTER_VERTICAL
        }
        val iconSize = (bubbleSizePx * 0.48f).toInt().coerceAtLeast(dp(15))
        icon?.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, gravity)
    }

    private fun createBubble() {
        val size = targetSizePx()
        bubbleSizePx = size
        appliedOpacityPercent = Prefs.bubbleOpacityPercent
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(0x35, 0x8B, 0xFF),
                    color(R.color.accent_blue),
                    color(R.color.accent_violet)
                )
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), Color.argb(190, 255, 255, 255))
            }
            elevation = dp(6).toFloat()
            alpha = restAlpha()
            contentDescription = getString(R.string.screenshot)
        }
        icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_screenshot)
            setColorFilter(Color.WHITE)
        }
        val iconSize = (size * 0.48f).toInt().coerceAtLeast(dp(15))
        root.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

        val display = Util.realDisplaySize(this)
        val side = Prefs.screenshotBubbleSide.coerceIn(0, 1)
        val startY = if (Prefs.screenshotBubbleY >= 0) {
            Prefs.screenshotBubbleY.coerceIn(minY(), maxY(size))
        } else {
            // Awalnya tepat di bawah posisi bola rekam agar terasa satu set.
            val base = if (Prefs.bubbleY >= 0) Prefs.bubbleY else (display.height * 0.38f).toInt()
            (base + dp(52)).coerceIn(minY(), maxY(size))
        }
        val lp = OverlayUtil.params(
            size,
            size,
            x = fullEdgeX(side, size),
            y = startY,
            gravity = Gravity.TOP or Gravity.START
        )
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params = lp
        attachTouch(root, lp)
        try {
            wm.addView(root, lp)
            bubble = root
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun applyPrefsAndVisibility() {
        if (!Prefs.screenshotBubble) {
            stopSelf()
            return
        }
        val view = bubble ?: return
        val lp = params ?: return

        val targetSize = targetSizePx()
        if (targetSize != bubbleSizePx) {
            val side = currentSide(lp, bubbleSizePx)
            bubbleSizePx = targetSize
            lp.width = targetSize
            lp.height = targetSize
            lp.y = lp.y.coerceIn(minY(), maxY(targetSize))
            lp.x = if (tucked) tuckedEdgeX(side, targetSize) else fullEdgeX(side, targetSize)
            updateIconGravity()
            try {
                wm.updateViewLayout(view, lp)
            } catch (e: Exception) {
            }
        }
        if (Prefs.bubbleOpacityPercent != appliedOpacityPercent) {
            appliedOpacityPercent = Prefs.bubbleOpacityPercent
            view.animate()?.alpha(restAlpha() * if (tucked) 0.84f else 1f)
                ?.setDuration(140)?.start()
        }
        if (!Prefs.bubbleEdgeTuck && tucked) wake()

        val shouldHide = hiddenForScreenshot || RecorderState.isRecording ||
            RecorderState.state == RecorderState.State.COUNTDOWN
        if (shouldHide) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
        } else if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            wake()
        }
    }

    private fun animateX(targetX: Int, durationMs: Long = 190L) {
        val view = bubble ?: return
        val lp = params ?: return
        animator?.cancel()
        if (lp.x == targetX) return
        animator = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                try {
                    wm.updateViewLayout(view, lp)
                } catch (e: Exception) {
                }
            }
            start()
        }
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, 2_800)
    }

    private fun dockIdle() {
        val view = bubble ?: return
        val lp = params ?: return
        if (view.visibility != View.VISIBLE || touchActive) return
        if (!Prefs.bubbleEdgeTuck) {
            view.animate()?.alpha(restAlpha() * 0.58f)?.setDuration(250)?.start()
            return
        }
        val side = currentSide(lp, bubbleSizePx)
        Prefs.screenshotBubbleSide = side
        Prefs.screenshotBubbleY = lp.y
        tucked = true
        updateIconGravity()
        animateX(tuckedEdgeX(side, bubbleSizePx), 220L)
        view.animate()?.alpha(restAlpha() * 0.84f)?.setDuration(220)?.start()
    }

    private fun wake() {
        handler.removeCallbacks(idleRunnable)
        bubble?.animate()?.alpha(restAlpha())?.setDuration(150)?.start()
        val lp = params
        if (tucked && lp != null) {
            tucked = false
            updateIconGravity()
            animateX(fullEdgeX(currentSide(lp, bubbleSizePx), bubbleSizePx), 170L)
        }
        scheduleIdle()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(view: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            val display = Util.realDisplaySize(this)
            val size = bubbleSizePx.coerceAtLeast(dp(34))
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    animator?.cancel()
                    startX = if (tucked) {
                        fullEdgeX(currentSide(lp, size), size)
                    } else {
                        lp.x
                    }
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    touchMoved = false
                    touchActive = true
                    longPressed = false
                    wake()
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        if (!touchMoved) {
                            handler.removeCallbacks(longPressRunnable)
                            animator?.cancel()
                        }
                        touchMoved = true
                    }
                    if (touchMoved) {
                        lp.x = (startX + dx).coerceIn(
                            -(size / 2), display.width - size / 2
                        )
                        lp.y = (startY + dy).coerceIn(minY(), maxY(size))
                        try {
                            wm.updateViewLayout(view, lp)
                        } catch (e: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (longPressed) {
                        longPressed = false
                    } else if (!touchMoved) {
                        captureScreenshot()
                    } else {
                        snapToEdge(lp, size)
                    }
                    touchMoved = false
                    touchActive = false
                    scheduleIdle()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(lp: WindowManager.LayoutParams, size: Int) {
        val side = currentSide(lp, size)
        Prefs.screenshotBubbleSide = side
        Prefs.screenshotBubbleY = lp.y
        tucked = false
        updateIconGravity()
        animateX(fullEdgeX(side, size), 190L)
    }

    private fun captureScreenshot() {
        if (RecorderState.isRecording || RecorderState.state == RecorderState.State.COUNTDOWN) {
            Toast.makeText(this, R.string.screenshot_while_recording, Toast.LENGTH_SHORT).show()
            return
        }
        applyHiddenForScreenshot(true)
        handler.postDelayed({
            try {
                if (RecorderState.projectionReady) {
                    startService(
                        Intent(this, ScreenRecordService::class.java)
                            .setAction(ScreenRecordService.ACTION_SCREENSHOT)
                    )
                } else {
                    startActivity(
                        Intent(this, ConsentActivity::class.java)
                            .putExtra(ConsentActivity.EXTRA_MODE, ConsentActivity.MODE_SCREENSHOT)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
                applyHiddenForScreenshot(false)
            }
        }, 150)

        // Pengaman bila dialog izin dibatalkan sebelum ScreenshotCapturer
        // sempat berjalan. Bila capture jadi berjalan, capturer akan
        // menyembunyikan lagi lalu memulihkan pada waktu selesai.
        handler.postDelayed({
            if (hiddenForScreenshot && !RecorderState.isRecording) {
                applyHiddenForScreenshot(false)
            }
        }, 1_500)
    }

    private fun applyHiddenForScreenshot(hidden: Boolean) {
        hiddenForScreenshot = hidden
        val view = bubble ?: return
        if (hidden) {
            handler.removeCallbacks(idleRunnable)
            animator?.cancel()
            view.visibility = View.GONE
        } else if (!RecorderState.isRecording &&
            RecorderState.state != RecorderState.State.COUNTDOWN
        ) {
            view.visibility = View.VISIBLE
            wake()
        }
    }

    companion object {
        @Volatile
        var running: Boolean = false

        @Volatile
        private var instance: ScreenshotBubbleService? = null

        fun setHiddenForScreenshot(hidden: Boolean) {
            val service = instance ?: return
            service.handler.post { service.applyHiddenForScreenshot(hidden) }
        }
    }
}
