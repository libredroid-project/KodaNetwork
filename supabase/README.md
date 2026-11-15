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

## Schema

Apply the SQL in this order:

1. `supabase/migrations/*.sql` (base schema and compatibility migrations)
2. `supabase/security_fix_*.sql` (RPC-only access, device tokens, RLS)
3. The remaining feature SQL (releases, extensions, invites, guests, transfers, notifications, KodaDash beta, profile avatar)

Each file ends with a schema reload. The newest feature files are
`kodadash_beta_20261114.sql` and `profile_avatar_20261115.sql`.

## Buckets

- `artifacts` (public): the Java runtimes (`openjdk17/...`, `openjdk21/...`)
- `release_images` (public): release banners
- `support_attachments` (public): ticket attachments
- `extension_assets` (public): extension screenshots and assets
- `avatars` (public): profile pictures
- `extension_packages` (private): uploaded extension jars
- `data_exports` (private): generated GDPR exports

## Function Secrets

Set in Supabase project secrets:

- `SUPABASE_SERVICE_ROLE_KEY`
- `FRP_TOKEN` (must match `auth.token` in `vps-https/frps.toml.example`)
- `IONOS_API_PREFIX`
- `IONOS_API_SECRET`
- `ACME_HOOK_TOKEN`
- `RESEND_API_KEY`
- `KODA_MAIL_FROM`
- `OTP_PEPPER`
- `OPENROUTER_KEY`
- `JDK_STORAGE_BUCKET=artifacts`
- `OPENJDK17_SHA256_AARCH64=<sha256>`
- `OPENJDK17_SHA256_ARMV7=<sha256_optional>`

## Deploy

```bash
supabase functions deploy
```

`config.toml` sets `verify_jwt = false` for `api` (public routes) and `acme-dns`
(shared ACME hook token). The other functions keep the default.

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
`extension_assets` and `avatars` public for object URLs). The DNS record, nginx
vhost and certificate for the host live in `vps-https/` (`setup-api-https.sh`).

## Security invariants

These came out of the 2026-11-14 external assessment
(`security_fix_20261114.sql`); keep them when the schema is rebuilt.

- `service_checks`, `service_check_history` and `v_service_checks_effective` are
  internal monitoring data. No client role may read them; the monitor uses the
  service role.
- `v_servers_public` must never expose tombstone hosts (`deleted%`) or rows whose
  `server_version` is `DELETED`; those host names carry auth UUIDs. Hibernated
  rows stay visible because the app uses them to decide whether to zip a world.
- Public asset buckets (`extension_assets`, `avatars`) serve object URLs without
  a `storage.objects` SELECT policy. Do not add a bucket-wide SELECT policy:
  it also enables the storage list endpoint and leaks every per-user auth UUID.
- Supabase Auth currently runs with `mailer_autoconfirm = true`. The only email
  verification source is `account_profiles.email_verified_at`, written after the
  app's OTP check. Never trust the JWT `email_verified` claim or
  `auth.users.email_confirmed_at` for feature access.
