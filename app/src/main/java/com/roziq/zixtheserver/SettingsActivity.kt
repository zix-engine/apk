package com.roziq.zixtheserver

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivitySettingsBinding
import com.roziq.zixtheserver.databinding.ItemSettingBinding

class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var store: ServerStore

    private val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ServerStore(this)
        b.btnBack.setOnClickListener { finish() }
        build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        val ll = b.llSettings
        ll.removeAllViews()

        // ---------------------------------------------------------- Tampilan
        header(getString(R.string.set_appearance))
        ll.addView(themePicker())
        switchRow(getString(R.string.set_splash), getString(R.string.set_splash_desc), prefs.splash) {
            prefs.splash = it
        }
        switchRow(getString(R.string.set_haptics), getString(R.string.set_haptics_desc), prefs.haptics) {
            prefs.haptics = it
        }

        // ---------------------------------------------------------- Terminal
        header(getString(R.string.set_terminal))
        val fontRow = row(
            getString(R.string.set_font),
            getString(R.string.set_font_value, prefs.termFont)
        ) { chooseFont() }
        fontRowRef = fontRow
        switchRow(getString(R.string.set_keep_screen), getString(R.string.set_keep_screen_desc), prefs.keepScreen) {
            prefs.keepScreen = it
        }
        row(getString(R.string.set_clear_history), getString(R.string.set_clear_history_desc)) {
            prefs.clearHistory()
            toast(R.string.history_cleared)
        }

        // ---------------------------------------------------------- Dashboard
        header(getString(R.string.set_dashboard))
        switchRow(getString(R.string.set_auto_refresh), getString(R.string.set_auto_refresh_desc), prefs.autoRefresh) {
            prefs.autoRefresh = it
        }
        val intervalRow = row(
            getString(R.string.set_interval),
            getString(R.string.set_interval_value, prefs.refreshSec)
        ) { chooseInterval() }
        intervalRowRef = intervalRow

        // ---------------------------------------------------------- Data
        header(getString(R.string.set_data))
        row(getString(R.string.set_backup), getString(R.string.set_backup_desc)) { confirmBackup() }
        row(getString(R.string.set_restore), getString(R.string.set_restore_desc)) {
            restoreLauncher.launch(arrayOf("*/*"))
        }

        // ---------------------------------------------------------- Update
        header(getString(R.string.set_update))
        switchRow(getString(R.string.set_auto_update), getString(R.string.set_auto_update_desc), prefs.autoUpdate) {
            prefs.autoUpdate = it
        }
        val repoRow = row(getString(R.string.set_repo), repoLabel()) { editRepo() }
        repoRowRef = repoRow
        row(getString(R.string.set_check_now), getString(R.string.set_check_now_desc, BuildConfig.VERSION_NAME)) {
            checkUpdate(true)
        }

        // ---------------------------------------------------------- Tentang
        header(getString(R.string.set_about))
        row(getString(R.string.app_name), getString(R.string.version_fmt, BuildConfig.VERSION_NAME)) {
            val repo = prefs.repo
            if (repo.isNotBlank() && !repo.startsWith("USERNAME")) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$repo")))
            }
        }
    }

    private var fontRowRef: ItemSettingBinding? = null
    private var intervalRowRef: ItemSettingBinding? = null
    private var repoRowRef: ItemSettingBinding? = null

    private fun repoLabel(): String {
        val repo = prefs.repo
        return if (repo.isBlank() || repo.startsWith("USERNAME")) {
            getString(R.string.set_repo_unset)
        } else {
            repo
        }
    }

    // ------------------------------------------------------------ Pembangun baris

    private fun header(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(zColor(R.attr.zAccent))
            textSize = 12f
            letterSpacing = 0.08f
            setAllCaps(true)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(20), 0, dp(6))
        }
        b.llSettings.addView(tv)
    }

    private fun row(title: String, desc: String, onClick: () -> Unit): ItemSettingBinding {
        val r = ItemSettingBinding.inflate(layoutInflater, b.llSettings, false)
        r.tvTitle.text = title
        r.tvDesc.text = desc
        r.root.setOnClickListener {
            haptic(it)
            onClick()
        }
        b.llSettings.addView(r.root)
        return r
    }

    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val r = row(title, desc) { }
        r.sw.visibility = View.VISIBLE
        r.sw.isChecked = checked
        r.sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        r.root.setOnClickListener {
            haptic(it)
            r.sw.toggle()
        }
    }

    // ------------------------------------------------------------ Tema

    private fun themePicker(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(6), dp(2), dp(10))
        }
        for (t in Themes.all) {
            val selected = t.id == prefs.theme
            val swatch = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(t.bg, t.surface, t.accent)
                ).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(
                        dp(if (selected) 3 else 1),
                        if (selected) zColor(R.attr.zAccent) else zColor(R.attr.zStroke)
                    )
                }
            }
            val label = TextView(this).apply {
                text = t.name
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(if (selected) zColor(R.attr.zAccent) else zColor(R.attr.zTextDim))
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), 0, dp(6), 0)
                addView(swatch, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(
                    label,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                )
                setOnClickListener {
                    haptic(it)
                    if (prefs.theme != t.id) {
                        prefs.theme = t.id
                        recreate()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                }
            }
            line.addView(item)
        }
        scroll.addView(line)
        val holder = FrameLayout(this)
        holder.addView(scroll)
        return holder
    }

    // ------------------------------------------------------------ Dialog pilihan

    private fun chooseFont() {
        val sizes = (9..20).toList()
        val labels = sizes.map { "$it sp" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_font)
            .setSingleChoiceItems(labels, sizes.indexOf(prefs.termFont).coerceAtLeast(0)) { d, which ->
                prefs.termFont = sizes[which]
                fontRowRef?.tvDesc?.text = getString(R.string.set_font_value, prefs.termFont)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseInterval() {
        val secs = listOf(5, 10, 15, 30, 60)
        val labels = secs.map { "$it detik" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_interval)
            .setSingleChoiceItems(labels, secs.indexOf(prefs.refreshSec).coerceAtLeast(0)) { d, which ->
                prefs.refreshSec = secs[which]
                intervalRowRef?.tvDesc?.text = getString(R.string.set_interval_value, prefs.refreshSec)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editRepo() {
        val input = EditText(this).apply {
            hint = "pemilik/nama-repo"
            setText(prefs.repoOverride)
            setSingleLine(true)
            setTextColor(zColor(R.attr.zText))
            setHintTextColor(zColor(R.attr.zTextDim))
        }
        val holder = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_repo)
            .setMessage(getString(R.string.set_repo_help, BuildConfig.GITHUB_REPO))
            .setView(holder)
            .setPositiveButton(R.string.save) { _, _ ->
                var v = input.text.toString().trim()
                v = v.removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
                prefs.repoOverride = v
                repoRowRef?.tvDesc?.text = repoLabel()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ Backup / restore

    private fun confirmBackup() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_backup)
            .setMessage(R.string.backup_warning)
            .setPositiveButton(R.string.continue_) { _, _ ->
                backupLauncher.launch("zixtheserver-backup.json")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doExport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(store.exportJson().toByteArray(Charsets.UTF_8))
            }
            toast(R.string.backup_done)
        } catch (e: Exception) {
            toast(getString(R.string.backup_failed, e.message ?: ""))
        }
    }

    private fun doImport(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("File kosong")
            val n = store.importJson(text)
            toast(getString(R.string.restore_done, n))
        } catch (e: Exception) {
            toast(getString(R.string.restore_failed, e.message ?: ""))
        }
    }
}
