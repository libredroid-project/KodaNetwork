-- View um id erweitern (Ticket-Referenzen + Host-Lookups ohne Tabellenzugriff)
DROP VIEW IF EXISTS public.v_servers_public;
CREATE VIEW public.v_servers_public AS
SELECT id, host, server_version, base_domain, online_players, max_players, motd,
       gamemode, difficulty, pvp, whitelist, is_banned, last_online, ram_mb
FROM public.koda_servers;
GRANT SELECT ON public.v_servers_public TO anon, authenticated;

-- Admin: User-Liste fuer PraetorAccountsActivity
CREATE OR REPLACE FUNCTION public.rpc_admin_list_users(p_admin_app_uuid text, p_admin_device_token text)
RETURNS TABLE(id uuid, app_uuid text, nickname text, mc_username text, device_model text,
              is_banned boolean, is_main boolean, permissions jsonb, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_is_praetor_admin(p_admin_app_uuid, p_admin_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  RETURN QUERY SELECT u.id, u.app_uuid, u.nickname, u.mc_username, u.device_model,
    u.is_banned, u.is_main, u.permissions, u.created_at
  FROM public.koda_users u ORDER BY u.created_at DESC LIMIT 500;
END;
$fn$;

-- Admin: Server-Zeile nach Host (Loesch-Bestaetigung etc.)
CREATE OR REPLACE FUNCTION public.rpc_admin_find_server(p_admin_app_uuid text, p_admin_device_token text, p_host text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_row public.koda_servers%ROWTYPE;
BEGIN
  IF NOT public.fn_is_praetor_admin(p_admin_app_uuid, p_admin_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT * INTO v_row FROM public.koda_servers WHERE host = p_host;
  IF NOT FOUND THEN RETURN NULL; END IF;
  RETURN jsonb_build_object('id', v_row.id, 'host', v_row.host, 'owner_app_uuid', v_row.owner_app_uuid,
    'server_version', v_row.server_version, 'is_banned', v_row.is_banned, 'ban_reason', v_row.ban_reason,
    'kodadash_port', v_row.kodadash_port, 'kodadash_token', v_row.kodadash_token);
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_admin_list_users(text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_admin_find_server(text, text, text) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
-- Verknuepfte Accounts fuer das Settings-UI (früher anonymer Table-SELECT)
CREATE OR REPLACE FUNCTION public.rpc_get_linked_accounts(p_app_uuid text, p_device_token text)
RETURNS TABLE(app_uuid text, mc_username text, nickname text, is_main boolean, auth_id uuid, device_model text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_auth uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT auth_id INTO v_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  RETURN QUERY SELECT u.app_uuid, u.mc_username, u.nickname, u.is_main, u.auth_id, u.device_model
  FROM public.koda_users u
  WHERE (u.app_uuid = p_app_uuid OR (v_auth IS NOT NULL AND u.auth_id = v_auth))
    AND u.mc_username IS NOT NULL
  ORDER BY u.is_main DESC;
END;
$fn$;
GRANT EXECUTE ON FUNCTION public.rpc_get_linked_accounts(text, text) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
