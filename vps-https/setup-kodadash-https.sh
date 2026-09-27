#!/usr/bin/env bash
#
# Copyright (c) 2026 KodaHosting
#
# Triple-Licensed under GPL-3.0 / LOPL v1.0 PREVIEW / Commercial License.
#
# Puts HTTPS in front of the tunneled KodaDash dashboards.
#
# Today a dashboard is reached as http://<server>.kodaserv.eu:<port>?token=... - unencrypted and with
# the token in the URL. This script issues a wildcard certificate for *.kodaserv.eu and writes one
# nginx server block per server, so the same dashboard is reachable as
# https://<server>.kodaserv.eu (nginx proxies to the local tunnel port, token stays in the query).
#
# Prerequisites
#   * an IONOS API key with DNS access (IONOS -> Developer -> API keys) for the DNS-01 challenge
#   * the wildcard A/CNAME record *.kodaserv.eu -> this server (already the case)
#   * nginx (already installed here)
#
# Usage
#   sudo IONOS_API_KEY=... ./setup-kodadash-https.sh --dry-run     # show what would happen
#   sudo IONOS_API_KEY=... ./setup-kodadash-https.sh               # issue cert + write vhosts
#
# The server list (host + dashboard port) comes from Supabase, so new servers appear automatically
# once this script runs again (add it to the monitor timer if you want it fully automatic).
set -euo pipefail

DOMAIN="${DOMAIN:-kodaserv.eu}"
SUPABASE_URL="${SUPABASE_URL:-}"
SUPABASE_SERVICE_ROLE_KEY="${SUPABASE_SERVICE_ROLE_KEY:-}"
CERT_DIR="/etc/nginx/certs"
NGINX_SNIPPET_DIR="/etc/nginx/kodadash"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

ENV_FILE="${ENV_FILE:-/etc/koda-monitor.env}"
if [ -f "$ENV_FILE" ]; then
    set -a; . "$ENV_FILE"; set +a
fi

log() { echo "[kodadash-https] $*"; }
die() { echo "[kodadash-https] ERROR: $*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "please run as root"
[ -n "$SUPABASE_URL" ] && [ -n "$SUPABASE_SERVICE_ROLE_KEY" ] || die "SUPABASE_URL/SUPABASE_SERVICE_ROLE_KEY missing (see $ENV_FILE)"

# ---------------------------------------------------------------- 1. certificate
if [ ! -f "$CERT_DIR/fullchain.pem" ]; then
    [ -n "${IONOS_API_KEY:-}" ] || die "IONOS_API_KEY is required to issue the wildcard certificate"
    log "issuing wildcard certificate for *.${DOMAIN} (DNS-01 via IONOS)"
    if [ "$DRY_RUN" = "1" ]; then
        log "dry run: would install acme.sh and run --issue -d ${DOMAIN} -d *.${DOMAIN} --dns dns_ionos"
    else
        command -v acme.sh >/dev/null 2>&1 || curl -s https://get.acme.sh | sh -s email=licence@kodaserv.eu >/dev/null
        export IONOS_API_KEY
        ~/.acme.sh/acme.sh --issue --dns dns_ionos -d "$DOMAIN" -d "*.${DOMAIN}" --keylength ec-256
        mkdir -p "$CERT_DIR"
        ~/.acme.sh/acme.sh --install-cert -d "$DOMAIN" --ecc \
            --fullchain-file "$CERT_DIR/fullchain.pem" \
            --key-file "$CERT_DIR/privkey.pem" \
            --reloadcmd "systemctl reload nginx"
    fi
else
    log "certificate already present at $CERT_DIR/fullchain.pem"
fi

# ---------------------------------------------------------------- 2. server list
log "reading servers from Supabase"
servers=$(curl -sS "${SUPABASE_URL}/rest/v1/koda_servers?select=host,base_domain,kodadash_port&kodadash_port=gt.0&host=not.like.deleted_*" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}")
count=$(echo "$servers" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
log "found ${count} server(s) with KodaDash"

# ---------------------------------------------------------------- 3. nginx vhosts
mkdir -p "$NGINX_SNIPPET_DIR" "$CERT_DIR"
echo "$servers" | python3 -c "
import json, sys
servers = json.load(sys.stdin)
template = open('$(dirname "$0")/nginx-kodadash.conf.template').read()
for s in servers:
    host = s['host']
    domain = s.get('base_domain') or '${DOMAIN}'
    port = s['kodadash_port']
    name = f'{host}.{domain}'
    with open(f'${NGINX_SNIPPET_DIR}/{host}.conf', 'w') as fh:
        fh.write(template.replace('{SERVER_NAME}', name).replace('{UPSTREAM_PORT}', str(port)))
    print('  wrote', name, '->', port)
"

# remove snippets for servers that no longer exist
for file in "$NGINX_SNIPPET_DIR"/*.conf; do
    [ -e "$file" ] || continue
    host=$(basename "$file" .conf)
    echo "$servers" | grep -q "\"$host\"" || { log "removing stale snippet for $host"; [ "$DRY_RUN" = "1" ] || rm -f "$file"; }
done

# ---------------------------------------------------------------- 4. include + reload
CONF="/etc/nginx/conf.d/kodadash-https.conf"
INCLUDE_BLOCK=$(cat <<'CONF'
# Managed by vps-https/setup-kodadash-https.sh - one server block per KodaDash dashboard
include /etc/nginx/kodadash/*.conf;
CONF
)

if [ ! -f "$CERT_DIR/fullchain.pem" ]; then
    log "no certificate yet - leaving nginx untouched (HTTP keeps working)"
    exit 0
fi

if [ "$DRY_RUN" = "1" ]; then
    log "dry run: would write $CONF and run nginx -t && systemctl reload nginx"
    exit 0
fi

echo "$INCLUDE_BLOCK" > "$CONF"
if nginx -t; then
    systemctl reload nginx
    log "nginx reloaded - dashboards are now reachable as https://<server>.${DOMAIN}"
    log "flip app_settings.kodadash_https to {\"enabled\": true, \"domain\": \"${DOMAIN}\"} to use the new links"
else
    rm -f "$CONF"
    die "nginx configuration test failed - reverted, nothing changed"
fi
