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
-- website dashboard: server list for logged in users (JWT based, read only).
-- the website used to read koda_servers directly - since the RLS fix (12.09.)
-- it silently gets []. this rpc takes auth.uid() from the JWT we send along.
--
-- app_state/app_last_ping ride along from the linked koda_users row so the website
-- can show device status without being allowed to read that table itself.
--
-- important: NO JOIN on koda_users. one account can have several koda_users rows
-- with the same app_uuid (family, device switch) - a join would then multiply every
-- server in the list. hence the IN subquery plus scalar subqueries for the status.

DROP FUNCTION IF EXISTS public.rpc_get_servers_for_auth();

CREATE OR REPLACE FUNCTION public.rpc_get_servers_for_auth()
RETURNS TABLE(id uuid, host text, base_domain text, server_version text,
              ram_mb integer, online_players integer, max_players integer,
              gamemode text, difficulty text, motd text, is_banned boolean,
              kodadash_port integer, kodadash_token text,
              last_online timestamptz, created_at timestamptz,
              app_state text, app_last_ping timestamptz,
              device_model text, os_version text, app_version text,
              total_ram_mb integer, free_ram_mb integer, cpu_cores integer,
              battery_level integer, is_charging boolean, network_type text,
              screen_resolution text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_auth uuid := auth.uid();
BEGIN
  IF v_auth IS NULL THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  RETURN QUERY
  SELECT s.id, s.host::text, s.base_domain::text, s.server_version::text,
         s.ram_mb, s.online_players, s.max_players,
         s.gamemode, s.difficulty, s.motd, s.is_banned,
         COALESCE(s.kodadash_port, 0), s.kodadash_token::text,
         s.last_online, s.created_at,
         (SELECT u2.app_state::text
            FROM public.koda_users u2
           WHERE u2.app_uuid = s.owner_app_uuid AND u2.auth_id = v_auth
           ORDER BY COALESCE(u2.app_last_ping, '-infinity'::timestamptz) DESC
           LIMIT 1) AS app_state,
         (SELECT max(u3.app_last_ping)
            FROM public.koda_users u3
           WHERE u3.app_uuid = s.owner_app_uuid AND u3.auth_id = v_auth) AS app_last_ping,
         -- live device telemetry from the app heartbeat, feeds the website's device card
         d.device_model::text, d.os_version::text, d.app_version::text,
         d.total_ram_mb, d.free_ram_mb, d.cpu_cores,
         d.battery_level, d.is_charging, d.network_type::text, d.screen_resolution::text
  FROM public.koda_servers s
  LEFT JOIN LATERAL (
      SELECT u2.device_model, u2.os_version, u2.app_version, u2.total_ram_mb, u2.free_ram_mb,
             u2.cpu_cores, u2.battery_level, u2.is_charging, u2.network_type, u2.screen_resolution
        FROM public.koda_users u2
       WHERE u2.app_uuid = s.owner_app_uuid AND u2.auth_id = v_auth
       ORDER BY COALESCE(u2.app_last_ping, '-infinity'::timestamptz) DESC
       LIMIT 1
  ) d ON true
  WHERE s.owner_app_uuid IN (SELECT u.app_uuid FROM public.koda_users u WHERE u.auth_id = v_auth)
    AND s.host NOT LIKE 'deleted_%'
    AND COALESCE(s.server_version, '') NOT IN ('DELETED')
  ORDER BY s.created_at DESC;
END;
$fn$;

REVOKE ALL ON FUNCTION public.rpc_get_servers_for_auth() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.rpc_get_servers_for_auth() TO authenticated;
NOTIFY pgrst, 'reload schema';

-- the website reads app_settings too (kodadash_https, to build HTTPS links).
-- the old policy was anon only, so logged in users got an empty answer even
-- though the SELECT was granted. those values (version, maintenance, thresholds,
-- https flag) are public anyway, so the policy now covers both roles.
DROP POLICY IF EXISTS "Public can read app_settings" ON public.app_settings;
CREATE POLICY "Public can read app_settings" ON public.app_settings
    FOR SELECT TO anon, authenticated USING (true);
