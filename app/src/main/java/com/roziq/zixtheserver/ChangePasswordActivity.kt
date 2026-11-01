package com.roziq.zixtheserver

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Ganti password. Bila [EXTRA_FORCED], wajib dilakukan (password sementara dari Owner). */
class ChangePasswordActivity : BaseActivity() {

    private lateinit var auth: AuthStore
    private lateinit var api: ApiClient
    private var forced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = AuthStore.get(this)
        api = ApiClient.get(this)
        forced = intent.getBooleanExtra(EXTRA_FORCED, false)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dpPx(20), dpPx(24), dpPx(20), dpPx(24))

        fun label(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.setTextColor(color)
            if (bold) v.setTypeface(v.typeface, android.graphics.Typeface.BOLD)
            return v
        }
        fun field(hint: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            e.setSingleLine(true)
            e.setTextColor(zColor(R.attr.zText))
            e.setHintTextColor(zColor(R.attr.zTextDim))
            e.setBackgroundResource(R.drawable.bg_input)
            e.setPadding(dpPx(12), dpPx(12), dpPx(12), dpPx(12))
            return e
        }
        val lp = { top: Int ->
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dpPx(top) }
        }

        root.addView(label("Ganti password", 22f, zColor(R.attr.zAccent), true))
        root.addView(
            label(
                if (forced) "Kamu masuk dengan password sementara. Buat password baru sebelum melanjutkan."
                else "Password baru berlaku untuk perangkat ini; sesi di perangkat lain akan keluar.",
                13f, zColor(R.attr.zTextDim)
            ),
            lp(4)
        )
        val cur = field("Password saat ini")
        val new1 = field("Password baru (min 10 karakter, huruf + angka)")
        val new2 = field("Ulangi password baru")
        root.addView(cur, lp(18))
        root.addView(new1, lp(10))
        root.addView(new2, lp(10))
        val err = label("", 13f, zColor(R.attr.zBad))
        root.addView(err, lp(10))
        val btn = MaterialButton(this)
        btn.text = "Simpan password"
        root.addView(btn, lp(14))

        btn.setOnClickListener {
            haptic(it)
            val username = auth.user?.username.orEmpty()
            val p1 = new1.text.toString()
            val problem = when {
                cur.text.isEmpty() -> "Isi password saat ini."
                p1.length < 10 -> "Password baru minimal 10 karakter."
                p1.length > 128 -> "Password baru maksimal 128 karakter."
                !p1.any { c -> c.isLetter() } || !p1.any { c -> c.isDigit() } -> "Password baru harus berisi huruf dan angka."
                username.isNotEmpty() && p1.lowercase().contains(username.lowercase()) -> "Password tidak boleh mengandung username."
                p1 != new2.text.toString() -> "Konfirmasi password tidak sama."
                p1 == cur.text.toString() -> "Password baru tidak boleh sama dengan yang lama."
                else -> null
            }
            if (problem != null) {
                err.text = problem
                return@setOnClickListener
            }
            err.text = ""
            btn.isEnabled = false
            runApi({
                api.call(
                    "POST", "/api/me/password",
                    org.json.JSONObject().put("currentPassword", cur.text.toString()).put("newPassword", p1)
                )
                api.me()
            }, {
                toast("Password diganti ✓")
                if (forced) {
                    startActivity(
                        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }
                finish()
            }, { e ->
                btn.isEnabled = true
                err.text = e.message
            })
        }

        val sv = ScrollView(this)
        sv.setBackgroundColor(zColor(R.attr.zBg))
        sv.addView(root)
        setContentView(sv)
    }

    companion object {
        const val EXTRA_FORCED = "forced"
    }
}
