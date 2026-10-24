package id.rekaminaja.app.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import id.rekaminaja.app.data.CensorZone
import id.rekaminaja.app.util.Util
import kotlin.math.roundToInt

/**
 * "Auto Sensor" RekaminAja: kotak hitam opaque yang digambar sebagai overlay.
 * Karena jendela overlay ikut tertangkap MediaProjection, area di balik kotak
 * benar-benar tersensor di file video — tanpa perlu pipeline OpenGL.
 */
class PrivacyShieldController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val zoneViews = mutableListOf<View>()
    private var fullShield: View? = null

    fun showZones(zones: List<CensorZone>) {
        hideZones()
        if (!Util.canOverlay(context)) return
        val display = Util.realDisplaySize(context)
        for (z in zones) {
            val w = ((z.right - z.left) * display.width).roundToInt().coerceAtLeast(1)
            val h = ((z.bottom - z.top) * display.height).roundToInt().coerceAtLeast(1)
            val v = View(context).apply { setBackgroundColor(Color.BLACK) }
            val lp = OverlayUtil.params(
                w, h,
                x = (z.left * display.width).roundToInt(),
                y = (z.top * display.height).roundToInt(),
                gravity = Gravity.TOP or Gravity.START,
                touchable = false
            )
            try {
                wm.addView(v, lp)
                zoneViews.add(v)
            } catch (e: Exception) {
                // Izin overlay dicabut di tengah jalan — abaikan.
            }
        }
    }

    fun hideZones() {
        for (v in zoneViews) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        zoneViews.clear()
    }

    /** Pelindung layar penuh saat app sensitif terdeteksi di latar depan. */
    fun showFullShield(text: String) {
        if (fullShield != null || !Util.canOverlay(context)) return
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            textSize = 18f
        }
        val lp = OverlayUtil.params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            gravity = Gravity.CENTER, touchable = false
        )
        try {
            wm.addView(tv, lp)
            fullShield = tv
        } catch (e: Exception) {
        }
    }

    fun hideFullShield() {
        fullShield?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        fullShield = null
    }

    fun hideAll() {
        hideZones()
        hideFullShield()
    }
}
