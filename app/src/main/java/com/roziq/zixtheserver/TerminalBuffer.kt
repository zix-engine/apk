package com.roziq.zixtheserver

/**
 * Penampung output terminal sederhana: membuang escape sequence ANSI
 * (warna, posisi kursor, judul jendela) dan menangani \r / backspace
 * sehingga progress bar (apt, curl, dll.) menimpa barisnya sendiri.
 */
class TerminalBuffer(private val maxChars: Int = 60_000) {

    private val sb = StringBuilder()
    private var state = ST_NORMAL
    private var carriageReturn = false

    @Synchronized
    fun feed(data: String) {
        for (c in data) {
            when (state) {
                ST_ESC -> state = when (c) {
                    '[' -> ST_CSI
                    ']' -> ST_OSC
                    '(', ')' -> ST_SKIP1
                    else -> ST_NORMAL
                }
                ST_CSI -> if (c in '@'..'~') state = ST_NORMAL
                ST_OSC -> when (c) {
                    '\u0007' -> state = ST_NORMAL
                    '\u001b' -> state = ST_ESC
                }
                ST_SKIP1 -> state = ST_NORMAL
                else -> normal(c)
            }
        }
        if (sb.length > maxChars) {
            sb.delete(0, sb.length - maxChars * 3 / 4)
        }
    }

    private fun normal(c: Char) {
        when (c) {
            '\u001b' -> state = ST_ESC
            '\r' -> carriageReturn = true
            '\n' -> {
                carriageReturn = false
                sb.append('\n')
            }
            '\b' -> if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') {
                sb.setLength(sb.length - 1)
            }
            else -> {
                if ((c < ' ' && c != '\t') || c == '\u007f') return
                if (carriageReturn) {
                    sb.setLength(sb.lastIndexOf("\n") + 1)
                    carriageReturn = false
                }
                sb.append(c)
            }
        }
    }

    @Synchronized
    fun text(): String = sb.toString()

    @Synchronized
    fun clear() {
        sb.setLength(0)
        carriageReturn = false
        state = ST_NORMAL
    }

    private companion object {
        const val ST_NORMAL = 0
        const val ST_ESC = 1
        const val ST_CSI = 2
        const val ST_OSC = 3
        const val ST_SKIP1 = 4
    }
}
