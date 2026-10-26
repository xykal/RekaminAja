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
import id.rekaminaja.app.MainActivity
import id.rekaminaja.app.R
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
 * Bola melayang RekaminAja ala XRecorder:
 * - kecil (38dp) dan selalu menempel di tepi kiri/kanan (snap beranimasi),
 * - seret ke lingkaran hapus di bawah-tengah untuk menyembunyikan bola,
 * - panel deck berisi aksi bulat, Deck Efek Suara (preset + intensitas),
 *   serta pengatur volume mic dan volume HP.
 *
 * Posisi terakhir (sisi + Y) diingat antar sesi lewat [Prefs].
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleIcon: ImageView? = null
    private var bubbleTimer: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var removeTarget: LinearLayout? = null
    private var removeCircle: FrameLayout? = null
    private var snapAnimator: ValueAnimator? = null
    private var pulseOn = false

    private val handler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { bubble?.animate()?.alpha(0.55f)?.setDuration(250)?.start() }

    private val updater = object : Runnable {
        override fun run() {
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
        VoiceFxState.sync(Prefs.voicePreset, Prefs.fxIntensity, Prefs.micVolume)
        createBubble()
        running = true
        handler.post(updater)
        scheduleDim()
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
        val size = dp(38)
        bubbleBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.WHITE, color(R.color.bg_soft))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), color(R.color.line))
        }
        val root = FrameLayout(this).apply {
            background = bubbleBg
            elevation = dp(5).toFloat()
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        bubbleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_record)
            setColorFilter(color(R.color.brand))
        }
        inner.addView(bubbleIcon, LinearLayout.LayoutParams(dp(15), dp(15)))
        bubbleTimer = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 6.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        inner.addView(bubbleTimer)
        root.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val display = Util.realDisplaySize(this)
        val startX = if (Prefs.bubbleSide == 0) 0 else display.width - size
        val startY = if (Prefs.bubbleY >= 0) {
            Prefs.bubbleY.coerceIn(minY(), maxY(size))
        } else {
            (display.height * 0.38f).toInt()
        }
        val lp = OverlayUtil.params(
            size, size, x = startX, y = startY,
            gravity = Gravity.TOP or Gravity.START
        )
        bubbleParams = lp
        attachDrag(root, lp, size)
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

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, 2_500)
    }

    private fun wakeBubble() {
        bubble?.animate()?.alpha(1f)?.setDuration(150)?.start()
        scheduleDim()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, lp: WindowManager.LayoutParams, size: Int) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            val display = Util.realDisplaySize(this)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = lp.x; startY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    wakeBubble()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        if (!moved) {
                            closePanel()
                            showRemoveTarget()
                        }
                        moved = true
                    }
                    if (moved) {
                        lp.x = (startX + dx).coerceIn(0, display.width - size)
                        lp.y = (startY + dy).coerceIn(minY(), maxY(size))
                        try {
                            wm.updateViewLayout(view, lp)
                        } catch (e: Exception) {
                        }
                        updateRemoveHover(lp, size)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val overRemove = moved && isOverRemoveTarget(lp, size)
                    hideRemoveTarget()
                    if (!moved) {
                        togglePanel()
                    } else if (overRemove) {
                        stopSelf()
                    } else {
                        snapToEdge(lp, size)
                    }
                    scheduleDim()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(lp: WindowManager.LayoutParams, size: Int) {
        val display = Util.realDisplaySize(this)
        val toLeft = (lp.x + size / 2) < display.width / 2
        val targetX = if (toLeft) 0 else display.width - size
        Prefs.bubbleSide = if (toLeft) 0 else 1
        Prefs.bubbleY = lp.y
        val fromX = lp.x
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 190
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                try {
                    bubble?.let { wm.updateViewLayout(it, lp) }
                } catch (e: Exception) {
                }
            }
            start()
        }
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
    private fun applyBubbleGradient(from: Int, to: Int) {
        bubbleBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), color(R.color.line))
        }
        bubble?.background = bubbleBg
    }

    private fun updateBubbleVisual() {
        val state = RecorderState.state
        if (state != lastVisualState) {
            lastVisualState = state
            when (state) {
                RecorderState.State.RECORDING ->
                    applyBubbleGradient(color(R.color.brand), color(R.color.brand_deep))
                RecorderState.State.PAUSED ->
                    applyBubbleGradient(Color.rgb(0xF5, 0xA6, 0x23), color(R.color.bubble_paused))
                else -> applyBubbleGradient(Color.WHITE, color(R.color.bg_soft))
            }
        }
        when (state) {
            RecorderState.State.RECORDING -> {
                bubbleIcon?.setColorFilter(Color.WHITE)
                bubbleTimer?.visibility = View.VISIBLE
                bubbleTimer?.text = Util.formatDuration(RecorderState.elapsedMs())
                pulseOn = !pulseOn
                val s = if (pulseOn) 1.14f else 1f
                bubbleIcon?.animate()?.scaleX(s)?.scaleY(s)?.setDuration(450)?.start()
            }
            RecorderState.State.PAUSED -> {
                bubbleIcon?.setColorFilter(Color.WHITE)
                bubbleTimer?.visibility = View.VISIBLE
                bubbleTimer?.text = Util.formatDuration(RecorderState.elapsedMs())
            }
            else -> {
                bubbleIcon?.setColorFilter(color(R.color.brand))
                bubbleTimer?.visibility = View.GONE
                bubbleIcon?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
            }
        }
    }

    // ---------------------------------------------------------------- panel

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun closePanel() {
        removeView(panel)
        panel = null
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

    private fun openPanel() {
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
        roundAction(actions2, R.drawable.ic_wave, getString(R.string.hide_bubble),
            color(R.color.bg_soft), color(R.color.ink)) { stopSelf() }
        // Dua slot kosong agar lebar kolom konsisten.
        repeat(2) {
            actions2.addView(
                View(this),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
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
                    Prefs.voicePreset = preset.id
                    VoiceFxState.preset = preset
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
            Prefs.fxIntensity = v
            VoiceFxState.intensity = v / 100f
        }
        addDeckSlider(body, getString(R.string.mic_volume), 10, 200, Prefs.micVolume,
            format = { getString(R.string.mic_volume_format, it) }) { v ->
            Prefs.micVolume = v
            VoiceFxState.micGain = v / 100f
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
        val bubbleCx = (bp?.x ?: 0) + dp(19)
        val panelX = if (bubbleCx < display.width / 2) {
            dp(8)
        } else {
            display.width - panelW - dp(8)
        }
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
