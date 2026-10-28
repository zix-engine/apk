package com.roziq.zixtheserver

/** Skema warna terminal. "theme" = ikuti tema aplikasi. */
object TermSchemes {

    data class Scheme(val id: String, val name: String, val bg: Int, val fg: Int)

    val all = listOf(
        Scheme("theme", "Ikuti tema aplikasi", 0, 0),
        Scheme("dracula", "Dracula", 0xFF282A36.toInt(), 0xFFF8F8F2.toInt()),
        Scheme("nord", "Nord", 0xFF2E3440.toInt(), 0xFFD8DEE9.toInt()),
        Scheme("solarized", "Solarized Dark", 0xFF002B36.toInt(), 0xFF93A1A1.toInt()),
        Scheme("monokai", "Monokai", 0xFF272822.toInt(), 0xFFF8F8F2.toInt()),
        Scheme("gruvbox", "Gruvbox", 0xFF282828.toInt(), 0xFFEBDBB2.toInt()),
        Scheme("paper", "Kertas (terang)", 0xFFFFFFFF.toInt(), 0xFF24292E.toInt())
    )

    fun byId(id: String): Scheme = all.firstOrNull { it.id == id } ?: all[0]
}
