package com.roziq.zixtheserver

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Layar kalkulator generik: kolom dibuat dari definisi di [TradeCalc]. */
class CalcActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val calc = intent.getStringExtra(EXTRA_ID)?.let { TradeCalc.byId(it) }
        if (calc == null) {
            finish()
            return
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dpPx(18), dpPx(14), dpPx(18), dpPx(28))

        fun text(t: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.setTextColor(color)
            if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
            if (mono) v.typeface = Typeface.MONOSPACE
            return v
        }

        root.addView(text(calc.title, 22f, zColor(R.attr.zAccent), bold = true))
        root.addView(text(calc.desc, 13f, zColor(R.attr.zTextDim)))

        val inputs = LinkedHashMap<String, EditText>()
        for (f in calc.fields) {
            val label = text(f.label, 12f, zColor(R.attr.zTextDim))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dpPx(12)
            root.addView(label, lp)
            val e = EditText(this)
            e.setText(f.default)
            e.textSize = 15f
            e.setTextColor(zColor(R.attr.zText))
            e.setBackgroundResource(R.drawable.bg_input)
            e.setPadding(dpPx(12), dpPx(10), dpPx(12), dpPx(10))
            e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            e.setSingleLine(true)
            inputs[f.key] = e
            root.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val result = text("", 14f, zColor(R.attr.zText), mono = true)
        result.setTextIsSelectable(true)
        result.setBackgroundResource(R.drawable.bg_card)
        result.setPadding(dpPx(14), dpPx(12), dpPx(14), dpPx(12))

        val btn = MaterialButton(this)
        btn.text = "Hitung"
        btn.setOnClickListener {
            haptic(it)
            val values = HashMap<String, Double>()
            for ((k, e) in inputs) {
                e.text.toString().trim().replace(',', '.').toDoubleOrNull()?.let { v -> values[k] = v }
            }
            result.text = try {
                calc.compute(values)
            } catch (e: Exception) {
                "Perhitungan gagal: " + (e.message ?: "input tidak valid")
            }
        }
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dpPx(16)
        root.addView(btn, blp)
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dpPx(12)
        root.addView(result, rlp)
        val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.topMargin = dpPx(14)
        root.addView(text("Rumus & asumsi:\n" + calc.note + "\n\nKalkulator ini alat bantu hitung, bukan saran trading atau jaminan hasil.", 12f, zColor(R.attr.zTextDim)), nlp)

        val sv = ScrollView(this)
        sv.setBackgroundColor(zColor(R.attr.zBg))
        sv.addView(root)
        setContentView(sv)
    }

    companion object {
        const val EXTRA_ID = "calc"
    }
}
