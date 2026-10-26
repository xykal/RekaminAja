package id.rekaminaja.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import id.rekaminaja.app.CensorEditorActivity
import id.rekaminaja.app.ConsentActivity
import id.rekaminaja.app.GalleryActivity
import id.rekaminaja.app.MainActivity
import id.rekaminaja.app.R
import id.rekaminaja.app.SettingsActivity
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.recorder.RecorderState
import id.rekaminaja.app.recorder.ScreenRecordService
import id.rekaminaja.app.recorder.VoiceFxState
import id.rekaminaja.app.recorder.VoicePreset
import id.rekaminaja.app.ui.RekSlider
import id.rekaminaja.app.util.Util
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Bola melayang RekaminAja dengan pola interaksi ala XRecorder:
 * - menempel di tepi, lalu setengahnya menyelip keluar layar saat idle,
 * - ketuk untuk toolbox; tekan-tahan untuk mulai/stop cepat,
 * - chip timer terpisah di samping bola saat merekam,
 * - seret ke lingkaran hapus di bawah-tengah untuk menyembunyikan bola,
 * - panel berisi aksi bulat, Deck Efek Suara, volume mic, dan volume HP.
 *
 * Ukuran/opasitas/perilaku tuck dapat dikustomisasi dari Settings. Posisi
 * terakhir (sisi + Y) diingat antar sesi lewat [Prefs].
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleIcon: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var timerChip: LinearLayout? = null
    private var timerChipParams: WindowManager.LayoutParams? = null
    private var timerDot: View? = null
    private var timerText: TextView? = null
    private var panel: LinearLayout? = null
    private var removeTarget: LinearLayout? = null
    private var removeCircle: FrameLayout? = null
    private var snapAnimator: ValueAnimator? = null
    private var pulseOn = false
    private var bubbleSizePx = 0
    private var appliedOpacityPercent = -1
    private var tucked = false
    private var touchMoved = false
    private var touchActive = false
    private var longPressed = false

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { dockBubbleIdle() }
    private val longPressRunnable = Runnable {
        if (!touchMoved && bubble != null) {
            longPressed = true
            try {
                bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (e: Exception) {
            }
            quickToggleRecording()
        }
    }

    private val updater = object : Runnable {
        override fun run() {
            applyBubblePrefsIfChanged()
            updateBubbleVisual()
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Util.canOverlay(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        VoiceFxState.syncFromPrefs()
        createBubble()
        running = true
        handler.post(updater)
        scheduleIdleDock()
    }

    override fun onDestroy() {
        // Bola pergi = sesi izin tangkap selesai. Rekaman berikutnya akan
        // meminta izin sekali lagi (lalu disimpan selama bola tampil).
        if (!RecorderState.isRecording &&
            RecorderState.state != RecorderState.State.COUNTDOWN &&
            RecorderState.projectionReady
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
        snapAnimator?.cancel()
        removeView(bubble)
        removeView(panel)
        removeView(removeTarget)
        bubble = null
        panel = null
        removeTarget = null
        running = false
        super.onDestroy()
    }

    private fun removeView(v: View?) {
        v?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
    }

    private fun dp(v: Int): Int = Util.dp(this, v)
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    // ---------------------------------------------------------------- bola

    private fun createBubble() {
        val size = dp(Prefs.bubbleSizeDp)
        bubbleSizePx = size
        appliedOpacityPercent = Prefs.bubbleOpacityPercent
        bubbleBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.rgb(0xFF, 0x6A, 0x3D),
                color(R.color.brand),
                color(R.color.accent_violet)
            )
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), Color.argb(190, 255, 255, 255))
        }
        val root = FrameLayout(this).apply {
            background = bubbleBg
            elevation = dp(6).toFloat()
            alpha = restAlpha()
        }
        bubbleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_record)
            setColorFilter(Color.WHITE)
        }
        val iconSize = (size * 0.46f).toInt().coerceAtLeast(dp(14))
        root.addView(
            bubbleIcon,
            FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        )

        val display = Util.realDisplaySize(this)
        val startX = fullEdgeX(Prefs.bubbleSide, size)
        val startY = if (Prefs.bubbleY >= 0) {
            Prefs.bubbleY.coerceIn(minY(), maxY(size))
        } else {
            (display.height * 0.38f).toInt()
        }
        val lp = OverlayUtil.params(
            size, size, x = startX, y = startY,
            gravity = Gravity.TOP or Gravity.START
        )
        // Diperlukan agar setengah bola boleh menyelip keluar tepi layar.
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        bubbleParams = lp
        attachDrag(root, lp)
        try {
            wm.addView(root, lp)
            bubble = root
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun minY(): Int = statusBarHeight() + dp(4)

    private fun maxY(size: Int): Int {
        val display = Util.realDisplaySize(this)
        return (display.height - size - dp(10)).coerceAtLeast(minY())
    }

    private fun restAlpha(): Float = Prefs.bubbleOpacityPercent / 100f

    private fun fullEdgeX(side: Int, size: Int): Int {
        val display = Util.realDisplaySize(this)
        return if (side == 0) 0 else display.width - size
    }

    private fun tuckedEdgeX(side: Int, size: Int): Int {
        val display = Util.realDisplaySize(this)
        // Sisakan sekitar 52% bola sebagai tab sentuh di tepi layar.
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

    private fun animateBubbleX(targetX: Int, durationMs: Long = 190L) {
        val lp = bubbleParams ?: return
        val view = bubble ?: return
        snapAnimator?.cancel()
        if (lp.x == targetX) {
            positionTimerChip()
            return
        }
        snapAnimator = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                try {
                    wm.updateViewLayout(view, lp)
                } catch (e: Exception) {
                }
                positionTimerChip()
            }
            start()
        }
    }

    private fun applyBubblePrefsIfChanged() {
        val view = bubble ?: return
        val lp = bubbleParams ?: return
        val targetSize = dp(Prefs.bubbleSizeDp)
        if (targetSize != bubbleSizePx) {
            val side = currentSide(lp, bubbleSizePx)
            bubbleSizePx = targetSize
            lp.width = targetSize
            lp.height = targetSize
            lp.y = lp.y.coerceIn(minY(), maxY(targetSize))
            lp.x = if (tucked) tuckedEdgeX(side, targetSize) else fullEdgeX(side, targetSize)
            val iconSize = (targetSize * 0.46f).toInt().coerceAtLeast(dp(14))
            bubbleIcon?.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            try {
                wm.updateViewLayout(view, lp)
            } catch (e: Exception) {
            }
            positionTimerChip()
        }
        val opacity = Prefs.bubbleOpacityPercent
        if (opacity != appliedOpacityPercent) {
            appliedOpacityPercent = opacity
            view.animate()?.alpha(restAlpha() * if (tucked) 0.84f else 1f)
                ?.setDuration(140)?.start()
        }
        if (!Prefs.bubbleEdgeTuck && tucked) wakeBubble()
    }

    private fun scheduleIdleDock() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, 2_800)
    }

    private fun dockBubbleIdle() {
        val view = bubble ?: return
        val lp = bubbleParams ?: return
        if (view.visibility != View.VISIBLE || panel != null || removeTarget != null || touchActive) return
        if (!Prefs.bubbleEdgeTuck) {
            view.animate()?.alpha(restAlpha() * 0.58f)?.setDuration(250)?.start()
            return
        }
        val side = currentSide(lp, bubbleSizePx)
        Prefs.bubbleSide = side
        Prefs.bubbleY = lp.y
        tucked = true
        animateBubbleX(tuckedEdgeX(side, bubbleSizePx), 220L)
        view.animate()?.alpha(restAlpha() * 0.84f)?.setDuration(220)?.start()
    }

    private fun wakeBubble() {
        handler.removeCallbacks(idleRunnable)
        bubble?.animate()?.alpha(restAlpha())?.setDuration(150)?.start()
        val lp = bubbleParams
        if (tucked && lp != null) {
            tucked = false
            animateBubbleX(fullEdgeX(currentSide(lp, bubbleSizePx), bubbleSizePx), 170L)
        }
        scheduleIdleDock()
    }

    private fun quickToggleRecording() {
        closePanel()
        wakeBubble()
        if (RecorderState.isRecording || RecorderState.state == RecorderState.State.COUNTDOWN) {
            sendRecorder(ScreenRecordService.ACTION_STOP)
        } else if (RecorderState.projectionReady) {
            sendRecorder(ScreenRecordService.ACTION_START)
        } else {
            openConsent(ConsentActivity.MODE_RECORD)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            val display = Util.realDisplaySize(this)
            val size = bubbleSizePx.coerceAtLeast(dp(34))
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    val wasTucked = tucked
                    startX = if (wasTucked) {
                        fullEdgeX(currentSide(lp, size), size)
                    } else {
                        lp.x
                    }
                    startY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    touchMoved = false
                    touchActive = true
                    longPressed = false
                    wakeBubble()
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
                            snapAnimator?.cancel()
                            closePanel()
                            showRemoveTarget()
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
                        positionTimerChip()
                        updateRemoveHover(lp, size)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    val overRemove = touchMoved && isOverRemoveTarget(lp, size)
                    hideRemoveTarget()
                    if (longPressed) {
                        longPressed = false
                    } else if (!touchMoved) {
                        togglePanel()
                    } else if (overRemove) {
                        stopSelf()
                    } else {
                        snapToEdge(lp, size)
                    }
                    touchMoved = false
                    touchActive = false
                    scheduleIdleDock()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(lp: WindowManager.LayoutParams, size: Int) {
        val side = currentSide(lp, size)
        Prefs.bubbleSide = side
        Prefs.bubbleY = lp.y
        tucked = false
        animateBubbleX(fullEdgeX(side, size), 190L)
    }

    // ------------------------------------------------------ zona hapus bola

    private fun showRemoveTarget() {
        if (removeTarget != null) return
        val circle = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(color(R.color.brand), color(R.color.brand_deep))
            ).apply { shape = GradientDrawable.OVAL }
            elevation = dp(6).toFloat()
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(Color.WHITE)
        }
        circle.addView(
            icon,
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        )
        removeCircle = circle
        val hint = TextView(this).apply {
            text = getString(R.string.bubble_remove_hint)
            setTextColor(color(R.color.ink_soft))
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(circle, LinearLayout.LayoutParams(dp(64), dp(64)))
        root.addView(hint)
        val lp = OverlayUtil.params(
            dp(170), WindowManager.LayoutParams.WRAP_CONTENT,
            y = dp(72), gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            touchable = false
        )
        try {
            wm.addView(root, lp)
            removeTarget = root
        } catch (e: Exception) {
        }
    }

    private fun hideRemoveTarget() {
        removeView(removeTarget)
        removeTarget = null
        removeCircle = null
    }

    /** Perkiraan pusat lingkaran hapus (bawah-tengah layar). */
    private fun removeCenter(): Pair<Float, Float> {
        val display = Util.realDisplaySize(this)
        return display.width / 2f to (display.height - dp(72) - dp(32)).toFloat()
    }

    private fun isOverRemoveTarget(lp: WindowManager.LayoutParams, size: Int): Boolean {
        val (cx, cy) = removeCenter()
        val bx = lp.x + size / 2f
        val by = lp.y + size / 2f
        return hypot(bx - cx, by - cy) <= dp(58)
    }

    private fun updateRemoveHover(lp: WindowManager.LayoutParams, size: Int) {
        val over = isOverRemoveTarget(lp, size)
        removeCircle?.animate()
            ?.scaleX(if (over) 1.18f else 1f)
            ?.scaleY(if (over) 1.18f else 1f)
            ?.setDuration(120)?.start()
    }

    // ------------------------------------------------------------ visual bola

    private var lastVisualState: RecorderState.State? = null

    /** setColors() baru ada di API 29, jadi gradasi dibuat ulang saat state berubah. */
    private fun applyBubbleGradient(vararg colors: Int) {
        bubbleBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, colors
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), Color.argb(190, 255, 255, 255))
        }
        bubble?.background = bubbleBg
    }

    private fun ensureTimerChip() {
        if (timerChip != null) return
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.brand))
            }
        }
        timerDot = dot
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }
        timerText = text
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.argb(232, 23, 24, 28))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
            elevation = dp(5).toFloat()
            setPadding(dp(8), dp(5), dp(9), dp(5))
        }
        root.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))
        root.addView(text, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(5) })
        val lp = OverlayUtil.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            gravity = Gravity.TOP or Gravity.START,
            touchable = false
        )
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        try {
            wm.addView(root, lp)
            timerChip = root
            timerChipParams = lp
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(150).start()
        } catch (e: Exception) {
            timerDot = null
            timerText = null
        }
    }

    private fun removeTimerChip() {
        removeView(timerChip)
        timerChip = null
        timerChipParams = null
        timerDot = null
        timerText = null
    }

    private fun positionTimerChip() {
        val chip = timerChip ?: return
        val chipLp = timerChipParams ?: return
        val lp = bubbleParams ?: return
        val display = Util.realDisplaySize(this)
        chip.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val chipW = chip.measuredWidth.coerceAtLeast(dp(48))
        val chipH = chip.measuredHeight.coerceAtLeast(dp(22))
        val side = currentSide(lp, bubbleSizePx)
        chipLp.x = if (side == 0) {
            lp.x + bubbleSizePx + dp(5)
        } else {
            lp.x - chipW - dp(5)
        }.coerceIn(0, (display.width - chipW).coerceAtLeast(0))
        chipLp.y = (lp.y + (bubbleSizePx - chipH) / 2)
            .coerceIn(minY(), (display.height - chipH - dp(8)).coerceAtLeast(minY()))
        try {
            wm.updateViewLayout(chip, chipLp)
        } catch (e: Exception) {
        }
    }

    private fun updateBubbleVisual() {
        val state = RecorderState.state
        val shouldHide = Prefs.hideBubbleWhileRecording && RecorderState.isRecording
        if (shouldHide) {
            if (panel != null) closePanel()
            removeTimerChip()
            if (bubble?.visibility != View.GONE) bubble?.visibility = View.GONE
            return
        } else if (bubble?.visibility != View.VISIBLE) {
            bubble?.visibility = View.VISIBLE
            wakeBubble()
        }
        if (state != lastVisualState) {
            lastVisualState = state
            when (state) {
                RecorderState.State.PAUSED -> {
                    applyBubbleGradient(
                        Color.rgb(0xF5, 0xA6, 0x23),
                        color(R.color.bubble_paused)
                    )
                    bubbleIcon?.setImageResource(R.drawable.ic_pause)
                }
                else -> {
                    applyBubbleGradient(
                        Color.rgb(0xFF, 0x6A, 0x3D),
                        color(R.color.brand),
                        color(R.color.accent_violet)
                    )
                    bubbleIcon?.setImageResource(R.drawable.ic_record)
                }
            }
            bubbleIcon?.setColorFilter(Color.WHITE)
        }
        when (state) {
            RecorderState.State.RECORDING, RecorderState.State.PAUSED -> {
                if (Prefs.bubbleShowTimer) {
                    ensureTimerChip()
                    timerText?.text = Util.formatDuration(RecorderState.elapsedMs())
                    timerDot?.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(
                            if (state == RecorderState.State.PAUSED) {
                                color(R.color.bubble_paused)
                            } else {
                                color(R.color.brand)
                            }
                        )
                    }
                    positionTimerChip()
                } else {
                    removeTimerChip()
                }
                if (state == RecorderState.State.RECORDING) {
                    pulseOn = !pulseOn
                    val s = if (pulseOn) 1.13f else 1f
                    bubbleIcon?.animate()?.scaleX(s)?.scaleY(s)?.setDuration(450)?.start()
                }
            }
            else -> {
                removeTimerChip()
                bubbleIcon?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
            }
        }
    }

    // ---------------------------------------------------------------- panel

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun closePanel() {
        val wasOpen = panel != null
        removeView(panel)
        panel = null
        if (wasOpen) scheduleIdleDock()
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(Color.argb(252, 255, 255, 255))
        setStroke(dp(1), color(R.color.line))
    }

    private fun label(text: String, sizeSp: Float, colorRes: Int = R.color.ink): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(colorRes))
        }

    private fun roundAction(
        parent: LinearLayout, iconRes: Int, text: String,
        bgColor: Int, iconColor: Int, action: () -> Unit
    ) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener {
                closePanel()
                action()
            }
        }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(iconColor)
        }
        circle.addView(icon, FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        col.addView(circle, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 9.5f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.ink_soft))
            setPadding(0, dp(4), 0, 0)
        })
        parent.addView(
            col,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun voicePresetLabelRes(preset: VoicePreset): Int = when (preset) {
        VoicePreset.NORMAL -> R.string.fx_preset_normal
        VoicePreset.CLEAN -> R.string.fx_preset_clean
        VoicePreset.BASS -> R.string.fx_preset_bass
        VoicePreset.ECHO -> R.string.fx_preset_echo
        VoicePreset.REVERB -> R.string.fx_preset_reverb
        VoicePreset.ALIEN -> R.string.fx_preset_alien
        VoicePreset.ROBOT -> R.string.fx_preset_robot
        VoicePreset.CHIPMUNK -> R.string.fx_preset_chipmunk
    }

    private fun openPanel() {
        wakeBubble()
        val recording = RecorderState.isRecording
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            elevation = dp(8).toFloat()
            val p = dp(14)
            setPadding(p, p, p, p)
        }

        // --- header ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    if (recording) color(R.color.brand) else color(R.color.faint)
                )
            }
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)))
        val title = label(
            if (recording) {
                "${getString(R.string.notif_recording)} ${Util.formatDuration(RecorderState.elapsedMs())}"
            } else {
                getString(R.string.app_name)
            },
            14.5f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
        }
        header.addView(title)
        val close = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.bg_soft))
            }
            isClickable = true
            setOnClickListener { closePanel() }
        }
        close.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(color(R.color.ink_soft))
        }, FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
        header.addView(close, LinearLayout.LayoutParams(dp(28), dp(28)))
        root.addView(header)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Status FX dibuat eksplisit agar pengguna tahu apakah perubahan
        // preset sedang live atau perangkat jatuh ke mic standar.
        val currentPreset = VoicePreset.fromId(Prefs.voicePreset)
        val fxStatus = label(
            when {
                recording && RecorderState.fxLive -> getString(
                    R.string.fx_live_format,
                    getString(voicePresetLabelRes(currentPreset)),
                    Prefs.fxIntensity,
                    Prefs.micVolume
                )
                recording -> getString(R.string.fx_applies_next)
                else -> getString(
                    R.string.fx_ready_format,
                    getString(voicePresetLabelRes(currentPreset)),
                    Prefs.fxIntensity,
                    Prefs.micVolume
                )
            },
            11.5f,
            if (recording && RecorderState.fxLive) R.color.accent_green else R.color.ink_soft
        ).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(
                    color(
                        if (recording && RecorderState.fxLive) {
                            R.color.accent_green_tint
                        } else {
                            R.color.bg_soft
                        }
                    )
                )
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        body.addView(fxStatus)

        // --- aksi utama ---
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(2))
        }
        if (recording) {
            roundAction(actions, R.drawable.ic_stop, getString(R.string.stop),
                color(R.color.brand), Color.WHITE) {
                sendRecorder(ScreenRecordService.ACTION_STOP)
            }
            val paused = RecorderState.state == RecorderState.State.PAUSED
            roundAction(
                actions,
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                getString(if (paused) R.string.resume else R.string.pause),
                color(R.color.bg_soft), color(R.color.ink)
            ) { sendRecorder(ScreenRecordService.ACTION_TOGGLE_PAUSE) }
            roundAction(actions, R.drawable.ic_camera, getString(R.string.facecam),
                color(R.color.accent_blue_tint), color(R.color.accent_blue)) {
                sendRecorder(ScreenRecordService.ACTION_TOGGLE_FACECAM)
            }
            roundAction(actions, R.drawable.ic_shield, getString(R.string.censor_zones),
                color(R.color.accent_violet_tint), color(R.color.accent_violet)) {
                sendRecorder(ScreenRecordService.ACTION_TOGGLE_SHIELD)
            }
        } else {
            roundAction(actions, R.drawable.ic_record, getString(R.string.start_recording),
                color(R.color.brand), Color.WHITE) {
                // Sesi izin masih hidup: rekam langsung dari sini tanpa
                // membuka activity/dialog izin apa pun.
                if (RecorderState.projectionReady) {
                    sendRecorder(ScreenRecordService.ACTION_START)
                } else {
                    openConsent(ConsentActivity.MODE_RECORD)
                }
            }
            roundAction(actions, R.drawable.ic_screenshot, getString(R.string.screenshot),
                color(R.color.accent_blue_tint), color(R.color.accent_blue)) {
                openConsent(ConsentActivity.MODE_SCREENSHOT)
            }
            roundAction(actions, R.drawable.ic_shield, getString(R.string.censor_zones),
                color(R.color.accent_violet_tint), color(R.color.accent_violet)) {
                openActivity(Intent(this, CensorEditorActivity::class.java))
            }
            roundAction(actions, R.drawable.ic_grid, getString(R.string.open_app),
                color(R.color.bg_soft), color(R.color.ink)) {
                openActivity(Intent(this, MainActivity::class.java))
            }
        }
        body.addView(actions)

        val actions2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        roundAction(actions2, R.drawable.ic_pen, getString(R.string.draw_on_screen),
            color(R.color.accent_amber_tint), color(R.color.accent_amber)) {
            try {
                startService(Intent(this, DrawOverlayService::class.java))
            } catch (e: Exception) {
                toast(R.string.overlay_needed)
            }
        }
        if (recording) {
            roundAction(actions2, R.drawable.ic_grid, getString(R.string.open_app),
                color(R.color.bg_soft), color(R.color.ink)) {
                openActivity(Intent(this, MainActivity::class.java))
            }
        } else {
            roundAction(actions2, R.drawable.ic_gallery, getString(R.string.gallery),
                color(R.color.accent_green_tint), color(R.color.accent_green)) {
                openActivity(Intent(this, GalleryActivity::class.java))
            }
        }
        roundAction(actions2, R.drawable.ic_sliders, getString(R.string.settings),
            color(R.color.accent_slate_tint), color(R.color.accent_slate)) {
            openActivity(Intent(this, SettingsActivity::class.java))
        }
        roundAction(actions2, R.drawable.ic_wave, getString(R.string.hide_bubble),
            color(R.color.bg_soft), color(R.color.ink)) { stopSelf() }
        body.addView(actions2)

        // --- pembagi ---
        body.addView(View(this).apply {
            setBackgroundColor(color(R.color.line))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(12); bottomMargin = dp(12) }
        })

        // --- Deck Efek Suara ---
        body.addView(label(getString(R.string.fx_deck_title), 13f))
        val chips = mutableMapOf<VoicePreset, TextView>()
        fun refreshChips() {
            val current = VoicePreset.fromId(Prefs.voicePreset)
            for ((preset, chip) in chips) {
                val selected = preset == current
                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(if (selected) color(R.color.brand) else color(R.color.bg_soft))
                    if (!selected) setStroke(dp(1), color(R.color.line))
                }
                chip.setTextColor(if (selected) Color.WHITE else color(R.color.ink))
            }
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        val presetLabels = listOf(
            VoicePreset.NORMAL to R.string.fx_preset_normal,
            VoicePreset.CLEAN to R.string.fx_preset_clean,
            VoicePreset.BASS to R.string.fx_preset_bass,
            VoicePreset.ECHO to R.string.fx_preset_echo,
            VoicePreset.REVERB to R.string.fx_preset_reverb,
            VoicePreset.ALIEN to R.string.fx_preset_alien,
            VoicePreset.ROBOT to R.string.fx_preset_robot,
            VoicePreset.CHIPMUNK to R.string.fx_preset_chipmunk
        )
        for ((preset, labelRes) in presetLabels) {
            val chip = TextView(this).apply {
                text = getString(labelRes)
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val px = dp(13)
                val py = dp(7)
                setPadding(px, py, px, py)
                isClickable = true
                setOnClickListener {
                    VoiceFxState.applyPreset(preset)
                    refreshChips()
                    if (RecorderState.isRecording && !RecorderState.fxLive) {
                        toast(R.string.fx_applies_next)
                    }
                }
            }
            chips[preset] = chip
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(7) }
            )
        }
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        })
        refreshChips()

        addDeckSlider(body, getString(R.string.fx_intensity), 0, 100, Prefs.fxIntensity,
            format = { getString(R.string.mic_volume_format, it) }) { v ->
            VoiceFxState.applyIntensityPercent(v)
        }
        addDeckSlider(body, getString(R.string.mic_volume), 10, 200, Prefs.micVolume,
            format = { getString(R.string.mic_volume_format, it) }) { v ->
            VoiceFxState.applyMicVolumePercent(v)
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = try {
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        } catch (e: Exception) {
            15
        }
        val curVol = try {
            am.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            0
        }
        addDeckSlider(body, getString(R.string.device_volume), 0, maxVol, curVol,
            format = { getString(R.string.mic_volume_format, it * 100 / maxVol) }) { v ->
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            } catch (e: Exception) {
            }
        }

        // Badan bisa discroll pada layar pendek (landscape).
        val display = Util.realDisplaySize(this)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Ukur dulu untuk memposisikan panel rapat ke sisi bola.
        val panelW = dp(292)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(display.height, View.MeasureSpec.AT_MOST)
        )
        val panelH = minOf(root.measuredHeight, (display.height * 0.8f).toInt())
        // Kunci tinggi area scroll: padding kartu (2x14dp) + header (28dp)
        // sudah termasuk di panelH, sisanya untuk badan yang bisa discroll.
        val scrollH = (panelH - dp(56)).coerceAtLeast(dp(160))
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, scrollH
        )
        val bp = bubbleParams
        val bubbleLeft = bp?.x ?: 0
        val bubbleCx = bubbleLeft + bubbleSizePx / 2
        val panelX = if (bubbleCx < display.width / 2) {
            bubbleLeft + bubbleSizePx + dp(6)
        } else {
            bubbleLeft - panelW - dp(6)
        }.coerceIn(dp(6), (display.width - panelW - dp(6)).coerceAtLeast(dp(6)))
        val wantY = (bp?.y ?: dp(120)) - dp(30)
        val panelY = wantY.coerceIn(minY(), (display.height - panelH - dp(10)).coerceAtLeast(minY()))
        val lp = OverlayUtil.params(
            panelW, WindowManager.LayoutParams.WRAP_CONTENT,
            x = panelX, y = panelY, gravity = Gravity.TOP or Gravity.START
        )
        try {
            wm.addView(root, lp)
            panel = root
            root.alpha = 0f
            root.scaleX = 0.92f
            root.scaleY = 0.92f
            root.pivotX = if (bubbleCx < display.width / 2) 0f else panelW.toFloat()
            root.pivotY = ((bp?.y ?: panelY) + bubbleSizePx / 2 - panelY)
                .coerceIn(0, panelH).toFloat()
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        } catch (e: Exception) {
        }
    }

    private fun addDeckSlider(
        parent: LinearLayout, title: String, min: Int, max: Int, initial: Int,
        format: (Int) -> String, onChange: (Int) -> Unit
    ) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        head.addView(label(title, 12.5f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueTv = TextView(this).apply {
            text = format(initial)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(R.color.brand_deep))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(color(R.color.brand_tint))
            }
            val ph = dp(8)
            setPadding(ph, dp(3), ph, dp(3))
        }
        head.addView(valueTv)
        parent.addView(head)
        val slider = RekSlider(this).apply {
            minValue = min
            maxValue = max
            setValueSilently(initial)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)
            )
            onValueChange = { v, _ ->
                valueTv.text = format(v)
                onChange(v)
            }
        }
        parent.addView(slider)
    }

    // ---------------------------------------------------------------- aksi

    private fun sendRecorder(action: String) {
        try {
            startService(Intent(this, ScreenRecordService::class.java).setAction(action))
        } catch (e: Exception) {
            toast(R.string.error_start_failed)
        }
    }

    private fun openConsent(mode: String) {
        openActivity(
            Intent(this, ConsentActivity::class.java)
                .putExtra(ConsentActivity.EXTRA_MODE, mode)
        )
    }

    private fun openActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.error_start_failed)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        @Volatile
        var running: Boolean = false
    }
}
