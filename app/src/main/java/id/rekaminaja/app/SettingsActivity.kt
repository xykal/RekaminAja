package id.rekaminaja.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import id.rekaminaja.app.data.Prefs
import id.rekaminaja.app.ui.RekChoiceView
import id.rekaminaja.app.ui.RekDialogs
import id.rekaminaja.app.ui.RekPillButton
import id.rekaminaja.app.ui.RekSlider
import id.rekaminaja.app.ui.RekToggle
import id.rekaminaja.app.ui.RekTopBar
import id.rekaminaja.app.util.Util

/**
 * Pengaturan full custom: kartu seksi putih, baris pilihan RekChoiceView,
 * saklar RekToggle, slider RekSlider, dan dialog RekDialogs.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<RekTopBar>(R.id.topBar).onBackClick = { finish() }
        container = findViewById(R.id.settingsContainer)
        buildUi()
    }

    private fun buildUi() {
        // ---- Video ----
        val video = section(R.string.video_section)
        addChoice(
            video, R.string.resolution,
            Prefs.RESOLUTION_OPTIONS.map {
                if (it == 0) getString(R.string.res_native) else "${it}p"
            },
            Prefs.RESOLUTION_OPTIONS.indexOf(Prefs.resolution).coerceAtLeast(0)
        ) { Prefs.resolution = Prefs.RESOLUTION_OPTIONS[it] }
        addChoice(
            video, R.string.fps,
            Prefs.FPS_OPTIONS.map { "$it FPS" },
            Prefs.FPS_OPTIONS.indexOf(Prefs.fps).coerceAtLeast(0)
        ) { Prefs.fps = Prefs.FPS_OPTIONS[it] }
        val bitrates = intArrayOf(0, 2, 4, 6, 8, 12, 16, 20, 24)
        addChoice(
            video, R.string.bitrate,
            bitrates.map { if (it == 0) getString(R.string.bitrate_auto) else "$it Mbps" },
            bitrates.indexOf(Prefs.bitrateMbps).coerceAtLeast(0)
        ) { Prefs.bitrateMbps = bitrates[it] }

        // ---- Audio ----
        val audio = section(R.string.audio_section)
        val audioValues = listOf(
            Prefs.AUDIO_MIC, Prefs.AUDIO_INTERNAL, Prefs.AUDIO_MIX, Prefs.AUDIO_NONE
        )
        addChoice(
            audio, R.string.audio_source,
            listOf(
                getString(R.string.audio_mic),
                getString(R.string.audio_internal),
                getString(R.string.audio_mix),
                getString(R.string.audio_none)
            ),
            audioValues.indexOf(Prefs.audioSource).coerceAtLeast(0)
        ) { Prefs.audioSource = audioValues[it] }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            addNote(audio, R.string.audio_note_old)
        }

        // ---- Lab Audio (podcast) ----
        val lab = section(R.string.audio_lab)
        addNote(lab, R.string.fx_intro)
        lateinit var tNs: RekToggle
        lateinit var tGate: RekToggle
        lateinit var tComp: RekToggle
        lateinit var gainSlider: RekSlider
        lateinit var gainValue: TextView
        val refreshPresets = addPresetRow(lab) { preset ->
            when (preset) {
                0 -> applyFx(ns = false, gate = false, comp = false, gain = Prefs.fxGainDb,
                    tNs, tGate, tComp, gainSlider, gainValue)
                1 -> applyFx(ns = true, gate = false, comp = false, gain = Prefs.fxGainDb,
                    tNs, tGate, tComp, gainSlider, gainValue)
                2 -> applyFx(ns = true, gate = true, comp = true, gain = 4,
                    tNs, tGate, tComp, gainSlider, gainValue)
                3 -> applyFx(ns = true, gate = true, comp = true, gain = 7,
                    tNs, tGate, tComp, gainSlider, gainValue)
            }
        }
        tNs = addToggle(lab, R.string.fx_noise_system, R.string.fx_noise_system_desc,
            Prefs.fxNoiseSystem) {
            Prefs.fxNoiseSystem = it
            refreshPresets(currentPresetIndex())
        }
        tGate = addToggle(lab, R.string.fx_gate, R.string.fx_gate_desc,
            Prefs.fxGate) {
            Prefs.fxGate = it
            refreshPresets(currentPresetIndex())
        }
        tComp = addToggle(lab, R.string.fx_compressor, R.string.fx_compressor_desc,
            Prefs.fxCompressor) {
            Prefs.fxCompressor = it
            refreshPresets(currentPresetIndex())
        }
        val gainViews = addSlider(
            lab, R.string.fx_gain, 0, 12, Prefs.fxGainDb,
            format = { getString(R.string.fx_gain_format, it) }
        ) {
            Prefs.fxGainDb = it
            refreshPresets(currentPresetIndex())
        }
        gainSlider = gainViews.first
        gainValue = gainViews.second

        // Deck Efek Suara v1.2: preset karakter + intensitas + volume mic.
        // Nilai yang sama juga bisa diganti live dari panel bola melayang.
        val voicePresetIds = listOf(
            "normal", "clean", "bass", "echo", "reverb", "alien", "robot", "chipmunk"
        )
        addChoice(
            lab, R.string.voice_preset,
            listOf(
                getString(R.string.fx_preset_normal),
                getString(R.string.fx_preset_clean),
                getString(R.string.fx_preset_bass),
                getString(R.string.fx_preset_echo),
                getString(R.string.fx_preset_reverb),
                getString(R.string.fx_preset_alien),
                getString(R.string.fx_preset_robot),
                getString(R.string.fx_preset_chipmunk)
            ),
            voicePresetIds.indexOf(Prefs.voicePreset).coerceAtLeast(0)
        ) {
            Prefs.voicePreset = voicePresetIds[it]
            id.rekaminaja.app.recorder.VoiceFxState.preset =
                id.rekaminaja.app.recorder.VoicePreset.fromId(voicePresetIds[it])
        }
        addNote(lab, R.string.voice_preset_desc)
        addSlider(lab, R.string.fx_intensity, 0, 100, Prefs.fxIntensity,
            format = { getString(R.string.mic_volume_format, it) }) {
            Prefs.fxIntensity = it
            id.rekaminaja.app.recorder.VoiceFxState.intensity = it / 100f
        }
        addSlider(lab, R.string.mic_volume, 10, 200, Prefs.micVolume,
            format = { getString(R.string.mic_volume_format, it) }) {
            Prefs.micVolume = it
            id.rekaminaja.app.recorder.VoiceFxState.micGain = it / 100f
        }
        addNote(lab, R.string.fx_note_route)

        // ---- Perekaman ----
        val rec = section(R.string.recording_section)
        val countdowns = intArrayOf(0, 3, 5, 10)
        addChoice(
            rec, R.string.countdown,
            countdowns.map { "$it s" },
            countdowns.indexOf(Prefs.countdownSec).coerceAtLeast(0)
        ) { Prefs.countdownSec = countdowns[it] }
        addToggle(rec, R.string.floating_bubble, null, Prefs.floatingBubble) {
            Prefs.floatingBubble = it
        }

        // ---- Facecam ----
        val face = section(R.string.overlay_section)
        addToggle(face, R.string.facecam_default, null, Prefs.facecamEnabled) {
            Prefs.facecamEnabled = it
        }
        addToggle(face, R.string.front_camera, null, Prefs.facecamFront) {
            Prefs.facecamFront = it
        }
        addSlider(face, R.string.facecam_size, 80, 180, Prefs.facecamSizeDp,
            format = { "$it dp" }) { Prefs.facecamSizeDp = it }

        // ---- Privasi ----
        val priv = section(R.string.privacy_section)
        addToggle(priv, R.string.censor_enable, null, Prefs.censorEnabled) {
            Prefs.censorEnabled = it
        }
        addToggle(priv, R.string.autopause_enable, null, Prefs.autoPauseSensitive) {
            Prefs.autoPauseSensitive = it
        }
        addAction(priv, R.string.edit_censor_zones, R.drawable.ic_shield) {
            startActivity(Intent(this, CensorEditorActivity::class.java))
        }
        addAction(priv, R.string.choose_sensitive_apps, R.drawable.ic_lock) {
            startActivity(Intent(this, SensitiveAppsActivity::class.java))
        }

        // ---- Baterai ----
        val batt = section(R.string.battery_section)
        addNote(batt, R.string.battery_desc)
        val btnBattery = RekPillButton(this).apply {
            setVariantKind(RekPillButton.VARIANT_SOFT)
            text = getString(R.string.battery_btn)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(this@SettingsActivity, 10) }
        }
        batt.addView(btnBattery)
        setupBatteryButton(btnBattery)

        // ---- Tentang ----
        val about = TextView(this).apply {
            text = getString(R.string.about_format, versionName())
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.muted))
            setPadding(
                Util.dp(this@SettingsActivity, 4), Util.dp(this@SettingsActivity, 18),
                Util.dp(this@SettingsActivity, 4), 0
            )
        }
        container.addView(about)
    }

    private fun currentPresetIndex(): Int {
        val ns = Prefs.fxNoiseSystem
        val gate = Prefs.fxGate
        val comp = Prefs.fxCompressor
        return when {
            !ns && !gate && !comp -> 0
            ns && !gate && !comp -> 1
            ns && gate && comp && Prefs.fxGainDb == 4 -> 2
            ns && gate && comp && Prefs.fxGainDb == 7 -> 3
            else -> -1
        }
    }

    private fun applyFx(
        ns: Boolean, gate: Boolean, comp: Boolean, gain: Int,
        tNs: RekToggle, tGate: RekToggle, tComp: RekToggle,
        gainSlider: RekSlider, gainValue: TextView
    ) {
        Prefs.fxNoiseSystem = ns
        Prefs.fxGate = gate
        Prefs.fxCompressor = comp
        Prefs.fxGainDb = gain
        tNs.setCheckedSilently(ns)
        tGate.setCheckedSilently(gate)
        tComp.setCheckedSilently(comp)
        gainSlider.setValueSilently(gain)
        gainValue.text = getString(R.string.fx_gain_format, gain)
    }

    // ---------------------------------------------------------------- builder baris

    private fun section(titleRes: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_card)
            elevation = Util.dp(this@SettingsActivity, 1).toFloat()
            val p = Util.dp(this@SettingsActivity, 16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(this@SettingsActivity, 14) }
        }
        val title = TextView(this).apply {
            setText(titleRes)
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.ink))
            setPadding(0, 0, 0, Util.dp(this@SettingsActivity, 8))
        }
        card.addView(title)
        container.addView(card)
        return card
    }

    private fun addChoice(
        card: LinearLayout,
        labelRes: Int,
        labels: List<String>,
        initialIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val choice = RekChoiceView(this).apply {
            setLabel(labelRes)
            valueText = labels[initialIndex]
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(this@SettingsActivity, 8) }
        }
        var current = initialIndex
        choice.setOnClickListener {
            RekDialogs.showChoice(
                this, getString(labelRes), labels, current
            ) { picked ->
                current = picked
                choice.valueText = labels[picked]
                onPick(picked)
            }
        }
        card.addView(choice)
    }

    private fun addToggle(
        card: LinearLayout,
        titleRes: Int,
        descRes: Int?,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): RekToggle {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(
                0, Util.dp(this@SettingsActivity, 10),
                0, Util.dp(this@SettingsActivity, 10)
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(this).apply {
            setText(titleRes)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.ink))
        }
        col.addView(title)
        if (descRes != null) {
            val desc = TextView(this).apply {
                setText(descRes)
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.muted))
            }
            col.addView(desc)
        }
        row.addView(col)
        val toggle = RekToggle(this)
        toggle.setCheckedSilently(initial)
        toggle.onCheckedChange = onChange
        row.addView(toggle)
        row.setOnClickListener { toggle.toggle() }
        card.addView(row)
        return toggle
    }

    private fun addSlider(
        card: LinearLayout,
        titleRes: Int,
        min: Int,
        max: Int,
        initial: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ): Pair<RekSlider, TextView> {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Util.dp(this@SettingsActivity, 8), 0, 0)
        }
        val title = TextView(this).apply {
            setText(titleRes)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.ink))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        head.addView(title)
        val valueTv = TextView(this).apply {
            text = format(initial)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.brand_deep))
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_chip_brand)
            val ph = Util.dp(this@SettingsActivity, 10)
            setPadding(ph, Util.dp(this@SettingsActivity, 4), ph, Util.dp(this@SettingsActivity, 4))
        }
        head.addView(valueTv)
        card.addView(head)

        val slider = RekSlider(this).apply {
            minValue = min
            maxValue = max
            setValueSilently(initial)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            onValueChange = { v, _ ->
                valueTv.text = format(v)
                onChange(v)
            }
        }
        card.addView(slider)
        return slider to valueTv
    }

    private fun addAction(
        card: LinearLayout, titleRes: Int, iconRes: Int, onClick: () -> Unit
    ) {
        val btn = RekPillButton(this).apply {
            setVariantKind(RekPillButton.VARIANT_OUTLINE)
            setIcon(iconRes)
            text = getString(titleRes)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Util.dp(this@SettingsActivity, 8) }
            setOnClickListener { onClick() }
        }
        card.addView(btn)
    }

    private fun addNote(card: LinearLayout, textRes: Int) {
        val tv = TextView(this).apply {
            setText(textRes)
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.muted))
            setPadding(0, Util.dp(this@SettingsActivity, 6), 0, Util.dp(this@SettingsActivity, 2))
        }
        card.addView(tv)
    }

    private fun addPresetRow(
        card: LinearLayout, onPreset: (Int) -> Unit
    ): (Int) -> Unit {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Util.dp(this@SettingsActivity, 6), 0, Util.dp(this@SettingsActivity, 4))
        }
        val pills = mutableListOf<RekPillButton>()
        fun refresh(selected: Int) {
            pills.forEachIndexed { i, pill ->
                pill.setVariantKind(
                    if (i == selected) RekPillButton.VARIANT_DANGER_SOFT
                    else RekPillButton.VARIANT_SOFT
                )
            }
        }
        val labels = intArrayOf(
            R.string.preset_off, R.string.preset_natural,
            R.string.preset_podcast, R.string.preset_studio
        )
        labels.forEachIndexed { idx, res ->
            val pill = RekPillButton(this).apply {
                setVariantKind(RekPillButton.VARIANT_SOFT)
                text = getString(res)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (idx > 0) marginStart = Util.dp(this@SettingsActivity, 6)
                }
                setOnClickListener {
                    refresh(idx)
                    onPreset(idx)
                }
            }
            pills.add(pill)
            row.addView(pill)
        }
        card.addView(row)
        refresh(currentPresetIndex())
        return { selected -> refresh(selected) }
    }

    // ---------------------------------------------------------------- baterai & versi

    private fun setupBatteryButton(btn: RekPillButton) {
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            pm.isIgnoringBatteryOptimizations(packageName)
        if (ignoring) {
            btn.text = getString(R.string.battery_ok)
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
