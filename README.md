# ZixTheServer

Aplikasi Android (Kotlin) untuk mengontrol server Linux lewat SSH dan memasang
Pterodactyl (Panel + Wings) dari HP.

## Fitur (v2)

- **7 tema** (Cyber Cyan, Neon Violet, Matrix Green, Sunset, Rose, AMOLED, Terang), ganti di Pengaturan.
- **Animasi loading** saat aplikasi dibuka (bisa dimatikan), ikon adaptive baru, transisi antar layar halus.
- **Daftar server**: cari, favorit, status online + ping, tarik untuk refresh, duplikat, salin perintah SSH.
- **Dashboard**: gauge CPU/RAM/disk live, auto-refresh, status layanan, jumlah container Docker.
- **80+ aksi cepat** (Pterodactyl, Docker, Sistem, Jaringan, Database, Dev & Game, Keamanan, Web & SSL):
  bisa dicari, dilipat per kategori, ditandai favorit (tekan lama), dan **perintah buatan sendiri**.
- **Terminal**: riwayat perintah, bagikan output, zoom font, sambung ulang, tombol Ctrl/panah/simbol lengkap.
- **Backup/Restore** daftar server, getar halus, layar tetap menyala.
- **Update dalam aplikasi** dari GitHub Releases.

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
