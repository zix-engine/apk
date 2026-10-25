#!/bin/sh
echo "HOST=$(hostname)"
echo "IP=$(hostname -I 2>/dev/null | awk '{print $1}')"
echo "OS=$( (. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME") || uname -s )"
echo "KERNEL=$(uname -r)"
echo "UPTIME=$(uptime -p 2>/dev/null || uptime)"
echo "LOAD=$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
echo "CPUS=$(nproc 2>/dev/null)"
echo "MEM=$(free -m 2>/dev/null | awk '/^Mem:/ {print $3 "/" $2 " MB"}')"
echo "MEM_PCT=$(free -m 2>/dev/null | awk '/^Mem:/ {if ($2>0) printf "%d", $3*100/$2; else print 0}')"
echo "SWAP=$(free -m 2>/dev/null | awk '/^Swap:/ {print $3 "/" $2 " MB"}')"
echo "DISK=$(df -h / 2>/dev/null | awk 'NR==2 {print $3 "/" $2 " (" $5 ")"}')"
echo "DISK_PCT=$(df -P / 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')"
c1=$(head -n 1 /proc/stat 2>/dev/null)
sleep 0.5 2>/dev/null || sleep 1
c2=$(head -n 1 /proc/stat 2>/dev/null)
echo "CPU_PCT=$(printf '%s\n%s\n' "$c1" "$c2" | awk 'NR==1 {t1=$2+$3+$4+$5+$6+$7+$8; i1=$5+$6} NR==2 {t2=$2+$3+$4+$5+$6+$7+$8; i2=$5+$6; d=t2-t1; if (d>0) printf "%d", (d-(i2-i1))*100/d; else print 0}')"
echo "PROCS=$(ps -e --no-headers 2>/dev/null | wc -l | tr -d ' ')"
echo "USERS=$(who 2>/dev/null | wc -l | tr -d ' ')"
if command -v docker >/dev/null 2>&1; then
  echo "DOCKER=$(docker ps -q 2>/dev/null | wc -l | tr -d ' ')/$(docker ps -aq 2>/dev/null | wc -l | tr -d ' ')"
fi
for s in nginx apache2 mariadb mysql redis-server docker wings pteroq fail2ban ufw ssh sshd cron; do
  if systemctl cat "$s" >/dev/null 2>&1; then
    echo "SVC_$s=$(systemctl is-active "$s" 2>/dev/null)"
  fi
done
