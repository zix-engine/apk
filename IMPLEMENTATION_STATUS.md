# ZixFunction — Status Implementasi

Dokumen ini melacak SEMUA bagian spesifikasi ZixFunction. Status jujur per bagian; tidak ada klaim "selesai" untuk
sesuatu yang belum ada. **Build Android belum diverifikasi** (lihat bagian Pengujian). Jumlah "320 fitur roadmap"
tidak diklaim selesai — yang tertera di bawah adalah yang benar-benar ada di kode.

Legenda: ✅ ada di kode • 🟡 sebagian • ⛔ belum dikerjakan (butuh backend/server/API key/pekerjaan lanjutan)

## Versi 3.0.0-alpha — yang dikerjakan di rilis ini
- Rebrand ke **ZixFunction** (package/applicationId `com.roziq.zixtheserver` tetap → bisa update dari versi lama).
- Tema baru **Zix Indigo** (default instalasi baru; tema lama tetap tersedia).
- **Modul Trading lokal (offline)**: 12 kalkulator, backtest, jurnal, statistik, equity curve, ekspor CSV/JSON.
- **Full Backup & Full Restore lokal**: ZIP + manifest + checksum SHA-256 + enkripsi passphrase + pratinjau + cadangan pengaman.
- Unit test untuk statistik & kalkulator (ditulis; **belum dijalankan** di lingkungan pembuatan).
- Keystore dikeluarkan dari source; tanda tangan lewat GitHub Secrets.

## Status per bagian spesifikasi
| # | Bagian | Status | Catatan |
|---|--------|--------|---------|
| 1 | Identitas & tujuan | ✅ | Nama ZixFunction, ID aplikasi dipertahankan, fitur lama tetap |
| 2 | UI/UX baru | 🟡 | Tema Indigo, animasi, kartu; belum ada bottom navigation/halaman beranda terpadu |
| 3 | Splash & pemulihan state | 🟡 | Splash animasi ada & tidak muncul ulang saat kembali dari latar; pemulihan state setelah proses dimatikan sistem belum dibuat |
| 4 | Login & autentikasi Owner/Admin/User | ⛔ | Butuh backend |
| 5 | Dashboard Owner | ⛔ | Butuh backend |
| 6 | Dashboard User & hak akses | ⛔ | Butuh backend |
| 7 | Create Account oleh Owner | ⛔ | Butuh backend |
| 8 | Ganti/reset password | ⛔ | Butuh backend |
| 9 | Database & backend | ⛔ | Hanya SQLite lokal untuk trading. Backend Node/MySQL belum dibuat (tidak bisa diuji di lingkungan ini) |
| 10 | Server, VPS, NAT, SSH, Docker, Pterodactyl | 🟡 | ✅ SSH multi-tab, SFTP, Docker/UFW/cron/proses/layanan/log, Pterodactyl API+konsol, jump host, WoL, monitor. ⛔ NAT diagnostics, audit log server |
| 11 | Modul trading | 🟡 | ✅ 12 kalkulator, jurnal. ⛔ market/watchlist/chart (butuh sumber data) |
| 12 | Backtest & rekap statistik | ✅ | Manual-entry: win rate, net R, expectancy, PF, max DD, streak, per bulan/instrumen/TF/setup/sesi, perbandingan proyek, ekspor CSV/JSON. ⛔ impor CSV, lampiran gambar, PDF |
| 13 | Market intelligence & notifikasi | ⛔ | Butuh API data pasar |
| 14 | AI Assistant | ⛔ | Butuh backend + API key penyedia |
| 15 | Full Backup & Restore | 🟡 | ✅ lokal lengkap (server, pengaturan, trading). ⛔ data backend, backup otomatis/pengingat |
| 16 | Keamanan | 🟡 | ✅ kunci aplikasi biometrik/PIN, FLAG_SECURE, kredensial terenkripsi, backup terenkripsi, hapus data darurat. ⛔ RBAC, audit log, 2FA, rate limit (butuh backend) |
| 17 | Developer / GitHub / build | ✅ | Actions build+release, update dalam aplikasi, tes CI (non-blokir) |
| 18 | Preferensi & pendukung | 🟡 | Tema, bahasa ID/EN, font, haptic, dll. |
| 19 | Roadmap terpadu | 🟡 | Lihat tabel di atas; jumlah fitur tidak diklaim |
| 20 | Arsitektur & pengujian | 🟡 | Modular per modul; tes ditulis tapi belum dijalankan |
| 21 | Prioritas | 🟡 | Tahap 1 (fondasi UI/branding) sebagian; Tahap 3 & 4 bagian lokal dikerjakan lebih dulu karena bisa dibuat tanpa server |
| 22 | Definisi Full Backup | 🟡 | Dipenuhi untuk data lokal; kategori backend dinyatakan "belum ada" di manifest & laporan |

## Rencana lanjutan (butuh keputusan/sumber daya darimu)
1. **Backend** (Node.js + Express + MariaDB/MySQL): migrasi SQL, hash password (argon2id/bcrypt), JWT access+refresh
   dengan rotasi, RBAC Owner/Admin/User, audit log, rate limit, setup Owner pertama lewat CLI sekali pakai.
   Perlu: VPS/domain/HTTPS dan pilihan DB. Tidak bisa diuji di lingkungan pembuatan (tanpa paket npm & DB).
2. Android: layar login, session aman, dashboard Owner/User, manajemen akun, mode "lokal" tetap tersedia.
3. Market data & chart (butuh penyedia data/lisensi), kalender ekonomi, alert harga.
4. AI Assistant lewat backend (API key hanya di server).
5. Backup yang menyertakan data backend + pengingat/penjadwalan.

## Pengujian
- Ditulis: `app/src/test/.../TradeStatsTest.kt`, `TradeCalcTest.kt` (JUnit 4).
- Rumus statistik/kalkulator diperiksa dengan cermin Python (bukan menjalankan Kotlin); ini menemukan satu bug
  pembulatan lot (floating point) yang sudah diperbaiki.
- **Belum dijalankan**: kompilasi Android, unit test Kotlin, dan uji di perangkat. CI GitHub menjalankan tes (non-blokir)
  dan build; hasilnya yang menentukan.
