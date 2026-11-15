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
-- device breakdown for extension analytics, 2026-11-09.
--
-- the download event gets the device model, Android version and app build the
-- app sends with the download request. web downloads are marked as source=web
-- without a model. no account or advertising id is stored.

alter table public.extension_download_events
  add column if not exists device_model text,
  add column if not exists os_version text,
  add column if not exists app_version integer,
  add column if not exists source text not null default 'app';

create index if not exists idx_ext_download_events_device
  on public.extension_download_events (extension_id, device_model);

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
    ), '[]'::jsonb),
    'devices', coalesce((
      select jsonb_agg(row_to_json(t) order by t.downloads desc)
      from (
        select coalesce(e.device_model, 'Unknown') as device_model,
               count(*)::int as downloads,
               count(distinct e.extension_id)::int as extensions
        from public.extension_download_events e
        join public.extensions x on x.id = e.extension_id
        where x.owner_auth_id = p_auth_id
          and e.created_at >= v_since
        group by 1
      ) t
    ), '[]'::jsonb),
    'os_versions', coalesce((
      select jsonb_agg(row_to_json(t) order by t.downloads desc)
      from (
        select coalesce(e.os_version, 'Unknown') as os_version,
               count(*)::int as downloads
        from public.extension_download_events e
        join public.extensions x on x.id = e.extension_id
        where x.owner_auth_id = p_auth_id
          and e.created_at >= v_since
        group by 1
      ) t
    ), '[]'::jsonb),
    'app_versions', coalesce((
      select jsonb_agg(row_to_json(t) order by t.app_version desc)
      from (
        select e.app_version,
               count(*)::int as downloads
        from public.extension_download_events e
        join public.extensions x on x.id = e.extension_id
        where x.owner_auth_id = p_auth_id
          and e.created_at >= v_since
          and e.app_version is not null
        group by 1
      ) t
    ), '[]'::jsonb),
    'sources', coalesce((
      select jsonb_agg(row_to_json(t) order by t.downloads desc)
      from (
        select coalesce(e.source, 'app') as source,
               count(*)::int as downloads
        from public.extension_download_events e
        join public.extensions x on x.id = e.extension_id
        where x.owner_auth_id = p_auth_id
          and e.created_at >= v_since
        group by 1
      ) t
    ), '[]'::jsonb)
  );
end;
$$;

revoke all on function public.rpc_extension_analytics(uuid, integer) from public, anon, authenticated;
grant execute on function public.rpc_extension_analytics(uuid, integer) to service_role;

create or replace function public.fn_purge_expired_extension_events()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  removed integer;
begin
  delete from public.extension_download_events
  where created_at < now() - interval '365 days';
  get diagnostics removed = row_count;
  return removed;
end;
$$;

revoke all on function public.fn_purge_expired_extension_events() from public, anon, authenticated;
grant execute on function public.fn_purge_expired_extension_events() to service_role;

-- daily at 03:20, the same mechanism the retained-user cleanup uses
select cron.unschedule('purge-extension-events')
where exists (select 1 from cron.job where jobname = 'purge-extension-events');
select cron.schedule('purge-extension-events', '20 3 * * *',
  $$select public.fn_purge_expired_extension_events()$$);
