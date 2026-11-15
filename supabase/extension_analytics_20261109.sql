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
-- extension download analytics, 2026-11-09.
--
-- the old counter (extensions.downloads) only knows a lifetime total. this table
-- records one row per store download with the version, so the developer console
-- can show a daily curve and version adoption. no user identifier is stored.

create table if not exists public.extension_download_events (
  id bigserial primary key,
  extension_id uuid not null references public.extensions(id) on delete cascade,
  version_code integer not null,
  created_at timestamptz not null default now()
);

create index if not exists idx_ext_download_events_ext_time
  on public.extension_download_events (extension_id, created_at desc);

alter table public.extension_download_events enable row level security;

-- only the API (service role) writes and reads this table
revoke all on public.extension_download_events from anon, authenticated;

create or replace function public.rpc_extension_analytics(p_auth_id uuid, p_days integer default 30)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_days integer := greatest(1, least(coalesce(p_days, 30), 365));
  v_since timestamptz := now() - make_interval(days => v_days);
begin
  return jsonb_build_object(
    'days', v_days,
    'tracking_since', (
      select min(e.created_at)
      from public.extension_download_events e
      join public.extensions x on x.id = e.extension_id
      where x.owner_auth_id = p_auth_id
    ),
    'daily', coalesce((
      select jsonb_agg(row_to_json(t) order by t.day)
      from (
        select e.extension_id,
               e.version_code,
               date_trunc('day', e.created_at)::date as day,
               count(*)::int as downloads
        from public.extension_download_events e
        join public.extensions x on x.id = e.extension_id
        where x.owner_auth_id = p_auth_id
          and e.created_at >= v_since
        group by 1, 2, 3
      ) t
    ), '[]'::jsonb),
    'versions', coalesce((
      select jsonb_agg(row_to_json(t) order by t.downloads desc)
      from (
        select e.extension_id,
               e.version_code,
               count(*)::int as downloads,
               min(e.created_at) as first_at,
               max(e.created_at) as last_at
        from public.extension_download_events e
        join public.extensions x on x.id = e.extension_id
        where x.owner_auth_id = p_auth_id
          and e.created_at >= v_since
        group by 1, 2
      ) t
    ), '[]'::jsonb)
  );
end;
$$;

revoke all on function public.rpc_extension_analytics(uuid, integer) from public, anon, authenticated;
grant execute on function public.rpc_extension_analytics(uuid, integer) to service_role;

comment on table public.extension_download_events is
  'one row per store download, used by rpc_extension_analytics; no user identifiers';
