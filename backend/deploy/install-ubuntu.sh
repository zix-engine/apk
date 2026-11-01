#!/usr/bin/env bash
# Pasang ZixFunction API di Ubuntu 22.04/24.04 (butuh root). Aman dijalankan ulang (idempotent).
# Pemakaian:  sudo bash deploy/install-ubuntu.sh
set -euo pipefail

APP_DIR=/opt/zixfunction-api
APP_USER=zixapi
DB_NAME=zixfunction
DB_USER=zixapi
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mGagal: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Jalankan sebagai root: sudo bash deploy/install-ubuntu.sh"
. /etc/os-release
case "${ID:-}" in ubuntu|debian) ;; *) die "Skrip ini untuk Ubuntu/Debian (terdeteksi: ${ID:-?})";; esac
[ -f "$SRC_DIR/package.json" ] || die "package.json tidak ditemukan di $SRC_DIR"

echo "Mode HTTPS:"
echo "  1) Pakai DOMAIN (disarankan; sertifikat Let's Encrypt otomatis)"
echo "  2) Hanya IP (sertifikat self-signed di port 8443; aplikasi 'mengunci' sidik jarinya)"
read -r -p "Pilih [1/2]: " MODE
DOMAIN=""; EMAIL=""
if [ "$MODE" = "1" ]; then
  read -r -p "Domain/subdomain API (mis. api.contoh.com, A-record sudah mengarah ke VPS ini): " DOMAIN
  [ -n "$DOMAIN" ] || die "Domain kosong"
  read -r -p "Email untuk Let's Encrypt: " EMAIL
  [ -n "$EMAIL" ] || die "Email kosong"
elif [ "$MODE" != "2" ]; then
  die "Pilihan tidak valid"
fi

say "Memasang paket sistem"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y nginx mariadb-server curl openssl ca-certificates gnupg rsync
[ "$MODE" = "1" ] && apt-get install -y certbot python3-certbot-nginx

say "Memeriksa Node.js (butuh >= 18)"
NODE_OK=0
if command -v node >/dev/null 2>&1; then
  [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 18 ] && NODE_OK=1
fi
if [ "$NODE_OK" -eq 0 ]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
node -v

say "Menyiapkan user & folder aplikasi"
id "$APP_USER" >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
mkdir -p "$APP_DIR"
rsync -a --delete --exclude node_modules --exclude .env --exclude tests "$SRC_DIR"/ "$APP_DIR"/
cd "$APP_DIR"
npm install --omit=dev --no-audit --no-fund

say "Menyiapkan database MariaDB"
systemctl enable --now mariadb
if [ ! -f "$APP_DIR/.env" ]; then
  DB_PASS="$(openssl rand -hex 24)"
  mysql <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASS';
ALTER USER '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASS';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'localhost';
FLUSH PRIVILEGES;
SQL
  umask 077
  cat > "$APP_DIR/.env" <<ENV
PORT=3000
HOST=127.0.0.1
TRUST_PROXY=1
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=$DB_NAME
DB_USER=$DB_USER
DB_PASSWORD=$DB_PASS
ENV
  echo ".env dibuat dengan password database acak (hanya terbaca oleh $APP_USER)."
else
  echo ".env sudah ada — dipertahankan."
fi
chown -R "$APP_USER":"$APP_USER" "$APP_DIR"
chmod 600 "$APP_DIR/.env"

say "Menjalankan migrasi database"
runuser -u "$APP_USER" -- node "$APP_DIR/scripts/migrate.js"

say "Membuat layanan systemd"
cat > /etc/systemd/system/zixfunction-api.service <<UNIT
[Unit]
Description=ZixFunction API
After=network.target mariadb.service
Wants=mariadb.service

[Service]
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$APP_DIR
ExecStart=$(command -v node) $APP_DIR/src/server.js
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now zixfunction-api
sleep 2
systemctl is-active --quiet zixfunction-api || { journalctl -u zixfunction-api -n 30 --no-pager; die "Layanan tidak berjalan"; }
curl -fsS http://127.0.0.1:3000/health >/dev/null || die "Health check gagal"

PROXY_BLOCK='
    client_max_body_size 128k;
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 30s;
    }'

if [ "$MODE" = "1" ]; then
  say "Mengatur nginx + HTTPS untuk $DOMAIN"
  cat > /etc/nginx/sites-available/zixfunction-api <<NGX
server {
    listen 80;
    server_name $DOMAIN;
$PROXY_BLOCK
}
NGX
  ln -sf /etc/nginx/sites-available/zixfunction-api /etc/nginx/sites-enabled/zixfunction-api
  nginx -t
  systemctl reload nginx
  certbot --nginx -d "$DOMAIN" -m "$EMAIL" --agree-tos --no-eff-email --redirect --non-interactive
  API_URL="https://$DOMAIN"
  FP_NOTE="Sertifikat resmi Let's Encrypt — tidak perlu sidik jari."
else
  say "Mengatur nginx + sertifikat self-signed (port 8443)"
  IP="$(curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
  mkdir -p /etc/zixfunction
  if [ ! -f /etc/zixfunction/api.crt ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 825 -keyout /etc/zixfunction/api.key -out /etc/zixfunction/api.crt \
      -subj "/CN=$IP" -addext "subjectAltName=IP:$IP"
    chmod 600 /etc/zixfunction/api.key
  fi
  cat > /etc/nginx/sites-available/zixfunction-api <<NGX
server {
    listen 8443 ssl;
    server_name _;
    ssl_certificate /etc/zixfunction/api.crt;
    ssl_certificate_key /etc/zixfunction/api.key;
    ssl_protocols TLSv1.2 TLSv1.3;
$PROXY_BLOCK
}
NGX
  ln -sf /etc/nginx/sites-available/zixfunction-api /etc/nginx/sites-enabled/zixfunction-api
  nginx -t
  systemctl reload nginx
  API_URL="https://$IP:8443"
  FP="$(openssl x509 -in /etc/zixfunction/api.crt -noout -fingerprint -sha256 | cut -d= -f2)"
  FP_NOTE="Sidik jari sertifikat (SHA-256) — cocokkan dengan yang ditampilkan aplikasi:\n  $FP"
fi

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  if [ "$MODE" = "1" ]; then ufw allow 'Nginx Full' >/dev/null || true; else ufw allow 8443/tcp >/dev/null || true; fi
fi

say "Selesai"
printf 'Alamat API untuk aplikasi : %s\n' "$API_URL"
printf '%b\n' "$FP_NOTE"
echo
read -r -p "Buat akun Owner pertama sekarang? [Y/n]: " YN
if [ "${YN:-Y}" != "n" ] && [ "${YN:-Y}" != "N" ]; then
  runuser -u "$APP_USER" -- node "$APP_DIR/scripts/create-owner.js"
else
  echo "Nanti jalankan:  sudo runuser -u $APP_USER -- node $APP_DIR/scripts/create-owner.js"
fi
echo
echo "Cek status : systemctl status zixfunction-api"
echo "Lihat log  : journalctl -u zixfunction-api -f"
