package com.roziq.zixtheserver

/** Definisi "alat" server: daftar diambil lewat SSH, tiap baris punya aksi. */
object Tools {

    data class Item(
        val title: String,
        val sub: String,
        val tag: String = "",
        val key: String = "",
        /** 0 biasa, 1 baik (hijau), 2 peringatan, 3 buruk (merah) */
        val level: Int = 0
    )

    data class Act(
        val label: String,
        val command: String,
        val confirm: Boolean = false,
        val terminal: Boolean = false
    )

    class Spec(
        val id: String,
        val title: String,
        val desc: String,
        val listCmd: String,
        val parse: (String) -> List<Item>,
        val acts: (Item) -> List<Act>,
        val top: List<Act> = emptyList(),
        val addLabel: String? = null,
        val addHint: String = "",
        val addCmd: ((String) -> String)? = null,
        val sources: List<Pair<String, String>> = emptyList()
    )

    /** Mengutip argumen untuk shell (aman untuk nama/IP yang dikirim dari daftar). */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun lines(out: String) = out.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()

    private val PROC = Spec(
        "proc", "Proses", "Lihat proses berdasarkan CPU, kill yang macet.",
        "ps -eo pid,pcpu,pmem,user,comm --sort=-pcpu | head -80",
        parse = { out ->
            lines(out).drop(1).mapNotNull { l ->
                val p = l.trim().split(Regex("\\s+"), limit = 5)
                if (p.size < 5) null else {
                    val cpu = p[1].toDoubleOrNull() ?: 0.0
                    Item(p[4], "PID ${p[0]} • ${p[3]}", "CPU ${p[1]}% • RAM ${p[2]}%", p[0],
                        if (cpu > 80) 3 else if (cpu > 40) 2 else 0)
                }
            }
        },
        acts = { i ->
            listOf(
                Act("Kill (SIGTERM)", "kill ${i.key}", confirm = true),
                Act("Kill paksa (SIGKILL)", "kill -9 ${i.key}", confirm = true)
            )
        }
    )

    private val SVC = Spec(
        "svc", "Layanan systemd", "Start / stop / restart / enable layanan.",
        "systemctl list-units --type=service --all --no-legend --no-pager | sed 's/^[^a-zA-Z0-9]*//' | awk '{print \$1\"|\"\$3\"|\"\$4}'",
        parse = { out ->
            lines(out).mapNotNull { l ->
                val p = l.split('|')
                if (p.size < 3) null else {
                    val lvl = when (p[1]) { "active" -> 1; "failed" -> 3; else -> 0 }
                    Item(p[0].removeSuffix(".service"), "${p[1]} (${p[2]})", p[1], p[0], lvl)
                }
            }.sortedWith(compareByDescending<Item> { it.level == 3 }.thenByDescending { it.level == 1 }.thenBy { it.title })
        },
        acts = { i ->
            listOf(
                Act("Restart", "systemctl restart ${q(i.key)}"),
                Act("Start", "systemctl start ${q(i.key)}"),
                Act("Stop", "systemctl stop ${q(i.key)}", confirm = true),
                Act("Enable (aktif saat boot)", "systemctl enable ${q(i.key)}"),
                Act("Disable", "systemctl disable ${q(i.key)}", confirm = true),
                Act("Status (terminal)", "systemctl status ${q(i.key)} --no-pager", terminal = true),
                Act("Log (terminal)", "journalctl -u ${q(i.key)} -n 100 --no-pager", terminal = true)
            )
        },
        top = listOf(Act("Reload daemon", "systemctl daemon-reload"))
    )

    private val DOCKER = Spec(
        "docker", "Container Docker", "Kelola container: start, stop, restart, log, hapus.",
        "docker ps -a --format '{{.Names}}|{{.Status}}|{{.Image}}'",
        parse = { out ->
            lines(out).mapNotNull { l ->
                val p = l.split('|')
                if (p.size < 3) null else {
                    val up = p[1].startsWith("Up")
                    Item(p[0], "${p[2]} • ${p[1]}", if (up) "running" else "stopped", p[0], if (up) 1 else 2)
                }
            }
        },
        acts = { i ->
            listOf(
                Act("Start", "docker start ${q(i.key)}"),
                Act("Stop", "docker stop ${q(i.key)}", confirm = true),
                Act("Restart", "docker restart ${q(i.key)}"),
                Act("Log 100 baris (terminal)", "docker logs --tail 100 ${q(i.key)}", terminal = true),
                Act("Masuk shell (terminal)", "docker exec -it ${q(i.key)} sh", terminal = true),
                Act("Hapus container", "docker rm -f ${q(i.key)}", confirm = true)
            )
        },
        top = listOf(Act("Bersihkan yang tak terpakai", "docker system prune -f", confirm = true))
    )

    private val UFW = Spec(
        "ufw", "Firewall (UFW)", "Lihat, tambah, dan hapus aturan firewall.",
        "ufw status numbered 2>&1",
        parse = { out ->
            if (out.contains("inactive", ignoreCase = true)) {
                listOf(Item("UFW tidak aktif", "Gunakan menu ⋮ › Aktifkan UFW (pastikan port SSH sudah diizinkan!)", "", "", 2))
            } else {
                lines(out).mapNotNull { l ->
                    val m = Regex("^\\[\\s*(\\d+)\\]\\s+(.*)$").find(l.trim())
                    if (m == null) null else {
                        val rule = m.groupValues[2].replace(Regex("\\s{2,}"), "  ")
                        Item(rule, "Aturan #${m.groupValues[1]}", if (rule.contains("DENY") || rule.contains("REJECT")) "deny" else "allow",
                            m.groupValues[1], if (rule.contains("DENY") || rule.contains("REJECT")) 3 else 1)
                    }
                }
            }
        },
        acts = { i ->
            if (i.key.isEmpty()) emptyList()
            else listOf(Act("Hapus aturan #${i.key}", "yes | ufw delete ${i.key}", confirm = true))
        },
        top = listOf(
            Act("Aktifkan UFW", "ufw allow ssh && yes | ufw enable", confirm = true),
            Act("Nonaktifkan UFW", "ufw disable", confirm = true)
        ),
        addLabel = "Izinkan port", addHint = "mis. 25565/tcp atau 80",
        addCmd = { v -> "ufw allow ${q(v)}" }
    )

    private val CRON = Spec(
        "cron", "Cron job", "Lihat, tambah, dan hapus jadwal cron user ini.",
        "crontab -l 2>&1",
        parse = { out ->
            if (out.contains("no crontab", ignoreCase = true)) emptyList()
            else out.lines().mapIndexedNotNull { idx, l ->
                if (l.isBlank()) null
                else Item(l.trim(), "Baris #${idx + 1}", if (l.trim().startsWith("#")) "komentar" else "", (idx + 1).toString())
            }
        },
        acts = { i ->
            listOf(Act("Hapus baris #${i.key}", "crontab -l | sed '${i.key}d' | crontab -", confirm = true))
        },
        addLabel = "Tambah cron", addHint = "*/5 * * * * perintah",
        addCmd = { v -> "(crontab -l 2>/dev/null; echo ${q(v)}) | crontab -" }
    )

    private val APT = Spec(
        "apt", "Update paket", "Paket yang bisa diperbarui (apt).",
        "apt-get update -qq >/dev/null 2>&1; apt list --upgradable 2>/dev/null | tail -n +2",
        parse = { out ->
            lines(out).map { l ->
                val name = l.substringBefore('/')
                Item(name, l.substringAfter(' ', ""), "update", name, 2)
            }
        },
        acts = { i ->
            listOf(Act("Update paket ini (terminal)", "apt-get install --only-upgrade -y ${q(i.key)}", terminal = true))
        },
        top = listOf(Act("Update semua (terminal)", "apt-get update && apt-get upgrade -y", confirm = true, terminal = true))
    )

    private val LOGS = Spec(
        "logs", "Log viewer", "Baca log sistem dengan penyorotan error/warning.",
        "journalctl -n 300 --no-pager",
        parse = { out ->
            lines(out).reversed().map { l ->
                val low = l.lowercase()
                val lvl = when {
                    Regex("error|fail|fatal|panic|denied|critical").containsMatchIn(low) -> 3
                    low.contains("warn") -> 2
                    else -> 0
                }
                Item(l, "", "", "", lvl)
            }
        },
        acts = { emptyList() },
        sources = listOf(
            "Sistem (journal)" to "journalctl -n 300 --no-pager",
            "SSH" to "journalctl -u ssh -u sshd -n 300 --no-pager",
            "Nginx error" to "tail -n 300 /var/log/nginx/error.log",
            "Wings" to "journalctl -u wings -n 300 --no-pager",
            "Auth" to "tail -n 300 /var/log/auth.log",
            "Kernel" to "dmesg | tail -n 300"
        )
    )

    private val AUTH = Spec(
        "auth", "Login SSH gagal", "IP yang paling sering gagal login, lengkap dengan tombol blokir.",
        "(grep -h 'Failed password' /var/log/auth.log* 2>/dev/null || journalctl -u ssh -u sshd --no-pager 2>/dev/null | grep 'Failed password') | grep -o 'from [0-9a-fA-F.:]*' | awk '{print \$2}' | sort | uniq -c | sort -rn | head -40",
        parse = { out ->
            lines(out).mapNotNull { l ->
                val p = l.trim().split(Regex("\\s+"), limit = 2)
                if (p.size < 2) null else {
                    val n = p[0].toIntOrNull() ?: 0
                    Item(p[1], "$n percobaan gagal", "$n×", p[1], if (n > 100) 3 else if (n > 10) 2 else 0)
                }
            }
        },
        acts = { i ->
            listOf(
                Act("Blokir IP (UFW)", "ufw deny from ${q(i.key)}", confirm = true),
                Act("Ban di fail2ban", "fail2ban-client set sshd banip ${q(i.key)}", confirm = true)
            )
        }
    )

    val all: List<Spec> = listOf(PROC, SVC, DOCKER, UFW, CRON, APT, LOGS, AUTH)

    fun byId(id: String): Spec? = all.firstOrNull { it.id == id }
}
