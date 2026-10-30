package com.roziq.zixtheserver

import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Context.dpPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Dialog berisi teks panjang yang bisa digulir dan dipilih/disalin. */
fun Context.showText(title: String, text: String, mono: Boolean = true) {
    val tv = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        if (mono) typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setTextColor(zColor(R.attr.zText))
        setPadding(dpPx(22), dpPx(8), dpPx(22), dpPx(8))
    }
    val sv = ScrollView(this)
    sv.addView(tv)
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(sv)
        .setPositiveButton(R.string.close, null)
        .show()
}
