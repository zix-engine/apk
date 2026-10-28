package com.roziq.zixtheserver

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.concurrent.thread

/** Dasar semua layar: menerapkan tema pilihan, haptic, dan alur update GitHub. */
abstract class BaseActivity : AppCompatActivity() {

    protected lateinit var prefs: Prefs
    private var appliedTheme: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        appliedTheme = prefs.theme
        setTheme(Themes.byId(appliedTheme).style)
        super.onCreate(savedInstanceState)
        // Saat kunci aktif, blokir screenshot dan thumbnail di daftar aplikasi terbaru.
        if (prefs.lockEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    /** Splash tidak meminta kunci; layar berikutnya (Main) yang meminta. */
    protected open val lockable: Boolean = true

    override fun onStart() {
        super.onStart()
        if (lockable && prefs.lockEnabled && AppLock.needsAuth()) {
            window.decorView.visibility = View.INVISIBLE
            AppLock.authenticate(this, getString(R.string.lock_title)) { ok ->
                if (ok && !isDestroyed) {
                    window.decorView.visibility = View.VISIBLE
                } else {
                    finishAffinity()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLock.touch()
        // Tema diganti dari layar lain: buat ulang agar warnanya ikut berubah.
        if (prefs.theme != appliedTheme) recreate()
    }

    override fun onPause() {
        AppLock.touch()
        super.onPause()
    }

    override fun onStop() {
        AppLock.touch()
        super.onStop()
    }

    protected fun haptic(v: View) {
        if (prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    protected fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    protected fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    protected fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }

    // ---------------------------------------------------------------- Update

    /** Cek rilis terbaru di GitHub. [manual] = tampilkan hasil walau tidak ada update. */
    fun checkUpdate(manual: Boolean) {
        val repo = prefs.repo
        if (repo.isBlank() || repo.startsWith("USERNAME")) {
            if (manual) toast(R.string.update_no_repo)
            return
        }
        if (manual) toast(R.string.update_checking)
        thread(name = "update-check") {
            try {
                val rel = Updater.fetchLatest(repo)
                prefs.lastUpdateCheck = System.currentTimeMillis()
                onUi {
                    if (Updater.isNewer(rel.tag, BuildConfig.VERSION_NAME)) {
                        showUpdateDialog(rel)
                    } else if (manual) {
                        toast(getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                    }
                }
            } catch (e: Exception) {
                if (manual) onUi { toast(getString(R.string.update_failed, e.message ?: "")) }
            }
        }
    }

    private fun showUpdateDialog(rel: Updater.Release) {
        val sizeMb = if (rel.size > 0) " (%.1f MB)".format(rel.size / 1048576.0) else ""
        val notes = rel.notes.trim().take(600)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available, rel.tag))
            .setMessage(
                getString(R.string.update_message, BuildConfig.VERSION_NAME, rel.tag, sizeMb) +
                    if (notes.isNotEmpty()) "\n\n$notes" else ""
            )
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(rel) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun startDownload(rel: Updater.Release) {
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            isIndeterminate = false
        }
        val label = TextView(this).apply {
            text = getString(R.string.update_downloading)
            setTextColor(zColor(R.attr.zTextDim))
            textSize = 13f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad / 2 }
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_title)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()

        val file = Updater.updateFile(this)
        thread(name = "update-download") {
            try {
                Updater.download(rel, file) { pct ->
                    onUi {
                        if (pct >= 0) {
                            bar.setProgressCompat(pct, true)
                            label.text = getString(R.string.update_progress, pct)
                        }
                    }
                }
                onUi {
                    dialog.dismiss()
                    installApk(file)
                }
            } catch (e: Exception) {
                onUi {
                    dialog.dismiss()
                    toast(getString(R.string.update_failed, e.message ?: ""))
                }
            }
        }
    }

    private fun installApk(file: java.io.File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_msg)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        try {
            Updater.install(this, file)
        } catch (e: Exception) {
            toast(getString(R.string.update_failed, e.message ?: ""))
        }
    }
}
