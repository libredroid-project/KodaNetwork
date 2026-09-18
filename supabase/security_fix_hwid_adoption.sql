-- ============================================================================
-- HWID-STABILITAET 2026-09-17: Konto-Adoption + Ban-Carryover bei Migration
-- ============================================================================

-- Server der gesamten Konto-Familie (alle Geraete mit gleicher auth_id)
CREATE OR REPLACE FUNCTION public.rpc_get_servers_by_auth_id(p_app_uuid text, p_device_token text)
RETURNS TABLE(id uuid, host text, ram_mb integer, server_version text, owner_app_uuid text, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_auth uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT auth_id INTO v_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  IF v_auth IS NULL THEN
    RETURN; -- nicht eingeloggt: keine Familien-Aufloesung moeglich
  END IF;
  RETURN QUERY
  SELECT s.id, s.host, s.ram_mb, s.server_version, s.owner_app_uuid, s.created_at
  FROM public.koda_servers s
  JOIN public.koda_users u ON u.app_uuid = s.owner_app_uuid AND u.auth_id = v_auth
  WHERE s.host NOT LIKE 'deleted_%'
    AND COALESCE(s.server_version, '') NOT IN ('DELETED')
    AND s.owner_app_uuid <> p_app_uuid;
END;
$fn$;

-- Alle Familien-Server auf den Aufrufer uebertragen (HWID-Wechsel/Geraetewechsel)
CREATE OR REPLACE FUNCTION public.rpc_adopt_servers(p_app_uuid text, p_device_token text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_auth uuid; v_count integer;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT auth_id INTO v_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  IF v_auth IS NULL THEN
    RETURN 0;
  END IF;
  UPDATE public.koda_servers s
  SET owner_app_uuid = p_app_uuid
  FROM public.koda_users u
  WHERE u.app_uuid = s.owner_app_uuid
    AND u.auth_id = v_auth
    AND s.owner_app_uuid <> p_app_uuid
    AND s.host NOT LIKE 'deleted_%';
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$fn$;

-- Migration: zusaetzlich is_banned uebernehmen (sonst koennte man Banns per HWID-Wechsel entkommen)
CREATE OR REPLACE FUNCTION public.rpc_migrate_servers(p_old_app_uuid text, p_old_device_token text, p_new_app_uuid text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_banned boolean; v_perms jsonb;
BEGIN
  IF NOT public.fn_verify_device_token(p_old_app_uuid, p_old_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_servers SET owner_app_uuid = p_new_app_uuid
  WHERE owner_app_uuid = p_old_app_uuid;

  -- Ban-Status und Berechtigungen der alten Geraet-Zeile auf die neue uebernehmen
  SELECT is_banned, permissions INTO v_banned, v_perms
  FROM public.koda_users WHERE app_uuid = p_old_app_uuid LIMIT 1;
  IF v_banned IS NOT NULL THEN
    UPDATE public.koda_users
    SET is_banned = v_banned,
        permissions = COALESCE(v_perms, permissions)
    WHERE app_uuid = p_new_app_uuid
      AND NOT (is_banned = true AND v_banned = false); -- bestehende Banns nicht aufheben
  END IF;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_get_servers_by_auth_id(text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_adopt_servers(text, text) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
-- varchar-Spalten in RETURNS-TABLE(text) machen RETURN QUERY hier strikt:
-- betroffene RPCs mit ::text-Casts neu erstellen.

DROP FUNCTION IF EXISTS public.rpc_get_my_servers(text, text);
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
  RETURN QUERY SELECT s.id, s.host::text, s.ram_mb, s.server_version::text, s.base_domain::text,
    s.kodadash_port, s.kodadash_token::text, s.online_players, s.is_banned, s.gamemode,
    s.difficulty, s.pvp, s.whitelist, s.motd, s.max_players, s.last_online,
    s.owner_app_uuid::text, s.created_at
  FROM public.koda_servers s WHERE s.owner_app_uuid = p_app_uuid;
END;
$fn$;

DROP FUNCTION IF EXISTS public.rpc_get_linked_accounts(text, text);
CREATE OR REPLACE FUNCTION public.rpc_get_linked_accounts(p_app_uuid text, p_device_token text)
RETURNS TABLE(id uuid, app_uuid text, mc_username text, nickname text, is_main boolean, auth_id uuid, device_model text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_auth uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT auth_id INTO v_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  RETURN QUERY SELECT u.id, u.app_uuid::text, u.mc_username::text, u.nickname, u.is_main, u.auth_id, u.device_model
  FROM public.koda_users u
  WHERE (u.app_uuid = p_app_uuid OR (v_auth IS NOT NULL AND u.auth_id = v_auth))
    AND u.mc_username IS NOT NULL
  ORDER BY u.is_main DESC;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_get_my_servers(text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_get_linked_accounts(text, text) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
