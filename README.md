# ZixFunction

Pembaruan dari **ZixTheServer**: aplikasi Android (Kotlin) untuk mengelola server (SSH, SFTP, Docker, Pterodactyl, monitor)
ditambah modul **trading lokal** (kalkulator, backtest, jurnal) dan **Full Backup & Restore**.
Status lengkap tiap bagian spesifikasi: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

> Build Android **belum diverifikasi** oleh pembuatnya (tidak ada Android SDK di lingkungan pembuatan). Hasil build pertama di GitHub Actions yang menentukan.

## Struktur proyek
```
app/                    modul Android (package com.roziq.zixtheserver — dipertahankan agar bisa update dari versi lama)
  src/main/java/...     Activity, SSH/SFTP, Tools, Trading (ZixDb, TradeStats, TradeCalc), FullBackup, dst.
  src/main/res/         layout, tema (Indigo default), string (ID + EN), ikon
  src/test/             unit test statistik & kalkulator
.github/workflows/      build APK + rilis otomatis
IMPLEMENTATION_STATUS.md
```
Backend (Node/MySQL) **belum ada** di versi ini.

## Build
**GitHub Actions (disarankan):** push ke repo **publik**; workflow `Build & Release APK` membangun APK dan membuat Release
(`ZixFunction-vX.Y.N.apk`). Update dalam aplikasi membaca Release itu.

**AIDE Pro:** kemungkinan besar **tidak kompatibel** (proyek memakai Gradle 8/AGP 8, View Binding, Material 1.12, WorkManager, Kotlin).
Tidak diverifikasi. Pakai GitHub Actions atau Android Studio.

**Android Studio:** JDK 17, Android SDK 34 → `gradle assembleRelease`.

### Tanda tangan
Kunci tidak disimpan di repo. Isi GitHub Secrets: `ZIX_KEYSTORE_BASE64`, `ZIX_KEYSTORE_PASSWORD`, `ZIX_KEY_ALIAS`, `ZIX_KEY_PASSWORD`.
Tanpa Secrets, APK memakai kunci debug: bisa dipasang baru tetapi **tidak bisa menimpa** instalasi lama (workflow memberi peringatan).
Buat keystore: `keytool -genkeypair -v -keystore zix.jks -alias zix -keyalg RSA -keysize 2048 -validity 36500`,
lalu `base64 -w0 zix.jks` → isi `ZIX_KEYSTORE_BASE64`. Jangan commit file `.jks`.

## Backend, MySQL/MariaDB, migration, Owner pertama, endpoint API
Belum ada di versi ini (lihat status). Rencananya ada di `IMPLEMENTATION_STATUS.md`.

## Backup & restore
Menu ⋮ › **Full Backup & Restore** (atau Pengaturan). Satu ZIP berisi `manifest.json` (versi, kategori, checksum SHA-256) dan
`servers/settings/trading`. Dengan **passphrase**: dienkripsi AES-256-GCM (PBKDF2) dan kredensial server ikut. **Tanpa passphrase**:
kredensial dikosongkan. Restore memverifikasi checksum, menampilkan pratinjau, membuat cadangan pengaman, lalu memilih **Gabung**
atau **Ganti semua** (data trading). Laporan menyebut apa yang termasuk dan tidak termasuk.

## Yang benar-benar ada
Server & SSH multi-tab (warna ANSI), SFTP, alat server (proses/layanan/Docker/UFW/cron/update/log/login gagal), Pterodactyl
(daftar, power, konsol live, backup, jadwal, edit file), jump host, Wake-on-LAN, SSH key otomatis, monitor + alert, jadwal perintah,
widget, kunci aplikasi, update dalam aplikasi, **Trading**: 12 kalkulator, backtest, jurnal, statistik, equity curve, ekspor.

## Yang masih butuh server/API/pekerjaan lanjutan
Login & manajemen akun, backend & database, market data/chart/berita, AI Assistant, backup data backend, RBAC/audit/2FA.

## Catatan keamanan
- Jangan commit `.env`, keystore, token, atau API key. `.gitignore` sudah memblokirnya.
- File backup tanpa passphrase tidak berisi kredensial; file dengan passphrase berisi kredensial terenkripsi — jaga passphrase-nya.
- Kalkulator dan statistik adalah alat bantu hitung, bukan saran trading atau jaminan hasil.
