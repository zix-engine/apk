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
    val hostKey: String = "",
    val favorite: Boolean = false,
    /** Waktu terakhir server dibuka (epoch ms), untuk pengurutan. */
    val lastUsed: Long = 0L,
    /** URL Panel Pterodactyl (mis. https://panel.contoh.com) dan Client API key (ptlc_...). */
    val panelUrl: String = "",
    val panelKey: String = "",
    /** Nama grup/tag untuk pengelompokan di daftar. */
    val group: String = "",
    /** MAC address untuk Wake-on-LAN (opsional). */
    val mac: String = "",
    /** ID server lain yang dipakai sebagai jump host (opsional). */
    val jumpId: String = "",
    /** Aktifkan alert CPU/RAM/disk/layanan di pemantau latar. */
    val alerts: Boolean = false
)
