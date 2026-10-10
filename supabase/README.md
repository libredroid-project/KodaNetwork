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
- `PLAYIT_AGENT_TOKEN=<token>`
- `IONOS_API_PREFIX=<prefix>`
- `IONOS_API_SECRET=<secret>`
- `IONOS_ZONE_ID=<zone id for kodanetwork.eu>`

## Deploy

```bash
supabase functions deploy provision-java
supabase functions deploy bootstrap-playit
supabase functions deploy create-dns-link
```
