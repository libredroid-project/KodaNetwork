-- Copyright (c) 2026 KodaHosting
--
-- This file is part of KodaHosting (KodaNetwork).
-- KodaHosting is free software: you can redistribute it and/or modify it under the
-- terms of the GNU General Public License as published by the Free Software
-- Foundation, version 3 of the License.
--
-- KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
-- WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
-- PARTICULAR PURPOSE. See the GNU General Public License for more details.
--
-- You should have received a copy of the GNU General Public License along with
-- KodaHosting. If not, see <https://www.gnu.org/licenses/>.
--
-- SPDX-FileCopyrightText: 2026 KodaHosting
-- SPDX-License-Identifier: GPL-3.0-only
-- ============================================================================
-- SERVER GUESTS (2026-10-16): a control invite accepted by a signed-in website
-- account creates a guest row here. the Servers tab then lists that server for
-- the guest (with Start/Stop/Restart and KodaDash access while it runs), because
-- rpc_get_servers_for_auth includes can_control guest rows. writes happen only
-- through the api edge function (service role) after invite validation.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.server_guests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  host text NOT NULL,
  auth_id uuid NOT NULL,
  can_control boolean NOT NULL DEFAULT false,
  created_by_app_uuid text,
  invited_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (host, auth_id)
);

ALTER TABLE public.server_guests ENABLE ROW LEVEL SECURITY;

CREATE INDEX IF NOT EXISTS server_guests_auth_idx ON public.server_guests (auth_id);
CREATE INDEX IF NOT EXISTS server_guests_host_idx ON public.server_guests (host);

-- the function keeps its exact shape, only the two guest columns and the guest
-- rows are new; a DROP is needed because the return type changes.
DROP FUNCTION IF EXISTS public.rpc_get_servers_for_auth();

CREATE OR REPLACE FUNCTION public.rpc_get_servers_for_auth()
 RETURNS TABLE(id uuid, host text, base_domain text, server_version text, ram_mb integer, online_players integer, max_players integer, gamemode text, difficulty text, motd text, is_banned boolean, kodadash_port integer, kodadash_token text, last_online timestamp with time zone, created_at timestamp with time zone, app_state text, app_last_ping timestamp with time zone, device_model text, os_version text, app_version text, total_ram_mb integer, free_ram_mb integer, cpu_cores integer, battery_level integer, is_charging boolean, network_type text, screen_resolution text, is_guest boolean, can_control boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
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
         -- Live device telemetry from the app's heartbeat, for the website's device card
         d.device_model::text, d.os_version::text, d.app_version::text,
         d.total_ram_mb, d.free_ram_mb, d.cpu_cores,
         d.battery_level, d.is_charging, d.network_type::text, d.screen_resolution::text,
         -- guest flags last: the declared column order decides, not the select order otherwise
         (s.owner_app_uuid IS DISTINCT FROM (SELECT u0.app_uuid FROM public.koda_users u0
             WHERE u0.auth_id = v_auth AND u0.app_uuid = s.owner_app_uuid LIMIT 1)) AS is_guest,
         COALESCE((SELECT g2.can_control FROM public.server_guests g2
             WHERE g2.host = s.host AND g2.auth_id = v_auth), false) AS can_control
  FROM public.koda_servers s
  LEFT JOIN LATERAL (
      SELECT u2.device_model, u2.os_version, u2.app_version, u2.total_ram_mb, u2.free_ram_mb,
             u2.cpu_cores, u2.battery_level, u2.is_charging, u2.network_type, u2.screen_resolution
        FROM public.koda_users u2
       WHERE u2.app_uuid = s.owner_app_uuid AND u2.auth_id = v_auth
       ORDER BY COALESCE(u2.app_last_ping, '-infinity'::timestamptz) DESC
       LIMIT 1
  ) d ON true
  WHERE (s.owner_app_uuid IN (SELECT u.app_uuid FROM public.koda_users u WHERE u.auth_id = v_auth)
         OR EXISTS (SELECT 1 FROM public.server_guests g WHERE g.host = s.host AND g.auth_id = v_auth AND g.can_control))
    AND s.host NOT LIKE 'deleted_%'
    AND COALESCE(s.server_version, '') NOT IN ('DELETED')
  ORDER BY s.created_at DESC;
END;
$function$


NOTIFY pgrst, 'reload schema';
