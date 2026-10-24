package id.rekaminaja.app.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import id.rekaminaja.app.CensorEditorActivity
import id.rekaminaja.app.ConsentActivity
import id.rekaminaja.app.MainActivity
import id.rekaminaja.app.R
import id.rekaminaja.app.recorder.RecorderState
import id.rekaminaja.app.recorder.ScreenRecordService
import id.rekaminaja.app.util.Util
import kotlin.math.abs

/** Bola melayang + panel kontrol ala XRecorder. */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: LinearLayout? = null
    private var bubbleIcon: ImageView? = null
    private var bubbleTimer: TextView? = null
    private var panel: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())

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
        createBubble()
        running = true
        handler.post(updater)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeView(bubble)
        removeView(panel)
        bubble = null
        panel = null
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

    private fun createBubble() {
        val size = Util.dp(this, 58)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_idle))
            }
            val pad = Util.dp(this@FloatingBubbleService, 6)
            setPadding(pad, pad, pad, pad)
        }
        bubbleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_record)
            setColorFilter(Color.WHITE)
        }
        root.addView(
            bubbleIcon,
            LinearLayout.LayoutParams(Util.dp(this, 26), Util.dp(this, 26))
        )
        bubbleTimer = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 9f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(bubbleTimer)
        val lp = OverlayUtil.params(
            size, size, x = Util.dp(this, 8), y = Util.dp(this, 180),
            gravity = Gravity.TOP or Gravity.END
        )
        attachDrag(root, lp)
        try {
            wm.addView(root, lp)
            bubble = root
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y
                        touchX = event.rawX; touchY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        lp.x = startX - dx // gravity END
                        lp.y = startY + dy
                        try {
                            wm.updateViewLayout(view, lp)
                        } catch (e: Exception) {
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun updateBubbleVisual() {
        val b = bubble ?: return
        when (RecorderState.state) {
            RecorderState.State.RECORDING -> {
                (b.background as? GradientDrawable)?.setColor(
                    ContextCompat.getColor(this, R.color.bubble_recording)
                )
                bubbleTimer?.visibility = View.VISIBLE
                bubbleTimer?.text = Util.formatDuration(RecorderState.elapsedMs())
            }
            RecorderState.State.PAUSED -> {
                (b.background as? GradientDrawable)?.setColor(
                    ContextCompat.getColor(this, R.color.bubble_paused)
                )
                bubbleTimer?.visibility = View.VISIBLE
                bubbleTimer?.text = Util.formatDuration(RecorderState.elapsedMs())
            }
            else -> {
                (b.background as? GradientDrawable)?.setColor(
                    ContextCompat.getColor(this, R.color.bubble_idle)
                )
                bubbleTimer?.visibility = View.GONE
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

    private fun openPanel() {
        val state = RecorderState.state
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Util.dp(this@FloatingBubbleService, 14)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = Util.dp(this@FloatingBubbleService, 18).toFloat()
                setColor(Color.argb(242, 30, 30, 34))
            }
        }
        val title = TextView(this).apply {
            text = if (RecorderState.isRecording) {
                "${getString(R.string.notif_recording)} ${Util.formatDuration(RecorderState.elapsedMs())}"
            } else {
                getString(R.string.app_name)
            }
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 0, 0, Util.dp(this@FloatingBubbleService, 8))
        }
        root.addView(title)

        fun btn(label: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    closePanel()
                    action()
                }
            }
            root.addView(
                b,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        if (RecorderState.isRecording) {
            btn(getString(R.string.stop)) {
                sendRecorder(ScreenRecordService.ACTION_STOP)
            }
            btn(
                if (state == RecorderState.State.PAUSED) getString(R.string.resume)
                else getString(R.string.pause)
            ) { sendRecorder(ScreenRecordService.ACTION_TOGGLE_PAUSE) }
            btn(getString(R.string.facecam)) {
                sendRecorder(ScreenRecordService.ACTION_TOGGLE_FACECAM)
            }
            btn(getString(R.string.censor_zones)) {
                sendRecorder(ScreenRecordService.ACTION_TOGGLE_SHIELD)
            }
        } else {
            btn(getString(R.string.start_recording)) { openConsent(ConsentActivity.MODE_RECORD) }
            btn(getString(R.string.screenshot)) { openConsent(ConsentActivity.MODE_SCREENSHOT) }
            btn(getString(R.string.censor_zones)) {
                openActivity(Intent(this, CensorEditorActivity::class.java))
            }
        }
        btn(getString(R.string.draw_on_screen)) {
            try {
                startService(Intent(this, DrawOverlayService::class.java))
            } catch (e: Exception) {
                toast(R.string.overlay_needed)
            }
        }
        btn(getString(R.string.open_app)) {
            openActivity(Intent(this, MainActivity::class.java))
        }
        btn(getString(R.string.hide_bubble)) { stopSelf() }
        btn(getString(R.string.close)) { }

        val lp = OverlayUtil.params(
            Util.dp(this, 250), WindowManager.LayoutParams.WRAP_CONTENT,
            gravity = Gravity.CENTER
        )
        try {
            wm.addView(root, lp)
            panel = root
        } catch (e: Exception) {
        }
    }

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
