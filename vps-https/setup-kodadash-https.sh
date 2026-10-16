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
# certificates: the WILDCARD per domain (*.kodanetwork.eu, *.kodaserv.eu) is issued once
# through koda-acme-hook.sh (DNS-01 via the acme-dns edge function, no IONOS keys on this
# host). One certificate covers every server host, so a new dashboard works immediately -
# per-host certificates are gone, they needed a public A record for each host and failed
# for every server whose DNS record was not live yet.
set -euo pipefail

DOMAIN="${DOMAIN:-kodanetwork.eu}"
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

STAGE_DIR=$(mktemp -d)
mkdir -p "$STAGE_DIR/https" "$STAGE_DIR/http"
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

    # 1. certificate: the domain wildcard covers this host. issued once by hand
    #    (see koda-acme-hook.sh), renewals run through the certbot timer.
    cert_name="${domain}-wildcard"
    if [ ! -d "/etc/letsencrypt/live/${cert_name}" ]; then
        log "wildcard certificate ${cert_name} missing - skipping ${name}"
        log "issue it once with: certbot certonly --manual --preferred-challenges dns \\"
        log "  --manual-auth-hook '/usr/local/bin/koda-acme-hook.sh present' \\"
        log "  --manual-cleanup-hook '/usr/local/bin/koda-acme-hook.sh cleanup' \\"
        log "  -d '*.${domain}' -d '${domain}' --cert-name ${cert_name} --non-interactive --agree-tos"
        continue
    fi

    # 2. the https server block for the dashboard
    if [ "$DRY_RUN" = "1" ]; then
        log "dry run: would write ${HTTPS_DIR}/${host}.conf (${name} -> 127.0.0.1:${port})"
    else
        sed -e "s/{SERVER_NAME}/${name}/g" \
            -e "s/{UPSTREAM_PORT}/${port}/g" \
            -e "s/{CERT_NAME}/${cert_name}/g" \
            "$TEMPLATE_HTTPS" > "${STAGE_DIR}/https/${host}.conf"
        log "staged ${host}.conf (${name} -> 127.0.0.1:${port}, cert ${cert_name})"
    fi

    # 3. port 80: keep the ACME challenge alive and send browsers on to https
    if [ "$DRY_RUN" = "1" ]; then
        log "dry run: would stage ${host}.conf (port 80)"
    else
        sed -e "s|{SERVER_NAME}|${name}|g" -e "s|{WEBROOT}|${WEBROOT}|g" \
            "$TEMPLATE_HTTP" > "${STAGE_DIR}/http/${host}.conf"
    fi
done

# ---------------------------------------------------------------- sync + reload
# Der Vergleich laeuft jetzt GEGEN den Live-Baum (vorher wurde der Live-Baum mit sich
# selbst verglichen, weil BEFORE erst nach dem Schreiben entstand - neue Hosts loesten
# deshalb nie einen Reload aus und die Dashboards blieben auf der alten Konfiguration).
if [ "$DRY_RUN" = "1" ]; then
    log "dry run: would sync the staged configuration and run nginx -t && systemctl reload nginx"
    exit 0
fi

CHANGED=0
diff -rq "$STAGE_DIR/https" "$HTTPS_DIR" >/dev/null 2>&1 || CHANGED=1
diff -rq "$STAGE_DIR/http" "$HTTP_DIR" >/dev/null 2>&1 || CHANGED=1

if [ "$CHANGED" = "0" ]; then
    log "configuration unchanged - nothing to reload"
    exit 0
fi

# Tausch mit Rollback: erst testen, dann reloaden, bei kaputtem Test zurueckspielen
BACKUP_DIR=$(mktemp -d)
mkdir -p "$BACKUP_DIR/https" "$BACKUP_DIR/http"
cp -a "$HTTPS_DIR"/. "$BACKUP_DIR/https"/ 2>/dev/null || true
cp -a "$HTTP_DIR"/. "$BACKUP_DIR/http"/ 2>/dev/null || true

install_tree() {
    local from="$1" to="$2"
    find "$to" -maxdepth 1 -name '*.conf' -delete 2>/dev/null || true
    cp -a "$from"/. "$to"/ 2>/dev/null || true
}
install_tree "$STAGE_DIR/https" "$HTTPS_DIR"
install_tree "$STAGE_DIR/http" "$HTTP_DIR"

if nginx -t; then
    systemctl reload nginx
    log "nginx reloaded - dashboards are reachable as https://<server>.<domain>"
    log "enable the links with: app_settings.kodadash_https = {\"enabled\": true, \"domain\": \"${DOMAIN}\"}"
else
    install_tree "$BACKUP_DIR/https" "$HTTPS_DIR"
    install_tree "$BACKUP_DIR/http" "$HTTP_DIR"
    die "nginx test failed - reverted, nothing was changed"
fi
