# ZixFunction API (backend)

Node.js (tanpa framework) + MariaDB/MySQL. Fitur: login, sesi (access + refresh token dengan rotasi), akun Owner/Admin/User,
izin per akun (RBAC), reset/ganti password, penguncian login, rate limit, audit log.

**Status pengujian:** logika (auth, RBAC, audit, transaksi, CLI Owner) diuji otomatis — 22 tes lolos
(`npm test`, Node ≥ 22, memakai SQLite in-memory dengan SQL yang sama). **Belum diuji**: koneksi nyata ke MariaDB (adapter `src/db.js`),
skrip `deploy/install-ubuntu.sh` di VPS sungguhan, dan nginx/Let's Encrypt. Laporkan bila ada error saat pemasangan.

## Pasang di VPS Ubuntu 22.04/24.04
```bash
# di VPS (sebagai root / sudo)
git clone https://github.com/USERNAME/REPO.git && cd REPO/backend
sudo bash deploy/install-ubuntu.sh
```
Skrip menanyakan mode HTTPS:
1. **Domain** (disarankan): nginx + sertifikat Let's Encrypt otomatis. A-record domain harus sudah mengarah ke IP VPS.
2. **Hanya IP**: sertifikat self-signed di port **8443**; skrip mencetak sidik jari SHA-256-nya. Di aplikasi, cocokkan sidik jari itu lalu pilih "Sama, percayai".

Skrip: memasang nginx + MariaDB + Node 20, membuat user sistem `zixapi`, database `zixfunction` dengan password acak (disimpan di
`/opt/zixfunction-api/.env`, izin 600), menjalankan migrasi, membuat layanan systemd `zixfunction-api`, mengatur nginx, lalu menawarkan membuat **Owner pertama**.
Aman dijalankan ulang untuk memperbarui kode (`.env` dan database dipertahankan).

### Owner pertama
Tidak ada endpoint HTTP untuk membuat Owner (sengaja). Hanya lewat CLI di server:
```bash
sudo runuser -u zixapi -- node /opt/zixfunction-api/scripts/create-owner.js
```
Menolak bila Owner sudah ada. Lupa password Owner? `scripts/reset-password.js` (butuh akses shell server).

## Variabel lingkungan (`.env`, lihat `.env.example`)
`PORT`, `HOST` (default 127.0.0.1), `TRUST_PROXY=1` (di belakang nginx), `DB_*`, `ACCESS_TTL_SEC` (900), `REFRESH_TTL_DAYS` (30), `SCRYPT_N`.
Jangan commit `.env`.

## Endpoint
| Method & path | Akses | Fungsi |
|---|---|---|
| `GET /health` | publik | cek hidup |
| `POST /api/auth/login` | publik (rate limit + kunci akun) | `{username,password,device}` → token |
| `POST /api/auth/refresh` | publik | tukar refresh token (rotasi; token lama tidak berlaku) |
| `POST /api/auth/logout` | login | cabut sesi ini |
| `GET /api/me` | login | profil + izin |
| `POST /api/me/password` | login | ganti password (cek password lama; sesi lain dicabut) |
| `GET /api/me/sessions`, `DELETE /api/me/sessions/:id` | login | daftar/cabut sesi sendiri |
| `GET/POST /api/users` | Owner/Admin | daftar / buat akun (password sementara acak, tampil sekali) |
| `GET/PATCH/DELETE /api/users/:id` | Owner/Admin* | detail / ubah / hapus (*hapus: Owner saja) |
| `POST /api/users/:id/reset-password` | Owner/Admin* | password sementara baru, sesi dicabut |
| `GET /api/audit` | Owner / izin `audit.view` | audit log |

Aturan: Admin hanya mengelola `user`; Owner tidak bisa dinonaktifkan/dihapus/diubah lewat API (termasuk oleh Owner lain); role `owner`
tidak bisa dibuat lewat API; akun dengan password sementara hanya boleh mengganti password sampai selesai.
Kebijakan password: 10–128 karakter, huruf + angka, tidak memuat username, bukan password umum. Hash: scrypt (N=32768).
Token disimpan di database hanya sebagai hash SHA-256. Pesan login tidak membocorkan apakah username ada.

## Izin (RBAC)
`server.view`, `server.manage`, `trading.use`, `backup.use`, `ai.use`, `audit.view`. Owner otomatis punya semuanya.

## Backup database
`mysqldump zixfunction > zixfunction-$(date +%F).sql` (jalankan sebagai root). Simpan di luar VPS.

## Pengembangan & tes
```bash
cd backend && npm test          # Node >= 22 (node:sqlite) — tidak butuh MariaDB
npm install && npm run migrate  # butuh .env + MariaDB
```
