<!--
  Copyright (c) 2026 KodaHosting

  This file is part of KodaHosting (KodaNetwork).
  KodaHosting is free software: you can redistribute it and/or modify it under the
  terms of the GNU General Public License as published by the Free Software
  Foundation, version 3 of the License.

  KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
  WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
  PARTICULAR PURPOSE. See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with
  KodaHosting. If not, see <https://www.gnu.org/licenses/>.

  SPDX-FileCopyrightText: 2026 KodaHosting
  SPDX-License-Identifier: GPL-3.0-only
-->
# HTTPS for the KodaDash dashboards

Currently a dashboard is opened as `http://<server>.kodaserv.eu:<port>?token=…` - unencrypted, with
the token visible in the URL bar and in every proxy log on the way. This directory contains the
preparation to serve the same dashboards as `https://<server>.kodaserv.eu`.

## What is already in place

* the DNS wildcard `*.kodaserv.eu` already points at the tunnel server
* nginx runs on the server (port 80, currently without TLS)
* the app and the website read `app_settings.kodadash_https`; as soon as it is
  `{"enabled": true, "domain": "kodaserv.eu"}` they build `https://…` links instead of `http://…:port`
* the reporter script already knows every server and its dashboard port through Supabase

## Certificates: the wildcard route (2026-10-16)

Per-host certificates needed a public A record for every dashboard host, so the timer kept
failing for servers whose DNS record was not live yet ("NXDOMAIN looking up A for …"). Now one
**wildcard per domain** (`*.kodanetwork.eu`, `*.kodaserv.eu`) covers every server host.

The wildcard needs the DNS-01 challenge, and the IONOS keys deliberately stay in the
`acme-dns` edge function (set as function secrets). The tunnel server only holds a shared
token in `/etc/koda-monitor.env` and talks to that function through `koda-acme-hook.sh`.

Issued once (already done on the tunnel server):

```bash
cd /opt/koda-https          # or the repo copy in vps-https/
install -m 700 koda-acme-hook.sh /usr/local/bin/koda-acme-hook.sh
set -a; . /etc/koda-monitor.env; set +a
certbot certonly --manual --preferred-challenges dns \
    --manual-auth-hook    '/usr/local/bin/koda-acme-hook.sh present' \
    --manual-cleanup-hook '/usr/local/bin/koda-acme-hook.sh cleanup' \
    -d '*.kodanetwork.eu' -d 'kodanetwork.eu' --cert-name kodanetwork.eu-wildcard \
    --non-interactive --agree-tos
```

The hooks are stored in the renewal configuration, so the normal certbot timer renews the
wildcards on its own (verified with `certbot renew --dry-run`).

## Then: the vhost sync

```bash
sudo ./setup-kodadash-https.sh --dry-run   # show what would happen
sudo ./setup-kodadash-https.sh             # nginx vhosts for every dashboard
```

The script writes one server block per server into `/etc/nginx/kodadash/`, tests the
configuration with `nginx -t` and only reloads nginx when the test passes - a broken config is
reverted, so the existing setup never breaks. It stages everything into a temp tree and compares it
against the live one, so a timer run without changes does not touch nginx at all.

Two traps were fixed on 2026-10-16, both of which had silently kept this whole route dead:

* the dashboard vhosts listened on `443` themselves, but port 443 belongs to the `stream` block in
  `nginx.conf` (SNI preread -> `127.0.0.1:8443`). The reload therefore died with
  `bind() to 0.0.0.0:443 failed (98: Address already in use)` and nginx kept serving the OLD
  configuration. The vhosts now listen on `127.0.0.1:8443 ssl http2 proxy_protocol`, like the apex
  and the api vhost.
* the "did anything change" check ran after the files were already written, so it compared the live
  tree with itself and never reloaded. It compares the staged tree against the live tree now.

Afterwards set `app_settings.kodadash_https` to enabled - the app link on the server screen and the
"Open KodaDash" button on the website then use the HTTPS address. The token stays in the query string
(nothing else changes), but from then on it travels encrypted.

## Why not Caddy

Caddy would take over ports 80 and 443 and fight with the existing nginx on this machine. The nginx
that is already there does the same job with the same automatic certificate renewal from acme.sh.

## The public API (api.kodanetwork.eu)

`api.kodanetwork.eu` is the public KodaHosting API. It is served through the same nginx as everything
else and forwards to the Supabase edge function `api` (the router described in
`supabase/functions/api`): `/v1/status`, `/v1/releases`, the extension store under `/v1/extensions`
and the account routes under `/v1/me`. The stream block in `nginx.conf` already sends every unknown
SNI to the TLS layer on `127.0.0.1:8443`, so a single server block is enough.

1. **DNS**: one A record `api` -> this machine. Created once through
   `POST /v1/admin/dns-bootstrap` of the deployed api function (bearer = service role key of the
   project, the target ip goes into the request body) or by hand in the IONOS UI.
2. **Certificate + vhost**: `sudo ./setup-api-https.sh` (add `--dry-run` first if you want).
   It issues the certificate over HTTP-01 through the existing webroot and writes
   `/etc/nginx/conf.d/kodanetwork-api.conf`. The upstream host is derived from `SUPABASE_URL` in
   `/etc/koda-monitor.env`, so the project reference never lands in this repository. nginx is only
   reloaded after `nginx -t` passed; a broken config is reverted.
3. **Check**: `curl https://api.kodanetwork.eu/v1/status` must answer `"ok": true`.

### Renewals

Copy `renewal-hook-reload-nginx.sh` to `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh`
(chmod +x). nginx caches certificates in memory, so without this hook a renewed certificate would
only be served after the next manual reload - this applies to every certificate on the machine, not
just the api one.
