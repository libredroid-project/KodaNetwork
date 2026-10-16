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
# KodaHosting Supabase Setup

## Buckets

- `artifacts`
  - `openjdk17/openjdk17-aarch64.tar.gz`
  - `openjdk17/openjdk17-armv7.tar.gz` (optional)

## Function Secrets

Set in Supabase project secrets:

- `SUPABASE_SERVICE_ROLE_KEY`
- `JDK_STORAGE_BUCKET=artifacts`
- `OPENJDK17_SHA256_AARCH64=<sha256>`
- `OPENJDK17_SHA256_ARMV7=<sha256_optional>`
- `IONOS_API_PREFIX=<prefix>`
- `IONOS_API_SECRET=<secret>`
- `IONOS_ZONE_ID=<zone id for kodanetwork.eu>`

## Deploy

```bash
supabase functions deploy provision-java
supabase functions deploy create-dns-link
supabase functions deploy api --use-api
```

## The api function (api.kodanetwork.eu)

`functions/api/index.ts` is the public API behind `api.kodanetwork.eu`: the
extension store (`/v1/extensions`), status and releases (`/v1/status`,
`/v1/releases`), the account routes (`/v1/me...`) and the app/submission side of
the store (`POST /v1/extensions`, `/versions`, `/finalize`, `/withdraw`,
`POST /v1/assets`). Deployed with `verify_jwt = false` (see `config.toml`) -
the public routes carry no token, the `/v1/me` routes verify the Supabase JWT
themselves, and `/v1/admin/dns-bootstrap` accepts only a token this project's
auth admin endpoint accepts as service role.

Schema and buckets: `extensions_store.sql` (tables `extensions`,
`extension_versions`, `account_profiles`, buckets `extension_packages` private,
`extension_assets` and `avatars` public read). The DNS record, nginx vhost and
certificate for the host live in `vps-https/` (`setup-api-https.sh`).
