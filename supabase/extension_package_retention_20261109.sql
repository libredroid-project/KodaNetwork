-- Copyright (c) 2026 KodaHosting
--
-- This file is part of KodaHosting (KodaNetwork).
-- KodaHosting is free software: you can redistribute it and/or modify it under the
-- terms of the GNU General Public License as published by the Free Software
-- Foundation, version 3 of the License.
--
-- KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
-- WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
-- PARTICULAR PURPOSE. See the GNU General Public License for more details.
--
-- You should have received a copy of the GNU General Public License along with
-- KodaHosting. If not, see <https://www.gnu.org/licenses/>.
--
-- SPDX-FileCopyrightText: 2026 KodaHosting
-- SPDX-License-Identifier: GPL-3.0-only
--
-- old release packages + support hand-out log, 2026-11-09.
--
-- the public API only serves the newest approved package. support may hand out
-- an older one for a documented reason (for example a legal request) inside the
-- 365 day retention window. every hand-out is logged here.

alter table public.extension_versions
  add column if not exists purged_at timestamptz;

comment on column public.extension_versions.purged_at is
  'set when the package file was removed after the 365 day retention window';

create table if not exists public.extension_package_access_log (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  extension_id uuid not null references public.extensions(id) on delete cascade,
  version_code integer not null,
  reason text not null,
  accessed_by text not null default 'admin-panel'
);

create index if not exists idx_ext_package_access_log_ext
  on public.extension_package_access_log (extension_id, created_at desc);

alter table public.extension_package_access_log enable row level security;
revoke all on public.extension_package_access_log from anon, authenticated;
