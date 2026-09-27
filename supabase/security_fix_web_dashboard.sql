-- Website-Dashboard: Server-Liste fuer eingeloggte Nutzer (JWT-basiert, NUR lesend).
-- Die Website hatte bisher direkt koda_servers gelesen - seit dem RLS-Fix (12.09.)
-- liefert das still []. Diese RPC nutzt auth.uid() aus dem mitgeschickten JWT.
--
-- app_state/app_last_ping kommen aus der verknuepften koda_users-Zeile mit, damit die
-- Website den Geraetestatus anzeigen kann, ohne die Tabelle selbst lesen zu duerfen.
--
-- Wichtig: KEIN JOIN auf koda_users. Ein Konto kann mehrere koda_users-Zeilen mit
-- derselben app_uuid haben (Familie/Geraetewechsel) - ein Join vervielfacht dann jeden
-- Server in der Liste. Deshalb IN-Subquery plus skalare Subqueries fuer den Status.

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
         -- Live device telemetry from the app's heartbeat, for the website's device card
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
