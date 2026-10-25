package com.roziq.zixtheserver

import java.util.UUID

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "root",
    val password: String = "",
    val privateKey: String = "",
    /** Fingerprint host yang dipercaya saat koneksi pertama (TOFU). */
    val hostKey: String = ""
)
