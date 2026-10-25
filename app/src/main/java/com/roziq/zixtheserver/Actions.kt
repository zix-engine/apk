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
                )
            )
        ),
        Section(
            "Docker", listOf(
                Action("Daftar container", "docker ps -a", "docker ps -a"),
                Action("Pemakaian resource container", "docker stats sekali jalan.", "docker stats --no-stream"),
                Action("Daftar image", "docker images", "docker images"),
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
