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
-- ============================================================================
-- HWID STABILITY 2026-09-17: account adoption + ban carryover on migration
-- ============================================================================

-- servers of the whole account family (all devices sharing one auth_id)
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
    RETURN; -- not logged in, so there is no family to resolve
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

-- hand every family server over to the caller (hwid or device change)
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

-- migration: carry is_banned over too, else a hwid change would shake off a ban
CREATE OR REPLACE FUNCTION public.rpc_migrate_servers(p_old_app_uuid text, p_old_device_token text, p_new_app_uuid text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_banned boolean; v_perms jsonb;
BEGIN
  IF NOT public.fn_verify_device_token(p_old_app_uuid, p_old_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_servers SET owner_app_uuid = p_new_app_uuid
  WHERE owner_app_uuid = p_old_app_uuid;

  -- move ban state and permissions from the old device row to the new one
  SELECT is_banned, permissions INTO v_banned, v_perms
  FROM public.koda_users WHERE app_uuid = p_old_app_uuid LIMIT 1;
  IF v_banned IS NOT NULL THEN
    UPDATE public.koda_users
    SET is_banned = v_banned,
        permissions = COALESCE(v_perms, permissions)
    WHERE app_uuid = p_new_app_uuid
      AND NOT (is_banned = true AND v_banned = false); -- never lift a ban that is already there
  END IF;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_get_servers_by_auth_id(text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_adopt_servers(text, text) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
-- varchar columns against RETURNS TABLE(text) make RETURN QUERY strict here, so
-- recreate the affected rpcs with ::text casts.

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
-- link, two steps: the plugin opens a PENDING request, the app confirms it.
CREATE OR REPLACE FUNCTION public.rpc_lobby_link_account(p_code text, p_mc_username text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_count integer;
BEGIN
  IF p_code IS NULL OR length(p_code) < 8 OR p_mc_username IS NULL OR length(p_mc_username) > 16 THEN
    RETURN 'invalid';
  END IF;
  -- already confirmed?
  UPDATE public.koda_users SET mc_username = p_mc_username
  WHERE code = p_code AND mc_username IS NULL AND pending_mc_username = p_mc_username;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  IF v_count > 0 THEN
    RETURN 'ok';
  END IF;
  -- otherwise remember the request (throws away an older one)
  UPDATE public.koda_users
  SET pending_mc_username = p_mc_username
  WHERE code = p_code AND mc_username IS NULL;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN CASE WHEN v_count > 0 THEN 'pending' ELSE 'invalid' END;
END;
$fn$;

-- app: fetch the pending link request
CREATE OR REPLACE FUNCTION public.rpc_get_pending_link(p_app_uuid text, p_device_token text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_pending text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT pending_mc_username INTO v_pending FROM public.koda_users
  WHERE app_uuid = p_app_uuid AND pending_mc_username IS NOT NULL
  ORDER BY created_at DESC LIMIT 1;
  RETURN COALESCE(v_pending, '');
END;
$fn$;

-- app: accept or reject the request
CREATE OR REPLACE FUNCTION public.rpc_resolve_pending_link(p_app_uuid text, p_device_token text, p_accept boolean)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_pending text; v_count integer;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT pending_mc_username INTO v_pending FROM public.koda_users
  WHERE app_uuid = p_app_uuid AND pending_mc_username IS NOT NULL
  ORDER BY created_at DESC LIMIT 1;
  IF v_pending IS NULL THEN
    RETURN false;
  END IF;
  IF p_accept THEN
    UPDATE public.koda_users
    SET mc_username = v_pending, pending_mc_username = NULL
    WHERE app_uuid = p_app_uuid AND pending_mc_username = v_pending AND mc_username IS NULL;
  ELSE
    UPDATE public.koda_users
    SET pending_mc_username = NULL
    WHERE app_uuid = p_app_uuid AND pending_mc_username = v_pending;
  END IF;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count > 0;
END;
$fn$;

ALTER TABLE public.koda_users ADD COLUMN IF NOT EXISTS pending_mc_username varchar(16);

GRANT EXECUTE ON FUNCTION public.rpc_get_pending_link(text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_resolve_pending_link(text, text, boolean) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
