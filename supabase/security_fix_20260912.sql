-- ============================================================================
-- KODAHOSTING SECURITY FIX 2026-09-12
-- Device-Bearer-Token-Modell: jedes Geraet erhaelt einen serverseitig
-- generierten Token, den jede schreibende RPC verifiziert.
-- Rollback: supabase/backup-20260912/ (Policies + Funktionen replaybar).
-- Alle Abschnitte sind idempotent.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ABSCHNITT 0: Alte (unsichere) Funktions-Signaturen entfernen.
-- WICHTIG: Signaturaenderung = neue Funktion; ohne DROP bliebe die alte,
-- token-lose Variante parallel per PostgREST aufrufbar.
-- ---------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.rpc_get_is_banned(text);
DROP FUNCTION IF EXISTS public.rpc_patch_user(text, jsonb);
DROP FUNCTION IF EXISTS public.rpc_patch_user_by_id(text, uuid, jsonb);
DROP FUNCTION IF EXISTS public.rpc_admin_patch_user(text, uuid, jsonb);
DROP FUNCTION IF EXISTS public.rpc_admin_patch_server(text, text, jsonb);
DROP FUNCTION IF EXISTS public.rpc_patch_server(text, text, jsonb);
DROP FUNCTION IF EXISTS public.rpc_patch_server_by_id(text, uuid, jsonb);
DROP FUNCTION IF EXISTS public.rpc_migrate_servers(text, text);
DROP FUNCTION IF EXISTS public.rpc_sync_auth_id(text, uuid);
DROP FUNCTION IF EXISTS public.rpc_create_ticket(text, text, text, text);
DROP FUNCTION IF EXISTS public.rpc_create_ticket_message(uuid, text, text);
DROP FUNCTION IF EXISTS public.rpc_get_tickets(text);
DROP FUNCTION IF EXISTS public.rpc_get_ticket_messages(uuid, text);

-- ---------------------------------------------------------------------------
-- ABSCHNITT 1: Device-Token-Spalte, Admin-Tabelle, Trigger
-- ---------------------------------------------------------------------------
ALTER TABLE public.koda_users ADD COLUMN IF NOT EXISTS device_token text;

CREATE UNIQUE INDEX IF NOT EXISTS idx_koda_users_device_token
  ON public.koda_users(device_token) WHERE device_token IS NOT NULL;

-- Admin-Allowlist: nur Eintraege hier duerfen Admin-RPCs nutzen.
CREATE TABLE IF NOT EXISTS public.praetor_admins (
  app_uuid text PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.praetor_admins ENABLE ROW LEVEL SECURITY;
-- keine Policies = fuer PostgREST unsichtbar

-- Bestehende Admins aus permissions-Spalte uebernehmen
INSERT INTO public.praetor_admins (app_uuid)
SELECT app_uuid FROM public.koda_users
WHERE permissions IS NOT NULL AND (permissions->>'praetor_admin')::boolean = true
ON CONFLICT (app_uuid) DO NOTHING;

-- Tokens fuer alle bestehenden Geraete erzeugen
UPDATE public.koda_users
SET device_token = encode(gen_random_bytes(16), 'hex')
WHERE device_token IS NULL;

-- Alte 4-/6-stellige Link-Codes auf 8 Zeichen verlaengern (Brute-Force-Schutz)
ALTER TABLE public.koda_users ALTER COLUMN code TYPE varchar(8);
UPDATE public.koda_users
SET code = upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 8))
WHERE length(COALESCE(code, '')) < 8;

-- Trigger: neue Zeilen bekommen automatisch Token + 8-Zeichen-Link-Code
CREATE OR REPLACE FUNCTION public.trg_koda_users_bootstrap()
RETURNS trigger LANGUAGE plpgsql SET search_path = public, extensions AS $fn$
BEGIN
  IF NEW.device_token IS NULL OR NEW.device_token = '' THEN
    NEW.device_token := encode(gen_random_bytes(16), 'hex');
  END IF;
  IF NEW.code IS NULL OR length(NEW.code) < 8 THEN
    NEW.code := upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 8));
  END IF;
  RETURN NEW;
END;
$fn$;

DROP TRIGGER IF EXISTS koda_users_bootstrap ON public.koda_users;
CREATE TRIGGER koda_users_bootstrap BEFORE INSERT ON public.koda_users
  FOR EACH ROW EXECUTE FUNCTION public.trg_koda_users_bootstrap();

-- ---------------------------------------------------------------------------
-- ABSCHNITT 2: Tote/undichte Policies entfernen
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS "Allow file transfer inserts" ON public.koda_file_transfers;
DROP POLICY IF EXISTS "Allow file transfer selects" ON public.koda_file_transfers;
DROP POLICY IF EXISTS "Allow select for servers public" ON public.koda_servers;
DROP POLICY IF EXISTS "Allow insert for app users" ON public.koda_servers;
DROP POLICY IF EXISTS "Allow public read access" ON public.koda_ports;
DROP POLICY IF EXISTS "Allow public read access" ON public.high_risk_hwids;
-- Tote Buchstaben-Policy (id statt auth_id, greift nie):
DROP POLICY IF EXISTS "Users can update own profile" ON public.koda_users;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 3: Oeffentliche Serverstatus-View (ohne owner/token Spalten)
-- ---------------------------------------------------------------------------
DROP VIEW IF EXISTS public.v_servers_public;
CREATE VIEW public.v_servers_public AS
SELECT host, server_version, base_domain, online_players, max_players, motd,
       gamemode, difficulty, pvp, whitelist, is_banned, last_online, ram_mb
FROM public.koda_servers;
GRANT SELECT ON public.v_servers_public TO anon, authenticated;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 4: Interne Token-Pruefung (nur fuer andere Funktionen)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fn_verify_device_token(p_app_uuid text, p_token text)
RETURNS boolean LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
  SELECT EXISTS (
    SELECT 1 FROM public.koda_users
    WHERE app_uuid = p_app_uuid
      AND device_token IS NOT NULL
      AND device_token = p_token
  );
$fn$;

REVOKE ALL ON FUNCTION public.fn_verify_device_token(text, text) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.fn_is_praetor_admin(p_app_uuid text, p_token text)
RETURNS boolean LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
  SELECT EXISTS (
    SELECT 1 FROM public.praetor_admins a
    JOIN public.koda_users u ON u.app_uuid = a.app_uuid
    WHERE a.app_uuid = p_app_uuid
      AND u.device_token IS NOT NULL
      AND u.device_token = p_token
  );
$fn$;

REVOKE ALL ON FUNCTION public.fn_is_praetor_admin(text, text) FROM PUBLIC, anon, authenticated;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 5: rpc_get_is_banned -> gibt Token mit zurueck (Bootstrap)
-- Alte App-Versionen: JSON enthaelt kein "true" -> Verhalten unverändert ok.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_get_is_banned(p_app_uuid text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
  v_banned boolean;
  v_token text;
BEGIN
  SELECT is_banned, device_token INTO v_banned, v_token
  FROM public.koda_users WHERE app_uuid = p_app_uuid;

  IF NOT FOUND THEN
    INSERT INTO public.koda_users (app_uuid)
    VALUES (p_app_uuid)
    ON CONFLICT DO NOTHING;
    SELECT device_token INTO v_token FROM public.koda_users WHERE app_uuid = p_app_uuid;
    RETURN jsonb_build_object('banned', false, 'device_token', v_token);
  END IF;

  RETURN jsonb_build_object('banned', COALESCE(v_banned, false), 'device_token', v_token);
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 6: rpc_patch_user mit Token-Pflicht
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_patch_user(p_app_uuid text, p_device_token text, p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_users SET
    mc_username = CASE WHEN p_payload ? 'mc_username' THEN p_payload->>'mc_username' ELSE mc_username END,
    nickname = CASE WHEN p_payload ? 'nickname' THEN p_payload->>'nickname' ELSE nickname END,
    two_fa_enabled = CASE WHEN p_payload ? 'two_fa_enabled' THEN (p_payload->>'two_fa_enabled')::boolean ELSE two_fa_enabled END,
    two_fa_password = CASE WHEN p_payload ? 'two_fa_password' THEN p_payload->>'two_fa_password' ELSE two_fa_password END,
    device_ram_mb = CASE WHEN p_payload ? 'device_ram_mb' THEN (p_payload->>'device_ram_mb')::integer ELSE device_ram_mb END,
    is_main = CASE WHEN p_payload ? 'is_main' THEN (p_payload->>'is_main')::boolean ELSE is_main END,
    code = CASE WHEN p_payload ? 'code' THEN p_payload->>'code' ELSE code END,
    app_state = CASE WHEN p_payload ? 'app_state' THEN p_payload->>'app_state' ELSE app_state END,
    app_last_ping = CASE WHEN p_payload ? 'app_last_ping' THEN (p_payload->>'app_last_ping')::timestamp with time zone ELSE app_last_ping END,
    device_model = CASE WHEN p_payload ? 'device_model' THEN p_payload->>'device_model' ELSE device_model END,
    os_version = CASE WHEN p_payload ? 'os_version' THEN p_payload->>'os_version' ELSE os_version END,
    app_version = CASE WHEN p_payload ? 'app_version' THEN p_payload->>'app_version' ELSE app_version END,
    total_ram_mb = CASE WHEN p_payload ? 'total_ram_mb' THEN (p_payload->>'total_ram_mb')::integer ELSE total_ram_mb END,
    free_ram_mb = CASE WHEN p_payload ? 'free_ram_mb' THEN (p_payload->>'free_ram_mb')::integer ELSE free_ram_mb END,
    cpu_cores = CASE WHEN p_payload ? 'cpu_cores' THEN (p_payload->>'cpu_cores')::integer ELSE cpu_cores END,
    screen_resolution = CASE WHEN p_payload ? 'screen_resolution' THEN p_payload->>'screen_resolution' ELSE screen_resolution END,
    battery_level = CASE WHEN p_payload ? 'battery_level' THEN (p_payload->>'battery_level')::integer ELSE battery_level END,
    is_charging = CASE WHEN p_payload ? 'is_charging' THEN (p_payload->>'is_charging')::boolean ELSE is_charging END,
    network_type = CASE WHEN p_payload ? 'network_type' THEN p_payload->>'network_type' ELSE network_type END
  WHERE app_uuid = p_app_uuid;
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 7: rpc_patch_user_by_id mit Token + gekuelzter Payload
-- (permissions/auth_id/app_uuid/device_token sind NIE patchbar)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_patch_user_by_id(p_app_uuid text, p_device_token text, p_id uuid, p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  -- Aufrufer muss sich selbst authentifizieren (Token)
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  -- Ziel: eigene Zeile ODER Admin
  IF NOT EXISTS (SELECT 1 FROM public.koda_users WHERE id = p_id AND app_uuid = p_app_uuid)
     AND NOT public.fn_is_praetor_admin(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_users SET
    mc_username = CASE WHEN p_payload ? 'mc_username' THEN p_payload->>'mc_username' ELSE mc_username END,
    nickname = CASE WHEN p_payload ? 'nickname' THEN p_payload->>'nickname' ELSE nickname END,
    two_fa_enabled = CASE WHEN p_payload ? 'two_fa_enabled' THEN (p_payload->>'two_fa_enabled')::boolean ELSE two_fa_enabled END,
    two_fa_password = CASE WHEN p_payload ? 'two_fa_password' THEN p_payload->>'two_fa_password' ELSE two_fa_password END,
    device_ram_mb = CASE WHEN p_payload ? 'device_ram_mb' THEN (p_payload->>'device_ram_mb')::integer ELSE device_ram_mb END,
    is_main = CASE WHEN p_payload ? 'is_main' THEN (p_payload->>'is_main')::boolean ELSE is_main END,
    code = CASE WHEN p_payload ? 'code' THEN p_payload->>'code' ELSE code END,
    app_state = CASE WHEN p_payload ? 'app_state' THEN p_payload->>'app_state' ELSE app_state END,
    app_last_ping = CASE WHEN p_payload ? 'app_last_ping' THEN (p_payload->>'app_last_ping')::timestamp with time zone ELSE app_last_ping END,
    device_model = CASE WHEN p_payload ? 'device_model' THEN p_payload->>'device_model' ELSE device_model END,
    os_version = CASE WHEN p_payload ? 'os_version' THEN p_payload->>'os_version' ELSE os_version END,
    app_version = CASE WHEN p_payload ? 'app_version' THEN p_payload->>'app_version' ELSE app_version END
  WHERE id = p_id;
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 8: Admin-RPCs mit Admin-Allowlist + Token
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_admin_patch_user(p_admin_app_uuid text, p_admin_device_token text, p_target_id uuid, p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_is_praetor_admin(p_admin_app_uuid, p_admin_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_users SET
    is_banned = CASE WHEN p_payload ? 'is_banned' THEN (p_payload->>'is_banned')::boolean ELSE is_banned END,
    permissions = CASE WHEN p_payload ? 'permissions' THEN p_payload->'permissions' ELSE permissions END
  WHERE id = p_target_id;
END;
$fn$;

CREATE OR REPLACE FUNCTION public.rpc_admin_patch_server(p_admin_app_uuid text, p_admin_device_token text, p_target_host text, p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_is_praetor_admin(p_admin_app_uuid, p_admin_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_servers SET
    host = CASE WHEN p_payload ? 'host' THEN p_payload->>'host' ELSE host END,
    server_version = CASE WHEN p_payload ? 'server_version' THEN p_payload->>'server_version' ELSE server_version END,
    is_banned = CASE WHEN p_payload ? 'is_banned' THEN (p_payload->>'is_banned')::boolean ELSE is_banned END,
    ban_reason = CASE WHEN p_payload ? 'ban_reason' THEN p_payload->>'ban_reason' ELSE ban_reason END
  WHERE host = p_target_host;
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 9: Server-Patches mit Token, owner_app_uuid nicht patchbar
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_patch_server(p_app_uuid text, p_device_token text, p_host text, p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_owner text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE host = p_host;
  IF v_owner IS NULL THEN RAISE EXCEPTION 'Server not found'; END IF;
  IF v_owner != p_app_uuid THEN RAISE EXCEPTION 'Not authorized'; END IF;

  UPDATE public.koda_servers SET
    host = CASE WHEN p_payload ? 'host' THEN p_payload->>'host' ELSE host END,
    base_domain = CASE WHEN p_payload ? 'base_domain' THEN p_payload->>'base_domain' ELSE base_domain END,
    server_version = CASE WHEN p_payload ? 'server_version' THEN p_payload->>'server_version' ELSE server_version END,
    online_players = CASE WHEN p_payload ? 'online_players' THEN (p_payload->>'online_players')::integer ELSE online_players END,
    last_online = CASE WHEN p_payload ? 'last_online' THEN (p_payload->>'last_online')::timestamp with time zone ELSE last_online END,
    gamemode = CASE WHEN p_payload ? 'gamemode' THEN p_payload->>'gamemode' ELSE gamemode END,
    difficulty = CASE WHEN p_payload ? 'difficulty' THEN p_payload->>'difficulty' ELSE difficulty END,
    pvp = CASE WHEN p_payload ? 'pvp' THEN (p_payload->>'pvp')::boolean ELSE pvp END,
    whitelist = CASE WHEN p_payload ? 'whitelist' THEN (p_payload->>'whitelist')::boolean ELSE whitelist END,
    motd = CASE WHEN p_payload ? 'motd' THEN p_payload->>'motd' ELSE motd END,
    max_players = CASE WHEN p_payload ? 'max_players' THEN (p_payload->>'max_players')::integer ELSE max_players END,
    ram_mb = CASE WHEN p_payload ? 'ram_mb' THEN (p_payload->>'ram_mb')::integer ELSE ram_mb END,
    kodadash_port = CASE WHEN p_payload ? 'kodadash_port' THEN (p_payload->>'kodadash_port')::integer ELSE kodadash_port END,
    kodadash_token = CASE WHEN p_payload ? 'kodadash_token' THEN p_payload->>'kodadash_token' ELSE kodadash_token END
  WHERE host = p_host;
END;
$fn$;

CREATE OR REPLACE FUNCTION public.rpc_patch_server_by_id(p_app_uuid text, p_device_token text, p_id uuid, p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_owner text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE id = p_id;
  IF v_owner IS NULL THEN RAISE EXCEPTION 'Server not found'; END IF;
  IF v_owner != p_app_uuid THEN RAISE EXCEPTION 'Not authorized'; END IF;

  UPDATE public.koda_servers SET
    host = CASE WHEN p_payload ? 'host' THEN p_payload->>'host' ELSE host END,
    base_domain = CASE WHEN p_payload ? 'base_domain' THEN p_payload->>'base_domain' ELSE base_domain END,
    server_version = CASE WHEN p_payload ? 'server_version' THEN p_payload->>'server_version' ELSE server_version END,
    online_players = CASE WHEN p_payload ? 'online_players' THEN (p_payload->>'online_players')::integer ELSE online_players END,
    last_online = CASE WHEN p_payload ? 'last_online' THEN (p_payload->>'last_online')::timestamp with time zone ELSE last_online END,
    gamemode = CASE WHEN p_payload ? 'gamemode' THEN p_payload->>'gamemode' ELSE gamemode END,
    difficulty = CASE WHEN p_payload ? 'difficulty' THEN p_payload->>'difficulty' ELSE difficulty END,
    pvp = CASE WHEN p_payload ? 'pvp' THEN (p_payload->>'pvp')::boolean ELSE pvp END,
    whitelist = CASE WHEN p_payload ? 'whitelist' THEN (p_payload->>'whitelist')::boolean ELSE whitelist END,
    motd = CASE WHEN p_payload ? 'motd' THEN p_payload->>'motd' ELSE motd END,
    max_players = CASE WHEN p_payload ? 'max_players' THEN (p_payload->>'max_players')::integer ELSE max_players END,
    ram_mb = CASE WHEN p_payload ? 'ram_mb' THEN (p_payload->>'ram_mb')::integer ELSE ram_mb END,
    kodadash_port = CASE WHEN p_payload ? 'kodadash_port' THEN (p_payload->>'kodadash_port')::integer ELSE kodadash_port END,
    kodadash_token = CASE WHEN p_payload ? 'kodadash_token' THEN p_payload->>'kodadash_token' ELSE kodadash_token END
  WHERE id = p_id;
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 10: Server-Diebstahl killen: migrate nur mit Token des ALTEN Geraets
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_migrate_servers(p_old_app_uuid text, p_old_device_token text, p_new_app_uuid text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_old_app_uuid, p_old_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_servers SET owner_app_uuid = p_new_app_uuid
  WHERE owner_app_uuid = p_old_app_uuid;
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 11: Account-Link nur mit Token ODER korrektem 8-Zeichen-Code
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_sync_auth_id(p_app_uuid text, p_auth_id uuid, p_device_token text DEFAULT NULL, p_link_code text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_ok boolean := false;
BEGIN
  IF p_device_token IS NOT NULL AND public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    v_ok := true;
  END IF;
  IF NOT v_ok AND p_link_code IS NOT NULL THEN
    SELECT EXISTS (SELECT 1 FROM public.koda_users
                   WHERE app_uuid = p_app_uuid AND code = p_link_code) INTO v_ok;
  END IF;
  IF NOT v_ok THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_users SET auth_id = p_auth_id WHERE app_uuid = p_app_uuid;

  IF NOT FOUND THEN
    INSERT INTO public.koda_users (app_uuid, auth_id) VALUES (p_app_uuid, p_auth_id);
  END IF;
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 12: Neue Lese-RPCs (ersetzen anonyme Table-SELECTs)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_get_my_servers(p_app_uuid text, p_device_token text)
RETURNS TABLE(id uuid, host text, ram_mb integer, server_version text, base_domain text,
              kodadash_port integer, kodadash_token text, online_players integer,
              is_banned boolean, gamemode text, difficulty text,
              pvp boolean, whitelist boolean, motd text, max_players integer,
              last_online timestamptz, owner_app_uuid text, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  RETURN QUERY SELECT s.id, s.host, s.ram_mb, s.server_version, s.base_domain,
    s.kodadash_port, s.kodadash_token, s.online_players, s.is_banned, s.gamemode,
    s.difficulty, s.pvp, s.whitelist, s.motd, s.max_players, s.last_online,
    s.owner_app_uuid, s.created_at
  FROM public.koda_servers s WHERE s.owner_app_uuid = p_app_uuid;
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 13: Server-Erstellung als RPC (direkter INSERT-Policy ist weg)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_create_server(p_app_uuid text, p_device_token text, p_host text, p_ram_mb integer DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_id uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  INSERT INTO public.koda_servers (host, owner_app_uuid, ram_mb)
  VALUES (p_host, p_app_uuid, p_ram_mb)
  ON CONFLICT (host) DO NOTHING
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$fn$;

-- Globale Serverzahl fuer den Watchdog (ersetzt HEAD-Select auf Tabelle)
CREATE OR REPLACE FUNCTION public.rpc_get_global_server_count()
RETURNS integer LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
  SELECT count(*)::int FROM public.koda_servers
  WHERE COALESCE(server_version, '') NOT IN ('', 'HIBERNATED');
$fn$;

-- Eigenes Profil lesen (ersetzt anonyme koda_users-Selects)
CREATE OR REPLACE FUNCTION public.rpc_get_user_profile(p_app_uuid text, p_device_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_row public.koda_users%ROWTYPE;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT * INTO v_row FROM public.koda_users WHERE app_uuid = p_app_uuid;
  RETURN jsonb_build_object(
    'app_uuid', v_row.app_uuid,
    'nickname', v_row.nickname,
    'mc_username', v_row.mc_username,
    'code', v_row.code,
    'two_fa_enabled', v_row.two_fa_enabled,
    'is_banned', v_row.is_banned,
    'is_main', v_row.is_main,
    'permissions', v_row.permissions,
    'auth_id', v_row.auth_id
  );
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 14: Ticket-RPCs mit Token (Spoofing tot)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_create_ticket(p_reporter_uuid text, p_device_token text, p_ticket_type text, p_reference_id text, p_title text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE new_ticket_id uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token)
     AND NOT public.fn_is_praetor_admin(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  INSERT INTO public.support_tickets (reporter_uuid, ticket_type, reference_id, title, status)
  VALUES (p_reporter_uuid, p_ticket_type, p_reference_id, p_title, 'OPEN')
  RETURNING id INTO new_ticket_id;

  IF p_ticket_type IN ('BUG', 'BUG_REPORT', 'APP_BUG') THEN
    INSERT INTO public.support_bugs (id, description, reporter_uuid, status)
    VALUES (new_ticket_id, p_title, p_reporter_uuid, 'OPEN');
  ELSIF p_ticket_type = 'SERVER_REPORT' THEN
    INSERT INTO public.support_reports (id, server_host, reason, reporter_uuid, status)
    VALUES (new_ticket_id, p_reference_id, p_title, p_reporter_uuid, 'OPEN');
  END IF;

  RETURN new_ticket_id;
END;
$fn$;

CREATE OR REPLACE FUNCTION public.rpc_create_ticket_message(p_ticket_id uuid, p_sender_uuid text, p_device_token text, p_message text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_is_admin boolean := false;
BEGIN
  v_is_admin := public.fn_is_praetor_admin(p_sender_uuid, p_device_token);

  IF NOT v_is_admin AND NOT public.fn_verify_device_token(p_sender_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  -- Nicht-Admins duerfen nur in eigene Tickets schreiben
  IF NOT v_is_admin AND NOT EXISTS (
    SELECT 1 FROM public.support_tickets t
    WHERE t.id = p_ticket_id AND t.reporter_uuid = p_sender_uuid
  ) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  INSERT INTO public.support_ticket_messages (ticket_id, sender_uuid, message, is_admin)
  VALUES (p_ticket_id, p_sender_uuid, p_message, v_is_admin);
END;
$fn$;

CREATE OR REPLACE FUNCTION public.rpc_get_tickets(p_reporter_uuid text, p_device_token text, p_all boolean DEFAULT false)
RETURNS TABLE(id uuid, created_at timestamptz, reporter_uuid text, ticket_type text, reference_id text, title text, status text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_is_admin boolean;
BEGIN
  v_is_admin := public.fn_is_praetor_admin(p_reporter_uuid, p_device_token);

  IF NOT v_is_admin AND NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  -- Admin mit p_all=true sieht alle Tickets (Support-Uebersicht)
  IF v_is_admin AND p_all THEN
    RETURN QUERY SELECT t.id, t.created_at, t.reporter_uuid, t.ticket_type, t.reference_id, t.title, t.status
    FROM public.support_tickets t ORDER BY t.created_at DESC;
  ELSE
    RETURN QUERY SELECT t.id, t.created_at, t.reporter_uuid, t.ticket_type, t.reference_id, t.title, t.status
    FROM public.support_tickets t WHERE t.reporter_uuid = p_reporter_uuid ORDER BY t.created_at DESC;
  END IF;
END;
$fn$;

CREATE OR REPLACE FUNCTION public.rpc_get_ticket_messages(p_ticket_id uuid, p_reporter_uuid text, p_device_token text)
RETURNS TABLE(id uuid, ticket_id uuid, created_at timestamptz, sender_uuid text, message text, attachment_url text, is_admin boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
#variable_conflict use_column
DECLARE v_is_admin boolean := false;
BEGIN
  v_is_admin := public.fn_is_praetor_admin(p_reporter_uuid, p_device_token);

  IF NOT v_is_admin AND NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  IF v_is_admin OR EXISTS (
    SELECT 1 FROM public.support_tickets t
    WHERE t.id = p_ticket_id AND t.reporter_uuid = p_reporter_uuid
  ) THEN
    RETURN QUERY SELECT m.id, m.ticket_id, m.created_at, m.sender_uuid, m.message, m.attachment_url, m.is_admin
    FROM public.support_ticket_messages m WHERE m.ticket_id = p_ticket_id
    ORDER BY m.created_at ASC;
  END IF;
END;
$fn$;

-- ---------------------------------------------------------------------------
-- ABSCHNITT 15: report_tamper wird Log-Only (kein Auto-Bann mehr per Client)
-- Banns setzt das Team ab jetzt ueber rpc_admin_patch_user.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.report_tamper(p_hwid text, p_reason text, p_user_uuid uuid DEFAULT NULL::uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  -- NUR LOGGEN. Der alte Auto-Bann (UPDATE koda_users SET is_banned) war ein
  -- Massen-Bann-DoS-Vektor: jeder konnte mit dem Anon-Key beliebige UUIDs bannen.
  INSERT INTO public.banned_hwids (hwid, reason, is_scary)
  VALUES (p_hwid, p_reason,
    CASE WHEN p_reason LIKE 'PERMANENT_BAN_%' THEN true ELSE false END)
  ON CONFLICT (hwid) DO UPDATE SET
    reason = EXCLUDED.reason;
END;
$fn$;

-- report_high_risk bleibt Log-Only (ist es bereits)

-- ---------------------------------------------------------------------------
-- ABSCHNITT 16: Grants saeubern
-- Alle oeffentlichen RPCs: anon + authenticated duerfen EXECUTE (Pruefung
-- erfolgt IN der Funktion per Token). Interne Helper bleiben gesperrt.
-- ---------------------------------------------------------------------------
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO anon, authenticated;
REVOKE ALL ON FUNCTION public.fn_verify_device_token(text, text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.fn_is_praetor_admin(text, text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.trg_koda_users_bootstrap() FROM PUBLIC, anon, authenticated;
