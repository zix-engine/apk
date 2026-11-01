package com.roziq.zixtheserver

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityLoginBinding

/** Login ke backend ZixFunction, atau pilih mode lokal (tanpa akun). */
class LoginActivity : BaseActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var auth: AuthStore
    private lateinit var api: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = AuthStore.get(this)
        api = ApiClient.get(this)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.etUrl.setText(auth.baseUrl)
        b.etUser.setText(auth.lastUsername)
        showPin()

        b.btnLogin.setOnClickListener {
            haptic(it)
            doLogin()
        }
        b.btnTest.setOnClickListener {
            haptic(it)
            doTest()
        }
        b.btnLocal.setOnClickListener {
            haptic(it)
            MaterialAlertDialogBuilder(this)
                .setTitle("Mode lokal")
                .setMessage(
                    "Kamu akan memakai ZixFunction tanpa akun: server, SSH, trading, dan backup tetap berjalan di HP ini. " +
                        "Fitur yang butuh akun/backend tidak tersedia. Kamu bisa login kapan saja dari menu Akun."
                )
                .setPositiveButton("Lanjut") { _, _ ->
                    auth.mode = "local"
                    goMain()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun goMain() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun showPin() {
        if (auth.pin.isEmpty()) {
            b.tvPin.visibility = View.GONE
        } else {
            b.tvPin.visibility = View.VISIBLE
            b.tvPin.text = "Sertifikat dipercaya (SHA-256):\n" + auth.pin
        }
    }

    private fun error(msg: String?) {
        if (msg.isNullOrEmpty()) {
            b.tvError.visibility = View.GONE
        } else {
            b.tvError.text = msg
            b.tvError.visibility = View.VISIBLE
        }
    }

    private fun busy(on: Boolean) {
        b.busy.visibility = if (on) View.VISIBLE else View.INVISIBLE
        b.btnLogin.isEnabled = !on
        b.btnTest.isEnabled = !on
    }

    /** Menormalkan alamat: wajib HTTPS; menambahkan https:// bila tanpa skema. */
    private fun readUrl(): String? {
        var u = b.etUrl.text.toString().trim()
        if (u.isEmpty()) {
            error("Isi alamat server API.")
            return null
        }
        if (u.startsWith("http://")) {
            error("Alamat harus https:// (koneksi tidak terenkripsi ditolak).")
            return null
        }
        if (!u.startsWith("https://")) u = "https://$u"
        if (u.removePrefix("https://").isEmpty() || u.contains(" ")) {
            error("Alamat server tidak valid.")
            return null
        }
        return u.trimEnd('/')
    }

    private fun doTest() {
        error(null)
        val url = readUrl() ?: return
        if (url != auth.baseUrl) {
            auth.baseUrl = url
            auth.pin = ""
            showPin()
        }
        busy(true)
        runApi({ api.ping() }, {
            busy(false)
            toast("Server terhubung ✓")
        }, { e ->
            busy(false)
            if (e.code == "TLS") offerPin(url, null) else error(e.message)
        })
    }

    private fun doLogin() {
        error(null)
        val url = readUrl() ?: return
        val user = b.etUser.text.toString().trim()
        val pass = b.etPass.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            error("Username dan password wajib diisi.")
            return
        }
        if (url != auth.baseUrl) {
            auth.baseUrl = url
            auth.pin = ""
            showPin()
        }
        busy(true)
        val device = Build.MANUFACTURER + " " + Build.MODEL
        runApi({ api.login(user, pass, device) }, { u ->
            busy(false)
            if (u.mustChangePassword) {
                startActivity(
                    Intent(this, ChangePasswordActivity::class.java).putExtra(ChangePasswordActivity.EXTRA_FORCED, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } else {
                goMain()
            }
        }, { e ->
            busy(false)
            if (e.code == "TLS") offerPin(url, user to pass) else error(e.message)
        })
    }

    /** Sertifikat tidak dikenal sistem: tawarkan mengunci sidik jarinya (untuk server tanpa domain). */
    private fun offerPin(url: String, retry: Pair<String, String>?) {
        busy(true)
        runApi({ api.probeFingerprint(url) }, { fp ->
            busy(false)
            MaterialAlertDialogBuilder(this)
                .setTitle("Sertifikat belum dikenal")
                .setMessage(
                    "Server memakai sertifikat yang tidak dikenal sistem Android (umum untuk server tanpa domain).\n\n" +
                        "Sidik jari SHA-256:\n$fp\n\n" +
                        "Cocokkan DENGAN yang ditampilkan skrip pemasangan di VPS-mu. Bila sama, percayai. " +
                        "Bila beda, batalkan — bisa jadi ada penyadap di jaringan."
                )
                .setPositiveButton("Sama, percayai") { _, _ ->
                    auth.pin = fp
                    showPin()
                    if (retry != null) doLogin() else doTest()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, { e ->
            busy(false)
            error(e.message)
        })
    }
}
