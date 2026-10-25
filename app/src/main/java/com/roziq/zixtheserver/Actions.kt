package com.roziq.zixtheserver

data class Action(
    val title: String,
    val desc: String,
    val command: String,
    /** Tampilkan dialog konfirmasi sebelum dijalankan. */
    val confirm: Boolean = false
)

data class Section(val title: String, val actions: List<Action>)

/**
 * Katalog aksi cepat. Setiap aksi diketik ke shell interaktif di Terminal,
 * jadi semua output dan prompt terlihat dan bisa dijawab langsung.
 * Placeholder yang didukung: {SSH_PORT}.
 */
object Actions {

    val sections: List<Section> = listOf(
        Section(
            "Pterodactyl", listOf(
                Action(
                    "Install Panel / Wings",
                    "Installer komunitas pterodactyl-installer.se (menu interaktif: Panel, Wings, atau keduanya). Jalankan sebagai root di Ubuntu/Debian yang bersih.",
                    "command -v curl >/dev/null 2>&1 || apt-get install -y curl; curl -sSL https://pterodactyl-installer.se -o /tmp/ptero-install.sh && bash /tmp/ptero-install.sh",
                    confirm = true
                ),
                Action(
                    "Install Docker (untuk Wings)",
                    "Memasang Docker CE dan mengaktifkannya saat boot.",
                    "curl -sSL https://get.docker.com/ | CHANNEL=stable bash && systemctl enable --now docker",
                    confirm = true
                ),
                Action(
                    "Status Panel",
                    "Status nginx dan antrean pteroq.",
                    "systemctl status nginx pteroq --no-pager"
                ),
                Action(
                    "Status Wings",
                    "Status layanan Wings.",
                    "systemctl status wings --no-pager"
                ),
                Action(
                    "Restart Panel",
                    "Restart nginx dan pteroq.",
                    "systemctl restart nginx pteroq && systemctl status nginx pteroq --no-pager",
                    confirm = true
                ),
                Action(
                    "Restart Wings",
                    "Restart Wings (server game yang berjalan akan ikut terputus sebentar).",
                    "systemctl restart wings && systemctl status wings --no-pager",
                    confirm = true
                ),
                Action(
                    "Log Wings",
                    "100 baris terakhir log Wings.",
                    "journalctl -u wings -n 100 --no-pager"
                ),
                Action(
                    "Log Panel",
                    "100 baris terakhir log Laravel Panel.",
                    "ls -t /var/www/pterodactyl/storage/logs/*.log | head -1 | xargs tail -n 100"
                ),
                Action(
                    "Buat user admin Panel",
                    "Menjalankan php artisan p:user:make (interaktif).",
                    "cd /var/www/pterodactyl && php artisan p:user:make"
                ),
                Action(
                    "Bersihkan cache Panel",
                    "php artisan optimize:clear.",
                    "cd /var/www/pterodactyl && php artisan optimize:clear"
                ),
                Action(
                    "Cek versi Wings",
                    "Menampilkan versi Wings yang terpasang.",
                    "wings --version"
                ),
                Action(
                    "Update Wings",
                    "Mengunduh binary Wings terbaru dari GitHub lalu restart. Backup dulu jika perlu.",
                    """systemctl stop wings && curl -L -o /usr/local/bin/wings "https://github.com/pterodactyl/wings/releases/latest/download/wings_linux_$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')" && chmod u+x /usr/local/bin/wings && systemctl restart wings && wings --version""",
                    confirm = true
                ),
                Action(
                    "Update Panel",
                    "Langkah upgrade resmi (maintenance mode, unduh rilis terbaru, composer, migrate). BACKUP database dan .env dulu.",
                    "cd /var/www/pterodactyl && php artisan down && curl -L https://github.com/pterodactyl/panel/releases/latest/download/panel.tar.gz | tar -xzv && chmod -R 755 storage/* bootstrap/cache && composer install --no-dev --optimize-autoloader && php artisan view:clear && php artisan config:clear && php artisan migrate --seed --force && chown -R www-data:www-data /var/www/pterodactyl/* && php artisan queue:restart && php artisan up",
                    confirm = true
                ),
                Action(
                    "Versi & info Panel",
                    "Menampilkan versi Panel dan konfigurasi lingkungannya.",
                    "cd /var/www/pterodactyl && php artisan p:info"
                ),
                Action(
                    "Maintenance Panel ON",
                    "Mengaktifkan mode maintenance (Panel tidak bisa diakses user).",
                    "cd /var/www/pterodactyl && php artisan down",
                    confirm = true
                ),
                Action(
                    "Maintenance Panel OFF",
                    "Mengembalikan Panel ke mode normal.",
                    "cd /var/www/pterodactyl && php artisan up"
                ),
                Action(
                    "Perbaiki permission Panel",
                    "chown www-data + chmod storage dan cache.",
                    "cd /var/www/pterodactyl && chmod -R 755 storage/* bootstrap/cache && chown -R www-data:www-data /var/www/pterodactyl/*",
                    confirm = true
                ),
                Action(
                    "Log Wings live",
                    "Mengikuti log Wings secara langsung. Tekan Ctrl+C untuk berhenti.",
                    "journalctl -u wings -f"
                ),
                Action(
                    "Edit config Wings",
                    "Membuka /etc/pterodactyl/config.yml di nano.",
                    "nano /etc/pterodactyl/config.yml"
                ),
                Action(
                    "Backup Panel (.env + storage)",
                    "Arsip .env dan storage ke /root/backup.",
                    "mkdir -p /root/backup && cd /var/www/pterodactyl && tar -czf /root/backup/panel-files-$(date +%F).tar.gz .env storage && ls -lh /root/backup",
                    confirm = true
                ),
                Action(
                    "Backup database Panel",
                    "mysqldump database 'panel' ke /root/backup (butuh akses root MariaDB).",
                    "mkdir -p /root/backup && mysqldump panel > /root/backup/panel-db-$(date +%F).sql && ls -lh /root/backup",
                    confirm = true
                )
            )
        ),
        Section(
            "Docker", listOf(
                Action("Daftar container", "docker ps -a", "docker ps -a"),
                Action("Pemakaian resource container", "docker stats sekali jalan.", "docker stats --no-stream"),
                Action("Daftar image", "docker images", "docker images"),
                Action("Container berjalan", "docker ps", "docker ps"),
                Action("Pemakaian disk Docker", "docker system df", "docker system df"),
                Action("Daftar volume", "docker volume ls", "docker volume ls"),
                Action("Restart Docker", "systemctl restart docker (container bisa terputus sebentar).", "systemctl restart docker && systemctl status docker --no-pager", confirm = true),
                Action(
                    "Install Docker Compose",
                    "Memasang plugin docker compose.",
                    "apt-get install -y docker-compose-plugin && docker compose version",
                    confirm = true
                ),
                Action(
                    "Bersihkan Docker",
                    "docker system prune -f: hapus container berhenti, network & image dangling.",
                    "docker system prune -f",
                    confirm = true
                )
            )
        ),
        Section(
            "Sistem", listOf(
                Action(
                    "Update paket sistem",
                    "apt / dnf / yum upgrade otomatis sesuai distro.",
                    "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get -y upgrade; elif command -v dnf >/dev/null 2>&1; then dnf -y upgrade; elif command -v yum >/dev/null 2>&1; then yum -y update; else echo 'Package manager tidak dikenali'; fi",
                    confirm = true
                ),
                Action(
                    "Install tool dasar",
                    "curl, wget, git, unzip, htop, nano, screen, ufw (Debian/Ubuntu).",
                    "apt-get update && apt-get install -y curl wget git unzip htop nano screen ufw",
                    confirm = true
                ),
                Action(
                    "Cek resource",
                    "CPU, RAM, dan disk.",
                    "top -bn1 | head -15; echo; free -h; echo; df -h"
                ),
                Action(
                    "Port yang listening",
                    "ss -tulpn",
                    "ss -tulpn"
                ),
                Action(
                    "Proses terberat",
                    "15 proses dengan pemakaian RAM terbesar.",
                    "ps aux --sort=-%mem | head -15"
                ),
                Action(
                    "Buat swap 2 GB",
                    "Membuat /swapfile 2 GB dan mengaktifkannya permanen.",
                    "if [ -f /swapfile ]; then echo '/swapfile sudah ada'; else fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile && echo '/swapfile none swap sw 0 0' >> /etc/fstab && free -h; fi",
                    confirm = true
                ),
                Action(
                    "Firewall UFW untuk Pterodactyl",
                    "Buka port SSH server ini, 80, 443, 8080 (Wings) dan 2022 (SFTP), lalu aktifkan UFW. Port server game harus ditambah manual.",
                    "apt-get install -y ufw && ufw allow {SSH_PORT}/tcp && ufw allow 80/tcp && ufw allow 443/tcp && ufw allow 8080/tcp && ufw allow 2022/tcp && ufw --force enable && ufw status",
                    confirm = true
                ),
                Action(
                    "Install Fail2ban",
                    "Blokir otomatis percobaan login SSH brute-force.",
                    "apt-get install -y fail2ban && systemctl enable --now fail2ban",
                    confirm = true
                ),
                Action(
                    "Ganti password user",
                    "Menjalankan passwd (interaktif; pakai tombol ••• untuk input rahasia).",
                    "passwd"
                ),
                Action(
                    "Info sistem lengkap",
                    "Kernel, CPU, RAM, disk, dan perangkat blok.",
                    "uname -a; echo; lscpu | head -20; echo; free -h; echo; lsblk"
                ),
                Action(
                    "Folder terbesar",
                    "15 folder terbesar di /.",
                    "du -xh / --max-depth=1 2>/dev/null | sort -rh | head -15"
                ),
                Action(
                    "Layanan yang gagal",
                    "systemctl --failed",
                    "systemctl --failed --no-pager"
                ),
                Action(
                    "Layanan yang berjalan",
                    "Daftar service berstatus running.",
                    "systemctl list-units --type=service --state=running --no-pager"
                ),
                Action(
                    "Cron job",
                    "Crontab user ini dan /etc/cron.d.",
                    "crontab -l; echo; ls -la /etc/cron.d"
                ),
                Action(
                    "Bersihkan paket & cache apt",
                    "autoremove dan clean.",
                    "apt-get autoremove -y && apt-get clean",
                    confirm = true
                ),
                Action(
                    "Bersihkan log journal",
                    "Hapus log journal yang lebih dari 3 hari.",
                    "journalctl --vacuum-time=3d",
                    confirm = true
                ),
                Action(
                    "Set zona waktu WIB",
                    "timedatectl set-timezone Asia/Jakarta.",
                    "timedatectl set-timezone Asia/Jakarta && timedatectl",
                    confirm = true
                ),
                Action(
                    "Reboot server",
                    "Server restart; sesi SSH akan terputus.",
                    "reboot",
                    confirm = true
                ),
                Action(
                    "Matikan server",
                    "shutdown -h now. Server harus dinyalakan lagi dari panel penyedia VPS.",
                    "shutdown -h now",
                    confirm = true
                )
            )
        ),
        Section(
            "Jaringan", listOf(
                Action("IP publik", "Menampilkan IP publik server.", "curl -s ifconfig.me; echo"),
                Action("Tes ping", "Ping 4x ke 8.8.8.8 dan google.com.", "ping -c 4 8.8.8.8; ping -c 4 google.com"),
                Action("Koneksi aktif", "30 koneksi TCP yang established.", "ss -tn state established | head -30"),
                Action("Traffic interface", "Statistik paket per interface.", "ip -s link"),
                Action("DNS server", "Isi /etc/resolv.conf.", "cat /etc/resolv.conf"),
                Action(
                    "Speedtest",
                    "Tes kecepatan memakai speedtest-cli (butuh python3).",
                    "curl -s https://raw.githubusercontent.com/sivel/speedtest-cli/master/speedtest.py | python3 -",
                    confirm = true
                ),
                Action("Status UFW", "ufw status verbose", "ufw status verbose"),
                Action(
                    "Buka port UFW",
                    "Interaktif: ketik port, mis. 25565/tcp.",
                    "read -p 'Port (mis. 25565/tcp): ' P && ufw allow \$P && ufw status",
                    confirm = true
                ),
                Action(
                    "Tutup port UFW",
                    "Interaktif: ketik port yang ingin ditutup.",
                    "read -p 'Port yang ditutup (mis. 25565/tcp): ' P && ufw delete allow \$P && ufw status",
                    confirm = true
                )
            )
        ),
        Section(
            "Database", listOf(
                Action(
                    "Install MariaDB",
                    "Memasang MariaDB server dan mengaktifkannya saat boot.",
                    "apt-get install -y mariadb-server && systemctl enable --now mariadb",
                    confirm = true
                ),
                Action("Status MariaDB", "Status layanan database.", "systemctl status mariadb --no-pager"),
                Action("Daftar database", "SHOW DATABASES (akses root lokal).", "mysql -e 'SHOW DATABASES;'"),
                Action("Shell MySQL", "Membuka konsol mysql sebagai root.", "mysql -u root"),
                Action(
                    "Backup semua database",
                    "mysqldump --all-databases ke /root/backup.",
                    "mkdir -p /root/backup && mysqldump --all-databases > /root/backup/all-db-$(date +%F).sql && ls -lh /root/backup",
                    confirm = true
                )
            )
        ),
        Section(
            "Dev & Game", listOf(
                Action(
                    "Install Node.js 20",
                    "Memasang Node.js 20 LTS dari NodeSource.",
                    "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && apt-get install -y nodejs && node -v && npm -v",
                    confirm = true
                ),
                Action(
                    "Install PM2",
                    "Process manager Node.js, aktif saat boot.",
                    "npm install -g pm2 && pm2 startup",
                    confirm = true
                ),
                Action("Daftar proses PM2", "pm2 list", "pm2 list"),
                Action(
                    "Install Java 21",
                    "OpenJDK 21 headless (server Minecraft modern).",
                    "apt-get install -y openjdk-21-jre-headless && java -version",
                    confirm = true
                ),
                Action(
                    "Install Git, Python, Screen",
                    "git, python3, python3-pip, screen.",
                    "apt-get install -y git python3 python3-pip screen",
                    confirm = true
                ),
                Action("Sesi screen", "Daftar sesi screen yang berjalan.", "screen -ls")
            )
        ),
        Section(
            "Keamanan", listOf(
                Action("Status Fail2ban", "Ringkasan jail sshd.", "fail2ban-client status sshd"),
                Action("Login terakhir", "15 login terakhir ke server.", "last -n 15"),
                Action("User yang sedang login", "Perintah w.", "w"),
                Action(
                    "Login SSH gagal",
                    "20 percobaan login gagal terakhir.",
                    "grep -i 'failed password' /var/log/auth.log | tail -20 || journalctl -u ssh -n 30 --no-pager"
                ),
                Action(
                    "Tambah user sudo",
                    "Interaktif: membuat user baru dan menambahkannya ke grup sudo.",
                    "read -p 'Username baru: ' U && adduser \$U && usermod -aG sudo \$U",
                    confirm = true
                ),
                Action(
                    "Update otomatis keamanan",
                    "Memasang unattended-upgrades.",
                    "apt-get install -y unattended-upgrades && dpkg-reconfigure -plow unattended-upgrades",
                    confirm = true
                )
            )
        ),
        Section(
            "Web & SSL", listOf(
                Action(
                    "Install Certbot (nginx)",
                    "certbot dan plugin nginx untuk SSL Let's Encrypt.",
                    "apt-get install -y certbot python3-certbot-nginx",
                    confirm = true
                ),
                Action(
                    "Tes & reload nginx",
                    "nginx -t lalu reload bila konfigurasi valid.",
                    "nginx -t && systemctl reload nginx"
                ),
                Action("Log error nginx", "100 baris terakhir error.log.", "tail -n 100 /var/log/nginx/error.log"),
                Action("Daftar site nginx", "Isi sites-enabled.", "ls -la /etc/nginx/sites-enabled/"),
                Action("Tes perpanjang SSL", "certbot renew --dry-run.", "certbot renew --dry-run"),
                Action(
                    "Install nginx",
                    "Memasang nginx dan mengaktifkannya.",
                    "apt-get install -y nginx && systemctl enable --now nginx",
                    confirm = true
                ),
                Action(
                    "Daftar sertifikat SSL",
                    "certbot certificates",
                    "certbot certificates"
                )
            )
        )
    )

    fun resolve(command: String, server: Server): String =
        command.replace("{SSH_PORT}", server.port.toString())
}
