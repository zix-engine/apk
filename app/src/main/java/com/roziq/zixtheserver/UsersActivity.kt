package com.roziq.zixtheserver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import org.json.JSONArray
import org.json.JSONObject

/** Manajemen akun untuk Owner/Admin: buat, ubah, izin, aktif/nonaktif, reset password, hapus. */
class UsersActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var api: ApiClient
    private lateinit var auth: AuthStore
    private lateinit var adapter: SimpleToolAdapter
    private var all: List<JSONObject> = emptyList()
    private var shown: List<JSONObject> = emptyList()
    private var query = ""

    private val permLabels = linkedMapOf(
        "server.view" to "Lihat server",
        "server.manage" to "Kelola server (tambah/ubah/hapus)",
        "trading.use" to "Modul trading",
        "backup.use" to "Backup & restore",
        "ai.use" to "AI Assistant",
        "audit.view" to "Lihat audit log"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = ApiClient.get(this)
        auth = AuthStore.get(this)
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = "Kelola akun"
        b.tvSub.text = "Owner membuat akun; password sementara tampil sekali"
        b.btnTop.visibility = View.GONE
        b.fabAdd.visibility = View.VISIBLE
        b.fabAdd.text = "Buat akun"
        adapter = SimpleToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnReload.setOnClickListener { load() }
        b.fabAdd.setOnClickListener { showCreate() }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bb: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase().orEmpty()
                render()
            }
        })
        load()
    }

    private fun load() {
        b.busy.visibility = View.VISIBLE
        b.tvMsg.visibility = View.GONE
        runApi({ api.call("GET", "/api/users").getJSONArray("users") }, { arr ->
            b.busy.visibility = View.INVISIBLE
            all = (0 until arr.length()).map { arr.getJSONObject(it) }
            render()
        }, { e ->
            b.busy.visibility = View.INVISIBLE
            b.tvMsg.text = e.message ?: e.code
            b.tvMsg.visibility = View.VISIBLE
        })
    }

    private fun render() {
        shown = if (query.isEmpty()) all else all.filter {
            (it.optString("username") + " " + it.optString("displayName") + " " + it.optString("email")).lowercase().contains(query)
        }
        adapter.set(
            shown.map { u ->
                val disabled = u.optString("status") == "disabled"
                Tools.Item(
                    u.optString("displayName") + "  (@" + u.optString("username") + ")",
                    u.optString("role") + " • " + (if (disabled) "NONAKTIF" else "aktif") +
                        (if (u.optBoolean("mustChangePassword")) " • wajib ganti password" else ""),
                    u.optString("role"),
                    u.optLong("id").toString(),
                    if (disabled) 3 else if (u.optString("role") == "owner") 1 else 0
                )
            }
        )
        if (shown.isEmpty()) {
            b.tvMsg.text = "Tidak ada akun."
            b.tvMsg.visibility = View.VISIBLE
        } else {
            b.tvMsg.visibility = View.GONE
        }
    }

    private fun onItem(pos: Int) {
        val u = shown.getOrNull(pos) ?: return
        val meId = auth.user?.id ?: -1L
        val meRole = auth.user?.role ?: ""
        val id = u.optLong("id")
        val role = u.optString("role")
        val disabled = u.optString("status") == "disabled"
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add("Detail"); acts.add { showDetail(u) }
        val manageable = id != meId && role != "owner" && (meRole == "owner" || (meRole == "admin" && role == "user"))
        if (manageable) {
            labels.add("Edit nama/email"); acts.add { showEdit(u) }
            if (meRole == "owner") {
                labels.add("Ubah role"); acts.add { chooseRole(u) }
            }
            labels.add("Atur izin"); acts.add { editPerms(u) }
            labels.add(if (disabled) "Aktifkan akun" else "Nonaktifkan akun"); acts.add { setStatus(u, if (disabled) "active" else "disabled") }
            labels.add("Reset password"); acts.add { confirmReset(u) }
            if (meRole == "owner") {
                labels.add("Hapus akun"); acts.add { confirmDelete(u) }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(u.optString("displayName"))
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .show()
    }

    private fun showDetail(u: JSONObject) {
        val perms = u.optJSONArray("permissions") ?: JSONArray()
        val list = (0 until perms.length()).map { permLabels[perms.getString(it)] ?: perms.getString(it) }
        showText(
            u.optString("username"),
            "Nama   : ${u.optString("displayName")}\nEmail  : ${u.optString("email").ifEmpty { "-" }}\n" +
                "Role   : ${u.optString("role")}\nStatus : ${u.optString("status")}\n\nIzin:\n" +
                (if (list.isEmpty()) "(tidak ada)" else list.joinToString("\n") { "• $it" }),
            mono = false
        )
    }

    private fun field(hint: String, value: String = "", password: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        e.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        e.setTextColor(zColor(R.attr.zText))
        e.setHintTextColor(zColor(R.attr.zTextDim))
        return e
    }

    private fun box(vararg views: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dpPx(24), dpPx(8), dpPx(24), 0)
        views.forEach { l.addView(it) }
        return l
    }

    // ------------------------------------------------------------ Buat akun

    private fun showCreate() {
        val meRole = auth.user?.role ?: ""
        val user = field("Username (huruf kecil/angka)")
        val name = field("Nama tampilan")
        val email = field("Email (opsional)")
        val pass = field("Password (kosongkan = dibuat otomatis)", password = true)
        var role = "user"
        val roleBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        roleBtn.text = "Role: user"
        if (meRole == "owner") {
            roleBtn.setOnClickListener {
                role = if (role == "user") "admin" else "user"
                roleBtn.text = "Role: $role"
            }
        } else {
            roleBtn.isEnabled = false
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Buat akun")
            .setView(box(user, name, email, pass, roleBtn))
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { btn ->
                val body = JSONObject()
                    .put("username", user.text.toString().trim())
                    .put("displayName", name.text.toString().trim().ifEmpty { user.text.toString().trim() })
                    .put("role", role)
                if (email.text.isNotBlank()) body.put("email", email.text.toString().trim())
                if (pass.text.isNotEmpty()) body.put("password", pass.text.toString())
                btn.isEnabled = false
                runApi({ api.call("POST", "/api/users", body) }, { res ->
                    dialog.dismiss()
                    load()
                    val temp = if (res.isNull("temporaryPassword")) "" else res.optString("temporaryPassword")
                    if (temp.isNotEmpty()) showTemp(user.text.toString().trim().lowercase(), temp) else toast("Akun dibuat")
                }, { e ->
                    btn.isEnabled = true
                    toast(e.message ?: e.code)
                })
            }
        }
        dialog.show()
    }

    private fun showTemp(username: String, temp: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Password sementara")
            .setMessage(
                "Akun: $username\nPassword sementara:\n\n$temp\n\nTampil SEKALI. Berikan ke pemilik akun lewat jalur aman; " +
                    "ia akan diminta menggantinya saat login pertama."
            )
            .setPositiveButton(R.string.close, null)
            .setNeutralButton("Salin") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("password", temp))
                toast(R.string.copied)
            }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------ Aksi

    private fun patch(u: JSONObject, body: JSONObject, ok: String) {
        runApi({ api.call("PATCH", "/api/users/" + u.optLong("id"), body) }, {
            toast(ok)
            load()
        })
    }

    private fun showEdit(u: JSONObject) {
        val name = field("Nama tampilan", u.optString("displayName"))
        val email = field("Email", u.optString("email"))
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit akun")
            .setView(box(name, email))
            .setPositiveButton(R.string.save) { _, _ ->
                patch(u, JSONObject().put("displayName", name.text.toString().trim()).put("email", email.text.toString().trim()), "Tersimpan")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseRole(u: JSONObject) {
        val roles = arrayOf("user", "admin")
        MaterialAlertDialogBuilder(this)
            .setTitle("Role")
            .setSingleChoiceItems(roles, roles.indexOf(u.optString("role")).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                patch(u, JSONObject().put("role", roles[i]), "Role diubah")
            }
            .show()
    }

    private fun editPerms(u: JSONObject) {
        val keys = permLabels.keys.toList()
        val have = u.optJSONArray("permissions") ?: JSONArray()
        val current = (0 until have.length()).map { have.getString(it) }.toSet()
        val checked = BooleanArray(keys.size) { current.contains(keys[it]) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Izin akses")
            .setMultiChoiceItems(permLabels.values.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton(R.string.save) { _, _ ->
                val arr = JSONArray()
                keys.forEachIndexed { i, k -> if (checked[i]) arr.put(k) }
                patch(u, JSONObject().put("permissions", arr), "Izin diperbarui")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setStatus(u: JSONObject, status: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (status == "disabled") "Nonaktifkan akun?" else "Aktifkan akun?")
            .setMessage(if (status == "disabled") "Semua sesi akun ini dikeluarkan dan ia tidak bisa login." else "Akun bisa login lagi.")
            .setPositiveButton("Ya") { _, _ -> patch(u, JSONObject().put("status", status), "Status diubah") }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmReset(u: JSONObject) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset password?")
            .setMessage("Dibuat password sementara baru, semua sesi akun ini dikeluarkan, dan ia wajib menggantinya saat login.")
            .setPositiveButton("Reset") { _, _ ->
                runApi({ api.call("POST", "/api/users/" + u.optLong("id") + "/reset-password") }, { res ->
                    showTemp(u.optString("username"), res.optString("temporaryPassword"))
                    load()
                })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(u: JSONObject) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus “" + u.optString("username") + "”?")
            .setMessage("Akun dan sesinya dihapus permanen. Catatan audit tetap tersimpan.")
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                runApi({ api.call("DELETE", "/api/users/" + u.optLong("id")) }, {
                    toast("Akun dihapus")
                    load()
                })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
