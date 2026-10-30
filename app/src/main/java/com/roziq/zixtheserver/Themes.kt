package com.roziq.zixtheserver

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes

/** Definisi tema. [bg], [surface], [accent] hanya untuk pratinjau di pengaturan. */
data class ThemeDef(
    val id: String,
    val name: String,
    @StyleRes val style: Int,
    val bg: Int,
    val surface: Int,
    val accent: Int
)

object Themes {

    val all: List<ThemeDef> = listOf(
        ThemeDef("indigo", "Zix Indigo", R.style.Theme_Zix_Indigo, 0xFF0C0B1E.toInt(), 0xFF1D1B3A.toInt(), 0xFF8B7CF6.toInt()),
        ThemeDef("cyan", "Cyber Cyan", R.style.Theme_Zix_Cyan, 0xFF0A0E1A.toInt(), 0xFF1A2238.toInt(), 0xFF22D3EE.toInt()),
        ThemeDef("violet", "Neon Violet", R.style.Theme_Zix_Violet, 0xFF0D0A1A.toInt(), 0xFF201A3D.toInt(), 0xFFA78BFA.toInt()),
        ThemeDef("green", "Matrix Green", R.style.Theme_Zix_Green, 0xFF050E09.toInt(), 0xFF10261A.toInt(), 0xFF4ADE80.toInt()),
        ThemeDef("orange", "Sunset", R.style.Theme_Zix_Orange, 0xFF140C08.toInt(), 0xFF2B1B11.toInt(), 0xFFFB923C.toInt()),
        ThemeDef("rose", "Rose", R.style.Theme_Zix_Rose, 0xFF14090E.toInt(), 0xFF2D1822.toInt(), 0xFFFB7185.toInt()),
        ThemeDef("amoled", "AMOLED Black", R.style.Theme_Zix_Amoled, 0xFF000000.toInt(), 0xFF16161A.toInt(), 0xFF38BDF8.toInt()),
        ThemeDef("dynamic", "Material You", R.style.Theme_Zix_Dynamic, 0xFF1B1B1F.toInt(), 0xFF303034.toInt(), 0xFFA8C7FA.toInt()),
        ThemeDef("light", "Terang", R.style.Theme_Zix_Light, 0xFFF3F6FB.toInt(), 0xFFFFFFFF.toInt(), 0xFF0891B2.toInt())
    )

    /** Material You hanya ada di Android 12+. */
    fun available(id: String): Boolean = id != "dynamic" || android.os.Build.VERSION.SDK_INT >= 31

    fun byId(id: String): ThemeDef =
        all.firstOrNull { it.id == id && available(it.id) } ?: all[0]
}

/** Ambil warna dari atribut tema aktif (mis. R.attr.zAccent). */
fun Context.zColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}
