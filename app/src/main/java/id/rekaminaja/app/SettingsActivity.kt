package id.rekaminaja.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import id.rekaminaja.app.data.Prefs

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        // --- Resolusi ---
        bindSpinner(
            findViewById(R.id.spinnerResolution),
            Prefs.RESOLUTION_OPTIONS.map {
                if (it == 0) getString(R.string.res_native) else "${it}p"
            },
            Prefs.RESOLUTION_OPTIONS.indexOf(Prefs.resolution).coerceAtLeast(0)
        ) { Prefs.resolution = Prefs.RESOLUTION_OPTIONS[it] }

        // --- FPS ---
        bindSpinner(
            findViewById(R.id.spinnerFps),
            Prefs.FPS_OPTIONS.map { "$it FPS" },
            Prefs.FPS_OPTIONS.indexOf(Prefs.fps).coerceAtLeast(0)
        ) { Prefs.fps = Prefs.FPS_OPTIONS[it] }

        // --- Bitrate ---
        val bitrates = intArrayOf(0, 2, 4, 6, 8, 12, 16, 20, 24)
        bindSpinner(
            findViewById(R.id.spinnerBitrate),
            bitrates.map { if (it == 0) getString(R.string.bitrate_auto) else "$it Mbps" },
            bitrates.indexOf(Prefs.bitrateMbps).coerceAtLeast(0)
        ) { Prefs.bitrateMbps = bitrates[it] }

        // --- Audio ---
        val audioValues = listOf(
            Prefs.AUDIO_MIC, Prefs.AUDIO_INTERNAL, Prefs.AUDIO_MIX, Prefs.AUDIO_NONE
        )
        bindSpinner(
            findViewById(R.id.spinnerAudio),
            listOf(
                getString(R.string.audio_mic),
                getString(R.string.audio_internal),
                getString(R.string.audio_mix),
                getString(R.string.audio_none)
            ),
            audioValues.indexOf(Prefs.audioSource).coerceAtLeast(0)
        ) { Prefs.audioSource = audioValues[it] }
        findViewById<TextView>(R.id.tvAudioNote).visibility =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) View.VISIBLE else View.GONE

        // --- Hitung mundur ---
        val countdowns = intArrayOf(0, 3, 5, 10)
        bindSpinner(
            findViewById(R.id.spinnerCountdown),
            countdowns.map { "$it s" },
            countdowns.indexOf(Prefs.countdownSec).coerceAtLeast(0)
        ) { Prefs.countdownSec = countdowns[it] }

        // --- Sakelar ---
        bindSwitch(R.id.switchBubble, Prefs.floatingBubble) { Prefs.floatingBubble = it }
        bindSwitch(R.id.switchFacecam, Prefs.facecamEnabled) { Prefs.facecamEnabled = it }
        bindSwitch(R.id.switchFrontCamera, Prefs.facecamFront) { Prefs.facecamFront = it }
        bindSwitch(R.id.switchCensor, Prefs.censorEnabled) { Prefs.censorEnabled = it }
        bindSwitch(R.id.switchAutopause, Prefs.autoPauseSensitive) { Prefs.autoPauseSensitive = it }

        // --- Ukuran facecam ---
        val seek = findViewById<SeekBar>(R.id.seekFacecamSize)
        val sizeLabel = findViewById<TextView>(R.id.tvFacecamSize)
        seek.max = 100
        seek.progress = (Prefs.facecamSizeDp - 80).coerceIn(0, 100)
        sizeLabel.text = "${Prefs.facecamSizeDp} dp"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 80 + progress
                Prefs.facecamSizeDp = v
                sizeLabel.text = "$v dp"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        findViewById<Button>(R.id.btnOpenCensor).setOnClickListener {
            startActivity(Intent(this, CensorEditorActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenSensitive).setOnClickListener {
            startActivity(Intent(this, SensitiveAppsActivity::class.java))
        }
        setupBatteryButton()
        findViewById<TextView>(R.id.tvAbout).text =
            getString(R.string.about_format, versionName())
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindSpinner(
        spinner: Spinner, labels: List<String>, selected: Int, onSelect: (Int) -> Unit
    ) {
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                onSelect(pos)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<SwitchCompat>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun setupBatteryButton() {
        val btn = findViewById<Button>(R.id.btnBattery)
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            pm.isIgnoringBatteryOptimizations(packageName)
        if (ignoring) {
            btn.setText(R.string.battery_ok)
            btn.isEnabled = false
            return
        }
        btn.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun versionName(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
}
