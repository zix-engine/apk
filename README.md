# ZixTheServer

Aplikasi Android (Kotlin) untuk mengontrol server Linux lewat SSH dan memasang
Pterodactyl (Panel + Wings) langsung dari HP.

## Fitur

- **Daftar server**: nama, host/IP, port, username, password atau private key.
  Kredensial disimpan terenkripsi (Android Keystore / EncryptedSharedPreferences).
- **Dashboard server**: hostname, OS, uptime, load CPU, RAM, disk, dan status
  layanan (nginx, mariadb/mysql, redis, docker, wings, pteroq).
- **Aksi cepat** (dijalankan di terminal sehingga semua output terlihat):
  - Pterodactyl: install Panel/Wings (installer komunitas pterodactyl-installer.se),
    install Docker, status/restart/log Panel & Wings, buat user admin,
    bersihkan cache, update Wings, update Panel.
  - Docker: daftar container/image, stats, prune.
  - Sistem: update paket, tool dasar, cek resource, port listening, swap 2 GB,
    firewall UFW untuk Pterodactyl, Fail2ban, ganti password, reboot, shutdown.
  - Web & SSL: Certbot nginx, tes/reload nginx, daftar sertifikat.
- **Terminal interaktif** (PTY): ketik perintah/jawaban prompt, tombol cepat
  (Enter, Ctrl+C, Ctrl+D, Tab, panah, y, n), input rahasia untuk password,
  salin output.
- **Sesi terjaga**: foreground service + wake lock selama terminal terbuka,
  supaya instalasi panjang tidak mati saat layar padam.
- **Verifikasi host key** (trust-on-first-use): koneksi ditolak jika
  fingerprint server berubah.

## Cara build

### Opsi 1 - Android Studio
1. Ekstrak zip, lalu **File > Open** pilih folder `ZixTheServer`.
2. Tunggu Gradle sync (butuh JDK 17, SDK Platform 34).
3. **Build > Build APK(s)**. Hasil: `app/build/outputs/apk/debug/app-debug.apk`.

### Opsi 2 - GitHub Actions (tanpa PC)
1. Upload isi folder ini ke repo GitHub.
2. Buka tab **Actions > Build APK > Run workflow**.
3. Unduh artifact `ZixTheServer-debug` berisi APK.

### Opsi 3 - Command line
Wrapper sudah disertakan (Gradle 8.7). Pastikan JDK 17 dan Android SDK
(`ANDROID_HOME`, platform 34) terpasang, lalu:

```
chmod +x gradlew
./gradlew assembleDebug
```

> Proyek memakai Android Gradle Plugin 8.5.2 + Kotlin 1.9.24 + ViewBinding,
> sehingga tidak bisa dibuild di AIDE; gunakan opsi di atas.

## Catatan penting

- Aksi instalasi butuh **root**. Login sebagai root, atau jalankan `sudo -i`
  di Terminal bila memakai user biasa.
- `pterodactyl-installer.se` adalah skrip komunitas (bukan resmi dari
  Pterodactyl). Ia menjalankan menu interaktif; jawab prompt lewat Terminal.
  Gunakan VPS/OS bersih (Ubuntu 22.04/24.04 atau Debian 11/12).
- Firewall UFW membuka port SSH server ini, 80, 443, 8080, dan 2022. Port
  server game harus ditambahkan manual.
- Update Panel/Wings: **backup database dan `.env` dulu**.
- Jika Keystore perangkat gagal dipakai, penyimpanan otomatis jatuh ke
  SharedPreferences biasa (tidak terenkripsi). Pakai private key + passphrase
  jika khawatir.
- Setelah server di-install ulang, fingerprint berubah: pilih menu
  **Reset host key** pada server tersebut.

## Struktur

```
app/src/main/java/com/roziq/zixtheserver/
  MainActivity.kt      daftar server + tambah/edit/hapus
  ServerActivity.kt    dashboard + aksi cepat
  TerminalActivity.kt  terminal SSH interaktif
  Actions.kt           katalog perintah aksi cepat (ubah/tambah di sini)
  Ssh.kt               koneksi JSch + verifikasi host key
  TerminalBuffer.kt    pembersih ANSI / carriage return
  ServerStore.kt       penyimpanan terenkripsi
  KeepAliveService.kt  foreground service + wake lock
app/src/main/assets/stats.sh   skrip statistik server
```
