package id.rekaminaja.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.ui.RekPillButton
import id.rekaminaja.app.ui.RekToggle
import id.rekaminaja.app.ui.RekTopBar
import id.rekaminaja.app.util.Util
import java.util.concurrent.Executors

data class AppChoice(val pkg: String, val label: String)

/**
 * Pilih aplikasi sensitif (m-banking, e-wallet, chat pribadi…).
 * Saat app terpilih tampil di latar depan, RekaminAja otomatis JEDA + memasang
 * pelindung layar penuh, lalu lanjut sendiri ketika app tersebut ditinggalkan.
 */
class SensitiveAppsActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var adapter: AppsAdapter
    private lateinit var usageStatus: TextView
    private var allApps: List<AppChoice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensitive_apps)
        findViewById<RekTopBar>(R.id.topBar).onBackClick = { finish() }

        usageStatus = findViewById(R.id.usageStatus)
        findViewById<RekPillButton>(R.id.btnUsageAccess).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        adapter = AppsAdapter()
        val rv = findViewById<RecyclerView>(R.id.appsList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<EditText>(R.id.searchBox).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    filter(s?.toString() ?: "")
                }

                override fun afterTextChanged(s: Editable?) = Unit
            }
        )

        executor.execute {
            val apps = loadLaunchableApps()
            runOnUiThread {
                allApps = apps
                adapter.submit(apps)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        usageStatus.text = getString(
            if (Util.hasUsageAccess(this)) R.string.usage_access_on else R.string.usage_access_off
        )
    }

    private fun filter(q: String) {
        if (q.isBlank()) adapter.submit(allApps)
        else adapter.submit(allApps.filter { it.label.contains(q, ignoreCase = true) })
    }

    private fun loadLaunchableApps(): List<AppChoice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val seen = HashSet<String>()
        val list = mutableListOf<AppChoice>()
        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            val label = try {
                pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString()
            } catch (e: Exception) {
                pkg
            }
            list.add(AppChoice(pkg, label))
        }
        return list.sortedBy { it.label.lowercase() }
    }

    inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.VH>() {
        private var data: List<AppChoice> = emptyList()

        fun submit(list: List<AppChoice>) {
            data = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_choice, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = data[position]
            holder.label.text = app.label
            holder.pkg.text = app.pkg
            try {
                holder.icon.setImageDrawable(packageManager.getApplicationIcon(app.pkg))
            } catch (e: Exception) {
                holder.icon.setImageResource(R.drawable.ic_gallery)
            }
            holder.check.onCheckedChange = null
            holder.check.setCheckedSilently(Prefs.sensitivePackages.contains(app.pkg))
            holder.check.onCheckedChange = { checked ->
                val set = Prefs.sensitivePackages.toMutableSet()
                if (checked) set.add(app.pkg) else set.remove(app.pkg)
                Prefs.sensitivePackages = set
            }
            holder.itemView.setOnClickListener { holder.check.toggle() }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val label: TextView = view.findViewById(R.id.appLabel)
            val pkg: TextView = view.findViewById(R.id.appPkg)
            val check: RekToggle = view.findViewById(R.id.appCheck)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
