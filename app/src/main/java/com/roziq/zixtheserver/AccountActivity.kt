package com.roziq.zixtheserver

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Akun saya: profil, ganti password, sesi aktif, kelola akun (Owner/Admin), audit log, keluar. */
class AccountActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var auth: AuthStore
    private lateinit var api: ApiClient
    private lateinit var adapter: SimpleToolAdapter
    private var actions: List<() -> Unit> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = AuthStore.get(this)
        api = ApiClient.get(this)
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = "Akun"
        b.tvSub.text = auth.baseUrl
        b.etSearch.visibility = View.GONE
        b.btnTop.visibility = View.GONE
        b.btnReload.setOnClickListener { refresh() }
        adapter = SimpleToolAdapter { pos -> actions.getOrNull(pos)?.invoke() }
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (auth.mode == "remote" && auth.hasSession()) refresh()
    }

    private fun refresh() {
        b.busy.visibility = View.VISIBLE
        runApi({ api.me() }, {
            b.busy.visibility = View.INVISIBLE
            render()
        }, { e ->
            b.busy.visibility = View.INVISIBLE
            toast(e.message ?: e.code)
        })
    }

    private fun render() {
        val items = ArrayList<Tools.Item>()
        val acts = ArrayList<() -> Unit>()
        val u = auth.user
        if (auth.mode != "remote" || u == null) {
            items.add(Tools.Item("Mode lokal", "Kamu belum login ke server. Semua fitur lokal tetap bisa dipakai.", "lokal", "", 2))
            acts.add { }
            items.add(Tools.Item("Masuk ke server", "Login dengan akun dari Owner.", "login", "", 1))
            acts.add { startActivity(Intent(this, LoginActivity::class.java)) }
        } else {
            items.add(Tools.Item(u.displayName + "  (@" + u.username + ")", u.email.ifEmpty { "tanpa email" } + " • izin: " + u.permissions.size, u.role, "", 1))
            acts.add { }
            items.add(Tools.Item("Ganti password", "Sesi di perangkat lain akan keluar.", "", "", 0))
            acts.add { startActivity(Intent(this, ChangePasswordActivity::class.java)) }
            items.add(Tools.Item("Sesi aktif", "Lihat dan keluarkan perangkat lain.", "", "", 0))
            acts.add { showSessions() }
            if (u.role == "owner" || u.role == "admin") {
                items.add(Tools.Item("Kelola akun", "Buat akun, atur izin, reset password, nonaktifkan.", "", "", 0))
                acts.add { startActivity(Intent(this, UsersActivity::class.java)) }
            }
            if (u.role == "owner" || u.permissions.contains("audit.view")) {
                items.add(Tools.Item("Audit log", "Catatan login dan perubahan akun.", "", "", 0))
                acts.add { startActivity(Intent(this, AuditActivity::class.java)) }
            }
            items.add(Tools.Item("Keluar", "Hapus sesi di perangkat ini. Data lokal tidak dihapus.", "", "", 3))
            acts.add { confirmLogout() }
            items.add(Tools.Item("Pakai mode lokal", "Putuskan dari server dan lanjut tanpa akun.", "", "", 2))
            acts.add { switchLocal() }
        }
        if (auth.baseUrl.isNotEmpty()) {
            items.add(
                Tools.Item(
                    "Server API", auth.baseUrl + if (auth.pin.isNotEmpty()) "\nSertifikat dikunci (SHA-256 dipercaya)" else "\nSertifikat: validasi sistem",
                    "", "", 0
                )
            )
            acts.add { if (auth.pin.isNotEmpty()) showText("Sidik jari dipercaya", auth.pin) }
        }
        actions = acts
        adapter.set(items)
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Keluar?")
            .setPositiveButton("Keluar") { _, _ ->
                b.busy.visibility = View.VISIBLE
                Thread {
                    api.logout()
                    onUi {
                        goLogin()
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun switchLocal() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Pakai mode lokal?")
            .setMessage("Sesi login di perangkat ini dihapus. Data lokal (server, trading, backup) tetap ada.")
            .setPositiveButton("Ya") { _, _ ->
                Thread {
                    api.logout()
                    auth.mode = "local"
                    onUi {
                        startActivity(
                            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSessions() {
        b.busy.visibility = View.VISIBLE
        runApi({ api.call("GET", "/api/me/sessions").getJSONArray("sessions") }, { arr ->
            b.busy.visibility = View.INVISIBLE
            val df = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
            val ids = ArrayList<Long>()
            val labels = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                ids.add(s.getLong("id"))
                labels.add(
                    (if (s.optBoolean("current")) "● ini  " else "") + s.optString("device", "?").ifBlank { "perangkat" } +
                        "\n" + df.format(Date(s.optLong("lastUsedAt"))) + " • " + s.optString("ip")
                )
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Sesi aktif")
                .setItems(labels.toTypedArray()) { _, i ->
                    if (arr.getJSONObject(i).optBoolean("current")) {
                        toast("Ini sesi perangkat ini.")
                    } else {
                        runApi({ api.call("DELETE", "/api/me/sessions/" + ids[i]) }, { toast("Sesi dikeluarkan") })
                    }
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }, { e ->
            b.busy.visibility = View.INVISIBLE
            toast(e.message ?: e.code)
        })
    }
}
