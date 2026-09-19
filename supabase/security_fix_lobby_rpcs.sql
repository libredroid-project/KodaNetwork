-- ============================================================================
-- LOBBY-PLUGIN RPCs (2026-09-15): Ersatz fuer die gesperrten Tabellen-Reads.
-- Das Plugin haelt nur den Anon-Key; alle Aufrufe sind SECURITY DEFINER mit
-- minimaler Datenfreigabe. Remote-Kommandos brauchen zusaetzlich den
-- Lobby-Key (Tabelle koda_lobby_keys, Wert liegt in der plugin config.yml).
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.koda_lobby_keys (
  key text PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.koda_lobby_keys ENABLE ROW LEVEL SECURITY;

-- Lobby-Key seeden (falls leer)
INSERT INTO public.koda_lobby_keys (key)
SELECT upper(substr(encode(gen_random_bytes(16), 'hex'), 1, 24))
WHERE NOT EXISTS (SELECT 1 FROM public.koda_lobby_keys);

-- Server-Liste fuer den Browser (wie bisher, aber ohne DELETE/Hibernate-Reste)
CREATE OR REPLACE FUNCTION public.rpc_lobby_get_servers()
RETURNS TABLE(host text, base_domain text, online_players integer, server_version text, owner_app_uuid text, max_players integer)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
  SELECT s.host, s.base_domain, s.online_players, s.server_version, s.owner_app_uuid, s.max_players
  FROM public.koda_servers s
  WHERE s.host NOT LIKE 'deleted_%'
    AND COALESCE(s.server_version, '') NOT IN ('DELETED')
  ORDER BY s.host;
$fn$;

CREATE OR REPLACE FUNCTION public.rpc_lobby_get_users()
RETURNS TABLE(app_uuid text, mc_username text, two_fa_enabled boolean, last_active timestamptz, is_main boolean, permissions jsonb)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
  SELECT u.app_uuid, u.mc_username, u.two_fa_enabled, u.app_last_ping, u.is_main, u.permissions
  FROM public.koda_users u
  WHERE u.mc_username IS NOT NULL;
$fn$;

-- 2FA: Passwort NIE auslesen, nur serverseitig pruefen
CREATE OR REPLACE FUNCTION public.rpc_lobby_verify_2fa(p_mc_username text, p_password text)
RETURNS boolean LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
  SELECT EXISTS (
    SELECT 1 FROM public.koda_users u
    WHERE lower(u.mc_username) = lower(p_mc_username)
      AND u.two_fa_enabled = true
      AND u.two_fa_password IS NOT NULL
      AND u.two_fa_password = p_password
  );
$fn$;

-- Account-Linking per 8-Zeichen-Code aus der App
CREATE OR REPLACE FUNCTION public.rpc_lobby_link_account(p_code text, p_mc_username text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_count integer;
BEGIN
  IF p_code IS NULL OR length(p_code) < 8 OR p_mc_username IS NULL OR length(p_mc_username) > 16 THEN
    RETURN false;
  END IF;
  UPDATE public.koda_users SET mc_username = p_mc_username
  WHERE code = p_code AND mc_username IS NULL;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count > 0;
END;
$fn$;

-- Remote-Kommando (nur mit gueltigem Lobby-Key, Whitelist der Befehle)
CREATE OR REPLACE FUNCTION public.rpc_lobby_send_command(p_lobby_key text, p_host text, p_command text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_count integer;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.koda_lobby_keys WHERE key = p_lobby_key) THEN
    RAISE EXCEPTION 'unauthorized';
  END IF;
  IF p_command IS NULL OR length(p_command) > 120
     OR p_command !~ '^(START|STOP|RESTART|HIBERNATE|WHITELIST_ON|WHITELIST_OFF|EXEC_ .+|EXEC_.+|INSTALL_PLUGIN_[A-Za-z0-9]+|SETPROP_[A-Za-z]+_.+)$' THEN
    RETURN false;
  END IF;
  UPDATE public.koda_servers SET server_version = 'CMD:' || p_command
  WHERE host = p_host AND host NOT LIKE 'deleted_%';
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count > 0;
END;
$fn$;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO anon, authenticated;
REVOKE ALL ON FUNCTION public.fn_verify_device_token(text, text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.fn_is_praetor_admin(text, text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.trg_koda_users_bootstrap() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON public.koda_lobby_keys FROM anon, authenticated;
NOTIFY pgrst, 'reload schema';
