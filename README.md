# ZixTheServer

Aplikasi Android (Kotlin) untuk mengontrol server Linux lewat SSH dan memasang
Pterodactyl (Panel + Wings) dari HP.

## Fitur (v2.2)

**Dasar:** 8 tema (termasuk Material You), animasi loading, ikon adaptive, update dalam aplikasi dari GitHub Releases,
bahasa Indonesia/English (menu Pengaturan), backup daftar server (bisa dienkripsi passphrase).

**Server:** cari, grup/tag, favorit + pintasan ikon, ping online/offline, jump host, Wake-on-LAN,
pasang SSH key otomatis, layout 2 kolom di layar lebar.

**Dashboard:** gauge + grafik CPU/RAM, uptime 7 hari, 80+ aksi cepat (cari, lipat, favorit, perintah sendiri
dengan `{variabel}`), dan **Alat** server: Proses, Layanan systemd, Docker, Firewall UFW, Cron, Update paket,
Log viewer, Login SSH gagal.

**Terminal:** multi-tab, warna ANSI, cari di output, skema warna (Dracula, Nord, ...), riwayat, bagikan output.

**File manager SFTP:** jelajah, unduh, unggah, edit teks, ganti nama, hapus.

**Pterodactyl (Client API):** daftar game server + grafik, Start/Restart/Stop/Kill, **konsol live**, kirim perintah,
backup (buat/unduh/restore/hapus), jadwal Panel, edit file (mis. `server.properties`).

**Otomasi:** jadwal perintah (di latar), jalankan satu perintah ke banyak server, monitor website/port + masa
berlaku SSL, alert CPU/RAM/disk/layanan mati, notifikasi server offline/pulih, widget layar utama.

**Keamanan:** kunci aplikasi (sidik jari/wajah/PIN layar), blokir screenshot, hapus data darurat (opsional).

## Deploy & update lewat GitHub

1. Upload isi folder ini ke repo GitHub **publik**.
2. Setiap `git push` ke `main`, GitHub Actions otomatis build APK dan membuat **Release** baru
   (`ZixTheServer-vX.Y.N.apk`).
3. Di HP: buka aplikasi > muncul dialog update (atau Pengaturan > Cek update sekarang) > Update > Pasang.
   Repo terisi otomatis dari build; bisa diubah di Pengaturan.

Naikkan versi besar dengan mengubah `APP_VERSION_BASE` di `gradle.properties`.

### Tanda tangan (penting)
APK baru hanya bisa menimpa yang lama bila **tanda tangannya sama**. Bawaan: `app/zix-release.jks`
ada di repo (praktis, tapi siapa pun yang bisa membaca repo bisa memakai kunci itu). Lebih aman:
buat keystore sendiri lalu isi GitHub Secrets `ZIX_KEYSTORE_BASE64`, `ZIX_KEYSTORE_PASSWORD`,
`ZIX_KEY_ALIAS`, `ZIX_KEY_PASSWORD`. Ganti keystore = pengguna harus install ulang sekali.

> Versi 1.x ditandatangani kunci lain, jadi **uninstall v1 sekali** sebelum memasang v2
> (daftar server v1 ikut terhapus; catat dulu).

## Build manual
JDK 17 + Android SDK 34: `gradle assembleRelease` (atau buka di Android Studio).

## Catatan penting
- Aksi instalasi butuh **root** (login root atau `sudo -i`).
- `pterodactyl-installer.se` adalah skrip komunitas, bukan resmi.
- Update Panel/Wings: backup database dan `.env` dulu.
- Setelah server di-install ulang, fingerprint berubah: pakai **Reset host key**.
- File backup server berisi password/private key polos; simpan aman.
- Pemantau latar & jadwal memakai WorkManager: waktunya perkiraan (minimal 15 menit, bisa tertunda mode hemat baterai).
- Hapus data darurat hanya menghitung kegagalan **biometrik**, bukan PIN layar.
- Nama aksi cepat dan alat server masih berbahasa Indonesia; menu/dialog utama sudah dua bahasa.
