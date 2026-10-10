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

## What is missing (one step, needs your input)

An **IONOS API key with DNS rights** (IONOS -> Developer -> API keys). The wildcard certificate for
`*.kodaserv.eu` cannot be issued over HTTP-01, so it needs the DNS-01 challenge through the IONOS API.

## Then

```bash
sudo IONOS_API_KEY=… ./setup-kodadash-https.sh --dry-run   # show what would happen
sudo IONOS_API_KEY=… ./setup-kodadash-https.sh             # certificate + nginx vhosts
```

The script issues the certificate, writes one server block per server into
`/etc/nginx/kodadash/`, tests the configuration with `nginx -t` and only reloads nginx when the test
passes - a broken config is reverted, so the existing setup never breaks. Re-running it picks up new
servers; entries for deleted servers are removed.

Afterwards set `app_settings.kodadash_https` to enabled - the app link on the server screen and the
"Open KodaDash" button on the website then use the HTTPS address. The token stays in the query string
(nothing else changes), but from then on it travels encrypted.

## Why not Caddy

Caddy would take over ports 80 and 443 and fight with the existing nginx on this machine. The nginx
that is already there does the same job with the same automatic certificate renewal from acme.sh.
