-- Website-Dashboard: Server-Liste fuer eingeloggte Nutzer (JWT-basiert, NUR lesend).
-- Die Website hatte bisher direkt koda_servers gelesen - seit dem RLS-Fix (12.09.)
-- liefert das still []. Diese RPC nutzt auth.uid() aus dem mitgeschickten JWT.
--
-- app_state/app_last_ping kommen aus der verknuepften koda_users-Zeile mit, damit die
-- Website den Geraetestatus anzeigen kann, ohne die Tabelle selbst lesen zu duerfen.

-- Signatur aendert sich (neue Spalten) -> DROP vor CREATE
DROP FUNCTION IF EXISTS public.rpc_get_servers_for_auth();

CREATE OR REPLACE FUNCTION public.rpc_get_servers_for_auth()
RETURNS TABLE(id uuid, host text, base_domain text, server_version text,
              ram_mb integer, online_players integer, max_players integer,
              gamemode text, difficulty text, motd text, is_banned boolean,
              kodadash_port integer, kodadash_token text,
              last_online timestamptz, created_at timestamptz,
              app_state text, app_last_ping timestamptz)
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
         u.app_state::text, u.app_last_ping
  FROM public.koda_servers s
  JOIN public.koda_users u ON u.app_uuid = s.owner_app_uuid
  WHERE u.auth_id = v_auth
    AND s.host NOT LIKE 'deleted_%'
    AND COALESCE(s.server_version, '') NOT IN ('DELETED')
  ORDER BY s.created_at DESC;
END;
$fn$;

REVOKE ALL ON FUNCTION public.rpc_get_servers_for_auth() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.rpc_get_servers_for_auth() TO authenticated;
NOTIFY pgrst, 'reload schema';
