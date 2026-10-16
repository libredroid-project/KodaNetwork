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
# puts api.kodanetwork.eu in front of the Supabase edge function "api".
# the DNS record (A -> this machine) is created once through the admin route of the
# api itself (POST /v1/admin/dns-bootstrap) or by hand in the IONOS UI, after that a
# single run of this script issues the certificate and writes the vhost:
#   /etc/nginx/conf.d/kodanetwork-api.conf   https://api.kodanetwork.eu -> the function
# the upstream host is derived from SUPABASE_URL in the env file, so the project ref
# never lands in this repository. nginx is only reloaded after `nginx -t` passed, a
# broken config gets reverted.
# usage
#   ./setup-api-https.sh --dry-run     # show what would happen
#   ./setup-api-https.sh               # certificate + nginx configuration
set -euo pipefail

API_HOST="${API_HOST:-api.kodanetwork.eu}"
WEBROOT="${WEBROOT:-/var/www/html}"
CERT_EMAIL="${CERT_EMAIL:-licence@kodaserv.eu}"
CONF="/etc/nginx/conf.d/kodanetwork-api.conf"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

ENV_FILE="${ENV_FILE:-/etc/koda-monitor.env}"
if [ -f "$ENV_FILE" ]; then
    set -a; . "$ENV_FILE"; set +a
fi

log() { echo "[koda-api-https] $*"; }
die() { echo "[koda-api-https] ERROR: $*" >&2; exit 1; }
run() { if [ "$DRY_RUN" = "1" ]; then log "dry run: $*"; else "$@"; fi; }

[ "$(id -u)" = "0" ] || die "please run as root"
[ -n "${SUPABASE_URL:-}" ] || die "SUPABASE_URL missing (see $ENV_FILE)"
command -v certbot >/dev/null || die "certbot is not installed"

# https://<project>.supabase.co -> <project>.supabase.co
UPSTREAM_HOST=$(echo "$SUPABASE_URL" | sed -E 's#^https?://##; s#/.*$##')
[ -n "$UPSTREAM_HOST" ] || die "could not derive the upstream host from SUPABASE_URL"

# 1. certificate, skipped when it already exists
if [ -d "/etc/letsencrypt/live/${API_HOST}" ]; then
    log "certificate for ${API_HOST} already present"
else
    log "issuing certificate for ${API_HOST}"
    run certbot certonly --webroot -w "$WEBROOT" -d "$API_HOST" --non-interactive --agree-tos -m "$CERT_EMAIL" --keep-until-expiring
fi

if [ ! -d "/etc/letsencrypt/live/${API_HOST}" ] && [ "$DRY_RUN" = "0" ]; then
    die "no certificate for ${API_HOST} - is the DNS record live yet?"
fi

# 2. the vhost
if [ "$DRY_RUN" = "1" ]; then
    log "dry run: would write ${CONF} (${API_HOST} -> ${UPSTREAM_HOST}/functions/v1/api)"
    exit 0
fi
sed -e "s/{SERVER_NAME}/${API_HOST}/g" \
    -e "s/{CERT_NAME}/${API_HOST}/g" \
    -e "s/{API_UPSTREAM_HOST}/${UPSTREAM_HOST}/g" \
    "$(dirname "$0")/nginx-api.conf.template" > "$CONF"
log "wrote ${CONF} (${API_HOST} -> ${UPSTREAM_HOST})"

# 3. reload only when the test passes
if nginx -t; then
    systemctl reload nginx
    log "nginx reloaded - the api is reachable as https://${API_HOST}"
else
    rm -f "$CONF"
    die "nginx test failed - reverted, nothing was changed"
fi
