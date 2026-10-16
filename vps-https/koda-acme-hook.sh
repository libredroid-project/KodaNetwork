#!/usr/bin/env bash
# Copyright (c) 2026 KodaHosting
#
# This file is part of KodaHosting (KodaNetwork).
# KodaHosting is free software: you can redistribute it and/or modify it under the
# terms of the GNU General Public License as published by the Free Software
# Foundation, version 3 of the License.
#
# KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# KodaHosting. If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-FileCopyrightText: 2026 KodaHosting
# SPDX-License-Identifier: GPL-3.0-only
#
# DNS-01 hook for certbot: publishes (or removes) the _acme-challenge TXT record
# through the acme-dns edge function, so the IONOS keys stay in the function's
# environment and the tunnel server never holds DNS credentials.
#
#   certbot certonly --manual --preferred-challenges dns \
#       --manual-auth-hook    "/usr/local/bin/koda-acme-hook.sh present" \
#       --manual-cleanup-hook "/usr/local/bin/koda-acme-hook.sh cleanup" \
#       -d "*.kodanetwork.eu" -d "kodanetwork.eu" --cert-name kodanetwork.eu-wildcard
#
# Needs KODA_ACME_URL and KODA_ACME_TOKEN in the environment (see /etc/koda-monitor.env).
# certbot exports CERTBOT_DOMAIN (the zone for a wildcard request) and CERTBOT_VALIDATION.
set -euo pipefail

# renewals run from the certbot timer, which knows nothing about our env file
if [ -f /etc/koda-monitor.env ]; then
    set -a
    # shellcheck disable=SC1091
    . /etc/koda-monitor.env
    set +a
fi

ACTION="${1:-}"
case "$ACTION" in
    present|cleanup) ;;
    *) echo "usage: $0 present|cleanup" >&2; exit 2 ;;
esac

[ -n "${KODA_ACME_URL:-}" ] || { echo "KODA_ACME_URL missing" >&2; exit 2; }
[ -n "${KODA_ACME_TOKEN:-}" ] || { echo "KODA_ACME_TOKEN missing" >&2; exit 2; }
[ -n "${CERTBOT_DOMAIN:-}" ] || { echo "CERTBOT_DOMAIN missing (run this through certbot)" >&2; exit 2; }

FQDN="_acme-challenge.${CERTBOT_DOMAIN}"
VALUE="${CERTBOT_VALIDATION:-}"

BODY=$(python3 - "$KODA_ACME_TOKEN" "$ACTION" "$FQDN" "$VALUE" <<'PY'
import json, sys
token, action, fqdn, value = sys.argv[1:5]
print(json.dumps({"token": token, "action": action, "fqdn": fqdn, "value": value}))
PY
)

RESPONSE=$(curl -sS -X POST "$KODA_ACME_URL" -H "Content-Type: application/json" -d "$BODY")
case "$RESPONSE" in
    *'"ok":true'*) ;;
    *) echo "acme-dns hook failed for ${FQDN}: ${RESPONSE}" >&2; exit 1 ;;
esac

if [ "$ACTION" = "present" ]; then
    # wait until the zone's own nameservers hand out the value - Let's Encrypt asks
    # quickly and a 60 second TTL record needs a moment to appear
    for i in $(seq 1 30); do
        NS=$(dig +short NS "$CERTBOT_DOMAIN" @1.1.1.1 | head -1)
        if [ -n "$NS" ] && dig +short TXT "$FQDN" "@$NS" | grep -qF "$VALUE"; then
            echo "TXT for ${FQDN} visible on ${NS} (try ${i})"
            exit 0
        fi
        sleep 4
    done
    echo "TXT for ${FQDN} not visible after 120s - letting the CA try anyway" >&2
fi
