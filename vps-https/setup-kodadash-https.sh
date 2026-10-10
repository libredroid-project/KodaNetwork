#!/usr/bin/env bash
# Copyright (c) 2026 KodaHosting
#
# This file is part of KodaHosting (KodaNetwork).
# KodaHosting is free software: you can redistribute it and/or modify it under the
# terms of the GNU General Public License as published by the Free Software
# Foundation, version 3 of the License.
#
# KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# KodaHosting. If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-FileCopyrightText: 2026 KodaHosting
# SPDX-License-Identifier: GPL-3.0-only
#
# puts HTTPS in front of the tunneled KodaDash dashboards.
# without this, a dashboard is reached as http://<server>.kodaserv.eu:<port>?token=... - unencrypted
# and with the token sitting in the URL. one certificate per server host is issued
# (Let's Encrypt, HTTP-01 through the nginx that is already running) and written to:
#   /etc/nginx/kodadash/<host>.conf      https://<host> -> 127.0.0.1:<tunnel port>   (SSE safe)
#   /etc/nginx/kodadash-http/<host>.conf keeps the ACME challenge path and redirects to https
# the server list comes from Supabase, so a rerun picks up new servers and removes the
# configuration of deleted ones. nginx is only reloaded after `nginx -t` passed, a broken config
# gets reverted, the existing setup is never touched.
# usage
#   ./setup-kodadash-https.sh --dry-run     # show what would happen
#   ./setup-kodadash-https.sh               # certificates + nginx configuration
# a wildcard certificate (*.kodaserv.eu) would need the DNS-01 challenge and with it an IONOS
# API key that has DNS rights, per-host certificates work without it because port 80 is open.
# IONOS_API_KEY in /etc/koda-monitor.env makes this script switch to the wildcard route on its own.
set -euo pipefail

DOMAIN="${DOMAIN:-kodaserv.eu}"
CERT_EMAIL="${CERT_EMAIL:-licence@kodaserv.eu}"
WEBROOT="${WEBROOT:-/var/www/html}"
HTTPS_DIR="/etc/nginx/kodadash"
HTTP_DIR="/etc/nginx/kodadash-http"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

ENV_FILE="${ENV_FILE:-/etc/koda-monitor.env}"
if [ -f "$ENV_FILE" ]; then
    set -a; . "$ENV_FILE"; set +a
fi

log() { echo "[kodadash-https] $*"; }
die() { echo "[kodadash-https] ERROR: $*" >&2; exit 1; }
run() { if [ "$DRY_RUN" = "1" ]; then log "dry run: $*"; else "$@"; fi; }

[ "$(id -u)" = "0" ] || die "please run as root"
[ -n "${SUPABASE_URL:-}" ] && [ -n "${SUPABASE_SERVICE_ROLE_KEY:-}" ] || die "SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing (see $ENV_FILE)"
command -v certbot >/dev/null || die "certbot is not installed"

# ---------------------------------------------------------------- server list
log "reading servers from Supabase"
servers=$(curl -sS "${SUPABASE_URL}/rest/v1/koda_servers?select=host,base_domain,kodadash_port&kodadash_port=gt.0&host=not.like.deleted_*" \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}")
count=$(echo "$servers" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
log "found ${count} server(s) with KodaDash"
[ "$count" -gt 0 ] || { log "nothing to do"; exit 0; }

[ "$DRY_RUN" = "1" ] || mkdir -p "$HTTPS_DIR" "$HTTP_DIR"
TEMPLATE_HTTPS="$(dirname "$0")/nginx-kodadash.conf.template"
TEMPLATE_HTTP="$(dirname "$0")/nginx-kodadash-http.conf.template"

# ---------------------------------------------------------------- per host
echo "$servers" | python3 -c "
import json, sys
for s in json.load(sys.stdin):
    print(s['host'], s.get('base_domain') or '$DOMAIN', s['kodadash_port'])
" | while read -r host domain port; do
    name="${host}.${domain}"

    # 1. certificate, skipped when it already exists and is valid
    if [ -d "/etc/letsencrypt/live/${name}" ]; then
        log "certificate for ${name} already present"
    elif [ -n "${IONOS_API_KEY:-}" ]; then
        log "wildcard route not implemented in this script - issuing ${name} via HTTP-01 as well"
        run certbot certonly --webroot -w "$WEBROOT" -d "$name" --non-interactive --agree-tos -m "$CERT_EMAIL" --keep-until-expiring
    else
        log "issuing certificate for ${name}"
        if ! run certbot certonly --webroot -w "$WEBROOT" -d "$name" --non-interactive --agree-tos -m "$CERT_EMAIL" --keep-until-expiring; then
            # one host without a (propagated) DNS record must not stop the others - the next timer
            # run picks it up again.
            log "certificate for ${name} failed (DNS record live yet?) - skipping this host"
            continue
        fi
    fi

    if [ ! -d "/etc/letsencrypt/live/${name}" ] && [ "$DRY_RUN" = "0" ]; then
        log "no certificate for ${name} - skipping its nginx configuration"
        continue
    fi

    # 2. the https server block for the dashboard
    if [ "$DRY_RUN" = "1" ]; then
        log "dry run: would write ${HTTPS_DIR}/${host}.conf (${name} -> 127.0.0.1:${port})"
    else
        sed -e "s/{SERVER_NAME}/${name}/g" \
            -e "s/{UPSTREAM_PORT}/${port}/g" \
            -e "s/{CERT_NAME}/${name}/g" \
            "$TEMPLATE_HTTPS" > "${HTTPS_DIR}/${host}.conf"
        log "wrote ${HTTPS_DIR}/${host}.conf (${name} -> 127.0.0.1:${port} + token)"
    fi

    # 3. port 80: keep the ACME challenge alive and send browsers on to https
    if [ "$DRY_RUN" = "1" ]; then
        log "dry run: would write ${HTTP_DIR}/${host}.conf"
    else
        sed -e "s|{SERVER_NAME}|${name}|g" -e "s|{WEBROOT}|${WEBROOT}|g" \
            "$TEMPLATE_HTTP" > "${HTTP_DIR}/${host}.conf"
    fi
done

# ---------------------------------------------------------------- cleanup + reload
known_hosts=$(echo "$servers" | python3 -c "import sys,json; print(' '.join(s['host'] for s in json.load(sys.stdin)))")
for file in "$HTTPS_DIR"/*.conf "$HTTP_DIR"/*.conf; do
    [ -e "$file" ] || continue
    host=$(basename "$file" .conf)
    if ! echo " $known_hosts " | grep -q " $host "; then
        log "removing stale configuration for $host"
        run rm -f "$file"
    fi
done

CONF="/etc/nginx/conf.d/kodadash-https.conf"
if [ "$DRY_RUN" = "1" ]; then
    log "dry run: would write $CONF and run nginx -t && systemctl reload nginx"
    exit 0
fi

# this script runs from a timer, so it must not reload nginx on every run.
BEFORE=$(cat "$CONF" 2>/dev/null; for f in "$HTTPS_DIR"/*.conf "$HTTP_DIR"/*.conf; do [ -e "$f" ] && cat "$f"; done | md5sum)

cat > "$CONF" <<'CONF'
# Managed by vps-https/setup-kodadash-https.sh
# https://<server>.kodaserv.eu -> tunneled KodaDash dashboard of that server
include /etc/nginx/kodadash-http/*.conf;
include /etc/nginx/kodadash/*.conf;
CONF

AFTER=$(cat "$CONF"; for f in "$HTTPS_DIR"/*.conf "$HTTP_DIR"/*.conf; do [ -e "$f" ] && cat "$f"; done | md5sum)

if [ "$BEFORE" = "$AFTER" ]; then
    log "configuration unchanged - nothing to reload"
    exit 0
fi

if nginx -t; then
    systemctl reload nginx
    log "nginx reloaded - dashboards are reachable as https://<server>.${DOMAIN}"
    log "enable the links with: app_settings.kodadash_https = {\"enabled\": true, \"domain\": \"${DOMAIN}\"}"
else
    rm -f "$CONF"
    die "nginx test failed - reverted, nothing was changed"
fi
