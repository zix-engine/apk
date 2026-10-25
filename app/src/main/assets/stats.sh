#!/bin/sh
echo "HOST=$(hostname)"
echo "IP=$(hostname -I 2>/dev/null | awk '{print $1}')"
echo "OS=$( (. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME") || uname -s )"
echo "KERNEL=$(uname -r)"
echo "UPTIME=$(uptime -p 2>/dev/null || uptime)"
echo "LOAD=$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
echo "CPUS=$(nproc 2>/dev/null)"
echo "MEM=$(free -m 2>/dev/null | awk '/^Mem:/ {print $3 "/" $2 " MB"}')"
echo "DISK=$(df -h / 2>/dev/null | awk 'NR==2 {print $3 "/" $2 " (" $5 ")"}')"
for s in nginx mariadb mysql redis-server docker wings pteroq; do
  if systemctl cat "$s" >/dev/null 2>&1; then
    echo "SVC_$s=$(systemctl is-active "$s" 2>/dev/null)"
  fi
done
