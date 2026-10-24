package id.rekaminaja.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import id.rekaminaja.app.data.CensorZone
import id.rekaminaja.app.ui.RekPillButton
import id.rekaminaja.app.ui.RekTopBar
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.util.Util
import kotlin.math.roundToInt

/**
 * Editor Zona Sensor: atur kotak hitam pada "peta layar".
 * Zona disimpan sebagai koordinat relatif, lalu digambar sebagai overlay
 * hitam pekat selama merekam — konten di baliknya tersensor permanen di video.
 */
class CensorEditorActivity : AppCompatActivity() {

    private lateinit var preview: FrameLayout
    private val zoneViews = mutableListOf<FrameLayout>()
    private var selected: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_censor_editor)
        findViewById<RekTopBar>(R.id.topBar).onBackClick = { finish() }

        preview = findViewById(R.id.previewArea)
        findViewById<RekPillButton>(R.id.btnAddZone).setOnClickListener {
            addZoneView(CensorZone(0.2f, 0.4f, 0.8f, 0.52f), select = true)
        }
        findViewById<RekPillButton>(R.id.btnDeleteZone).setOnClickListener {
            selected?.let {
                preview.removeView(it)
                zoneViews.remove(it)
                selected = null
            } ?: Toast.makeText(this, R.string.select_zone_first, Toast.LENGTH_SHORT).show()
        }
        findViewById<RekPillButton>(R.id.btnSaveZones).setOnClickListener {
            save()
            Toast.makeText(this, R.string.zones_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        preview.post {
            for (z in Prefs.censorZones) addZoneView(z, select = false)
            if (zoneViews.isNotEmpty()) select(zoneViews[0])
        }
    }

    private fun zoneBackground(isSelected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.argb(215, 0, 0, 0))
            cornerRadius = Util.dp(this@CensorEditorActivity, 6).toFloat()
            setStroke(
                Util.dp(this@CensorEditorActivity, if (isSelected) 3 else 1),
                if (isSelected) ContextCompat.getColor(this@CensorEditorActivity, R.color.brand)
                else Color.WHITE
            )
        }

    private fun select(view: FrameLayout?) {
        selected = view
        for (z in zoneViews) z.background = zoneBackground(z === view)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addZoneView(zone: CensorZone, select: Boolean) {
        val w = preview.width
        val h = preview.height
        if (w <= 0 || h <= 0) return
        val view = FrameLayout(this).apply {
            background = zoneBackground(false)
        }
        val label = TextView(this).apply {
            text = getString(R.string.censored_label)
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        view.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val handleSize = Util.dp(this, 30)
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        view.addView(
            handle,
            FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.END)
        )

        val lp = FrameLayout.LayoutParams(
            ((zone.right - zone.left) * w).roundToInt().coerceAtLeast(Util.dp(this, 40)),
            ((zone.bottom - zone.top) * h).roundToInt().coerceAtLeast(Util.dp(this, 28))
        )
        lp.leftMargin = (zone.left * w).roundToInt()
        lp.topMargin = (zone.top * h).roundToInt()
        preview.addView(view, lp)
        zoneViews.add(view)

        // Seret untuk memindah zona.
        label.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startL = 0
            private var startT = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = view.layoutParams as FrameLayout.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        select(view)
                        downX = event.rawX; downY = event.rawY
                        startL = p.leftMargin; startT = p.topMargin
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.leftMargin = (startL + (event.rawX - downX))
                            .coerceIn(0f, (preview.width - view.width).toFloat()).toInt()
                        p.topMargin = (startT + (event.rawY - downY))
                            .coerceIn(0f, (preview.height - view.height).toFloat()).toInt()
                        view.layoutParams = p
                    }
                }
                return true
            }
        })
        // Gagang pojok untuk mengubah ukuran.
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startW = 0
            private var startH = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = view.layoutParams as FrameLayout.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        select(view)
                        downX = event.rawX; downY = event.rawY
                        startW = view.width; startH = view.height
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.width = (startW + (event.rawX - downX))
                            .coerceIn(Util.dp(this@CensorEditorActivity, 40).toFloat(),
                                (preview.width - p.leftMargin).toFloat()).toInt()
                        p.height = (startH + (event.rawY - downY))
                            .coerceIn(Util.dp(this@CensorEditorActivity, 28).toFloat(),
                                (preview.height - p.topMargin).toFloat()).toInt()
                        view.layoutParams = p
                    }
                }
                return true
            }
        })
        if (select) select(view)
    }

    private fun save() {
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w <= 0 || h <= 0) return
        val zones = zoneViews.map { v ->
            val p = v.layoutParams as FrameLayout.LayoutParams
            val l = (p.leftMargin / w).coerceIn(0f, 1f)
            val t = (p.topMargin / h).coerceIn(0f, 1f)
            val r = ((p.leftMargin + v.width) / w).coerceIn(l, 1f)
            val b = ((p.topMargin + v.height) / h).coerceIn(t, 1f)
            CensorZone(l, t, r, b)
        }
        Prefs.censorZones = zones
        if (zones.isNotEmpty()) Prefs.censorEnabled = true
    }
}
