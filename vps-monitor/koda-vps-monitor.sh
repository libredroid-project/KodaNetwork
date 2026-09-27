#!/usr/bin/env bash
#
# Copyright (c) 2026 KodaHosting
#
# Triple-Licensed under GPL-3.0 / LOPL v1.0 PREVIEW / Commercial License.
#
# Reports the state of the tunnel server to Supabase so the app can warn about overload:
# load, memory, disk, established connections on the game port ranges, connected frpc clients,
# open tunnels and the network counters.
#
# Runs from a systemd timer (see koda-vps-monitor.timer). Configuration comes from an env file:
#
#   SUPABASE_URL=https://<project>.supabase.co
#   SUPABASE_SERVICE_ROLE_KEY=<service role key>
#   FRP_BIND_PORT=7000                 # frps bindPort, counts connected frpc clients
#   GAME_PORT_RANGES="30000-39999 40000-49999 55000-59999"
#   REPORT_HOST=vps                    # label written into the row
#   KEEP_DAYS=7                        # older rows are deleted
#
# Usage: koda-vps-monitor.sh [--print]     (--print only dumps the values, sends nothing)
set -euo pipefail

ENV_FILE="${ENV_FILE:-/etc/koda-monitor.env}"
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
fi

FRP_BIND_PORT="${FRP_BIND_PORT:-7000}"
GAME_PORT_RANGES="${GAME_PORT_RANGES:-30000-39999 40000-49999 55000-59999}"
REPORT_HOST="${REPORT_HOST:-vps}"
KEEP_DAYS="${KEEP_DAYS:-7}"
PRINT_ONLY=0
[ "${1:-}" = "--print" ] && PRINT_ONLY=1

# ---------- collect ----------
cpu_cores=$(nproc)
read -r load1 load5 load15 _ < /proc/loadavg

ram_total_mb=$(awk '/MemTotal/     {printf "%d", $2/1024}' /proc/meminfo)
ram_avail_mb=$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo)
ram_used_mb=$(( ram_total_mb - ram_avail_mb ))
swap_total_kb=$(awk '/SwapTotal/ {print $2}' /proc/meminfo)
swap_free_kb=$(awk '/SwapFree/  {print $2}' /proc/meminfo)
swap_used_mb=$(( (swap_total_kb - swap_free_kb) / 1024 ))

disk_free_gb=$(df -BG --output=avail / | tail -1 | tr -d 'G ')
uptime_seconds=$(cut -d' ' -f1 /proc/uptime | cut -d. -f1)

# Count only what belongs to frps. Filtering by port alone was wrong: other services on this
# machine (Tailscale, bore, rathole) also bind ports that happen to fall into the same ranges.
# Counting runs in python: the ss output has different columns for listening and established
# sockets, and only frps sockets may be counted (Tailscale, bore and rathole bind ports in the
# same ranges on this machine). See the field layout notes below.
read -r players tunnels tunnel_clients <<< "$(python3 - "$FRP_BIND_PORT" "$GAME_PORT_RANGES" <<'PYEOF'
import re, subprocess, sys

bind_port = int(sys.argv[1])
ranges = []
for part in sys.argv[2].split():
    low, high = part.split('-')
    ranges.append((int(low), int(high)))

listen = subprocess.run(['ss', '-Hltnp'], capture_output=True, text=True).stdout
established = subprocess.run(['ss', '-Htnp', 'state', 'established'], capture_output=True, text=True).stdout

def port_of(address):
    match = re.search(r':(\d+)$', address.strip())
    return int(match.group(1)) if match else None

def ip_of(address):
    match = re.match(r'^\[?(.*?)\]?:\d+$', address.strip())
    ip = match.group(1) if match else address
    return ip.replace('::ffff:', '')

# listening:  LISTEN  Recv-Q  Send-Q  Local  Peer  [process]
open_tunnels = 0
for line in listen.splitlines():
    if '"frps"' not in line:
        continue
    fields = line.split()
    if len(fields) >= 4 and port_of(fields[3]) != bind_port:
        open_tunnels += 1

# established: Recv-Q  Send-Q  Local  Peer  [process]
players = 0
clients = set()
for line in established.splitlines():
    if '"frps"' not in line:
        continue
    fields = line.split()
    if len(fields) < 4:
        continue
    local_port = port_of(fields[2])
    if local_port == bind_port:
        clients.add(ip_of(fields[3]))          # frpc control connection -> one per client
    elif local_port and any(low <= local_port <= high for low, high in ranges):
        players += 1                            # forwarded player connection

print(players, open_tunnels, len(clients))
PYEOF
)"

net_rx_mb=$(awk '{sum += $1} END {printf "%d", sum/1048576}' /sys/class/net/*/statistics/rx_bytes 2>/dev/null || echo 0)
net_tx_mb=$(awk '{sum += $1} END {printf "%d", sum/1048576}' /sys/class/net/*/statistics/tx_bytes 2>/dev/null || echo 0)

payload=$(cat <<JSON
{
  "host": "${REPORT_HOST}",
  "cpu_cores": ${cpu_cores},
  "load1": ${load1}, "load5": ${load5}, "load15": ${load15},
  "ram_total_mb": ${ram_total_mb}, "ram_used_mb": ${ram_used_mb}, "ram_available_mb": ${ram_avail_mb},
  "swap_used_mb": ${swap_used_mb},
  "disk_free_gb": ${disk_free_gb},
  "players_connected": ${players}, "tunnel_clients": ${tunnel_clients}, "open_tunnels": ${tunnels},
  "net_rx_mb": ${net_rx_mb}, "net_tx_mb": ${net_tx_mb},
  "uptime_seconds": ${uptime_seconds}
}
JSON
)

if [ "$PRINT_ONLY" = "1" ]; then
    echo "$payload"
    exit 0
fi

if [ -z "${SUPABASE_URL:-}" ] || [ -z "${SUPABASE_SERVICE_ROLE_KEY:-}" ]; then
    echo "SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing (see ${ENV_FILE})" >&2
    exit 1
fi

curl -sS -o /dev/null -X POST "${SUPABASE_URL}/rest/v1/vps_stats" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Content-Type: application/json" \
    -H "Prefer: return=minimal" \
    --data "$payload"

# keep the table small: drop rows older than KEEP_DAYS once in a while
if [ $(( $(date +%M) % 30 )) -eq 0 ]; then
    cutoff=$(date -u -d "-${KEEP_DAYS} days" +%Y-%m-%dT%H:%M:%SZ)
    curl -sS -o /dev/null -X DELETE "${SUPABASE_URL}/rest/v1/vps_stats?created_at=lt.${cutoff}" \
        -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
        -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" || true
fi
