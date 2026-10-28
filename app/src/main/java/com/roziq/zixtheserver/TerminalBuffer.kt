package com.roziq.zixtheserver

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * Penampung output terminal sederhana. Mendukung warna ANSI (SGR: 16 warna,
 * 256 warna, truecolor), serta menangani \r / backspace sehingga progress bar
 * (apt, curl, dll.) menimpa barisnya sendiri. Escape lain (kursor, judul) dibuang.
 */
class TerminalBuffer(
    private val maxChars: Int = 60_000,
    private val light: Boolean = false
) {

    private var chars = CharArray(4096)
    private var cols = IntArray(4096)
    private var len = 0

    private var state = ST_NORMAL
    private var carriageReturn = false
    private val csi = StringBuilder()
    private var curFg = 0
    private var bold = false

    private fun append(c: Char) {
        if (len == chars.size) {
            chars = chars.copyOf(len * 2)
            cols = cols.copyOf(len * 2)
        }
        chars[len] = c
        cols[len] = curFg
        len++
    }

    private fun lastNewline(): Int {
        var i = len - 1
        while (i >= 0) {
            if (chars[i] == '\n') return i
            i--
        }
        return -1
    }

    @Synchronized
    fun feed(data: String) {
        for (c in data) {
            when (state) {
                ST_ESC -> state = when (c) {
                    '[' -> {
                        csi.setLength(0)
                        ST_CSI
                    }
                    ']' -> ST_OSC
                    '(', ')' -> ST_SKIP1
                    else -> ST_NORMAL
                }
                ST_CSI -> if (c in '@'..'~') {
                    if (c == 'm') sgr(csi.toString())
                    csi.setLength(0)
                    state = ST_NORMAL
                } else {
                    csi.append(c)
                }
                ST_OSC -> when (c) {
                    '\u0007' -> state = ST_NORMAL
                    '\u001b' -> state = ST_ESC
                }
                ST_SKIP1 -> state = ST_NORMAL
                else -> normal(c)
            }
        }
        if (len > maxChars) {
            val drop = len - maxChars * 3 / 4
            System.arraycopy(chars, drop, chars, 0, len - drop)
            System.arraycopy(cols, drop, cols, 0, len - drop)
            len -= drop
        }
    }

    private fun normal(c: Char) {
        when (c) {
            '\u001b' -> state = ST_ESC
            '\r' -> carriageReturn = true
            '\n' -> {
                carriageReturn = false
                append('\n')
            }
            '\b' -> if (len > 0 && chars[len - 1] != '\n') len--
            else -> {
                if ((c < ' ' && c != '\t') || c == '\u007f') return
                if (carriageReturn) {
                    len = lastNewline() + 1
                    carriageReturn = false
                }
                append(c)
            }
        }
    }

    private fun sgr(params: String) {
        val p = if (params.isEmpty()) listOf("0") else params.split(';')
        var i = 0
        while (i < p.size) {
            val n = p[i].toIntOrNull() ?: 0
            when (n) {
                0 -> {
                    curFg = 0
                    bold = false
                }
                1 -> bold = true
                22 -> bold = false
                39 -> curFg = 0
                in 30..37 -> curFg = palette(n - 30 + if (bold) 8 else 0)
                in 90..97 -> curFg = palette(n - 90 + 8)
                38 -> {
                    val mode = p.getOrNull(i + 1)?.toIntOrNull()
                    if (mode == 5) {
                        val idx = p.getOrNull(i + 2)?.toIntOrNull()
                        if (idx != null) curFg = color256(idx)
                        i += 2
                    } else if (mode == 2) {
                        val r = p.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        val g = p.getOrNull(i + 3)?.toIntOrNull() ?: 0
                        val b = p.getOrNull(i + 4)?.toIntOrNull() ?: 0
                        curFg = 0xFF000000.toInt() or
                            (r.coerceIn(0, 255) shl 16) or
                            (g.coerceIn(0, 255) shl 8) or
                            b.coerceIn(0, 255)
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun palette(i: Int): Int = (if (light) LIGHT else DARK)[i.coerceIn(0, 15)]

    private fun color256(n: Int): Int {
        if (n < 16) return palette(n)
        if (n >= 232) {
            val v = (8 + 10 * (n - 232)).coerceIn(0, 255)
            return 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val i = n - 16
        fun level(x: Int) = if (x == 0) 0 else 55 + 40 * x
        return 0xFF000000.toInt() or (level(i / 36) shl 16) or (level((i / 6) % 6) shl 8) or level(i % 6)
    }

    @Synchronized
    fun text(): String = String(chars, 0, len)

    /** Teks dengan warna ANSI sebagai span, untuk ditampilkan di TextView. */
    @Synchronized
    fun spannable(): SpannableStringBuilder {
        val out = SpannableStringBuilder(String(chars, 0, len))
        var start = 0
        while (start < len) {
            val color = cols[start]
            var end = start + 1
            while (end < len && cols[end] == color) end++
            if (color != 0) {
                out.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = end
        }
        return out
    }

    @Synchronized
    fun clear() {
        len = 0
        carriageReturn = false
        state = ST_NORMAL
        csi.setLength(0)
        curFg = 0
        bold = false
    }

    private companion object {
        const val ST_NORMAL = 0
        const val ST_ESC = 1
        const val ST_CSI = 2
        const val ST_OSC = 3
        const val ST_SKIP1 = 4

        val DARK = intArrayOf(
            0xFF6B7280.toInt(), 0xFFF87171.toInt(), 0xFF4ADE80.toInt(), 0xFFFACC15.toInt(),
            0xFF60A5FA.toInt(), 0xFFC084FC.toInt(), 0xFF22D3EE.toInt(), 0xFFE5E7EB.toInt(),
            0xFF9CA3AF.toInt(), 0xFFFCA5A5.toInt(), 0xFF86EFAC.toInt(), 0xFFFDE047.toInt(),
            0xFF93C5FD.toInt(), 0xFFD8B4FE.toInt(), 0xFF67E8F9.toInt(), 0xFFFFFFFF.toInt()
        )
        val LIGHT = intArrayOf(
            0xFF111827.toInt(), 0xFFB91C1C.toInt(), 0xFF15803D.toInt(), 0xFFA16207.toInt(),
            0xFF1D4ED8.toInt(), 0xFF7E22CE.toInt(), 0xFF0E7490.toInt(), 0xFF4B5563.toInt(),
            0xFF6B7280.toInt(), 0xFFDC2626.toInt(), 0xFF16A34A.toInt(), 0xFFCA8A04.toInt(),
            0xFF2563EB.toInt(), 0xFF9333EA.toInt(), 0xFF0891B2.toInt(), 0xFF111827.toInt()
        )
    }
}
