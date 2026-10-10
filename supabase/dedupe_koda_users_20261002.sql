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
-- KODA_USERS DEDUPE + LINK CODE FIX 2026-10-02
-- ============================================================================
-- where the duplicates came from: "Link New Account" in the app inserted a new
-- koda_users row on EVERY tap (raw POST, see SettingsActivity). the trigger gave
-- every row its own device_token and rpc_get_is_banned read without ORDER BY ->
-- whichever row the plan felt like. result: up to 9 rows per device, doubled
-- entries in the UI, token lookups that kept flipping around.
--
-- order in here: backup (backup-20261002/04_...json) -> dedupe -> stable
-- pick -> unique index -> link code rpc -> one MC account per device.

DO $mig$
DECLARE r record; v_token text; v_id uuid;
BEGIN
  FOR r IN SELECT app_uuid FROM public.koda_users GROUP BY app_uuid HAVING count(*) > 1 LOOP
    -- survivor: the newest row. its token becomes the one rpc_get_is_banned
    -- returns TODAY - and that is the token the running app has stored, so the
    -- live session stays valid.
    v_token := (public.rpc_get_is_banned(r.app_uuid) ->> 'device_token');
    SELECT id INTO v_id FROM public.koda_users
    WHERE app_uuid = r.app_uuid ORDER BY created_at DESC LIMIT 1;

    -- fill the survivor's NULL fields from the old rows
    UPDATE public.koda_users s SET
      auth_id      = COALESCE(s.auth_id, o.auth_id),
      mc_username  = COALESCE(s.mc_username, o.mc_username),
      nickname     = COALESCE(s.nickname, o.nickname),
      device_model = COALESCE(s.device_model, o.device_model)
    FROM public.koda_users o
    WHERE s.id = v_id AND o.app_uuid = r.app_uuid AND o.id <> v_id;

    -- delete the old rows first (device_token is globally unique), then take
    -- the running app's token over to the survivor
    DELETE FROM public.koda_users WHERE app_uuid = r.app_uuid AND id <> v_id;
    UPDATE public.koda_users SET device_token = v_token WHERE id = v_id;
  END LOOP;
END
$mig$;

-- deterministic from now on: always the newest row
CREATE OR REPLACE FUNCTION public.rpc_get_is_banned(p_app_uuid text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
  v_banned boolean;
  v_token text;
BEGIN
  SELECT is_banned, device_token INTO v_banned, v_token
  FROM public.koda_users WHERE app_uuid = p_app_uuid
  ORDER BY created_at DESC LIMIT 1;

  IF NOT FOUND THEN
    INSERT INTO public.koda_users (app_uuid)
    VALUES (p_app_uuid)
    ON CONFLICT DO NOTHING;
    SELECT device_token INTO v_token FROM public.koda_users
    WHERE app_uuid = p_app_uuid ORDER BY created_at DESC LIMIT 1;
    RETURN jsonb_build_object('banned', false, 'device_token', v_token);
  END IF;

  RETURN jsonb_build_object('banned', COALESCE(v_banned, false), 'device_token', v_token);
END;
$fn$;

-- duplicates can no longer physically happen
CREATE UNIQUE INDEX IF NOT EXISTS uniq_koda_users_app_uuid ON public.koda_users (app_uuid);

-- new link code: rpc on the EXISTING row instead of a raw INSERT from the app
CREATE OR REPLACE FUNCTION public.rpc_regenerate_link_code(p_app_uuid text, p_device_token text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $fn$
DECLARE v_code text; v_exists boolean; v_auth uuid; v_main boolean;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT TRUE INTO v_exists FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  IF v_exists IS DISTINCT FROM TRUE THEN
    SELECT auth_id INTO v_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
    v_main := NOT EXISTS (SELECT 1 FROM public.koda_users u
                          WHERE u.auth_id = v_auth AND u.auth_id IS NOT NULL);
    INSERT INTO public.koda_users (app_uuid, auth_id, is_main) VALUES (p_app_uuid, v_auth, v_main);
  END IF;
  LOOP
    v_code := upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 8));
    EXIT WHEN NOT EXISTS (SELECT 1 FROM public.koda_users WHERE code = v_code);
  END LOOP;
  UPDATE public.koda_users SET code = v_code, pending_mc_username = NULL
  WHERE app_uuid = p_app_uuid;
  RETURN v_code;
END;
$fn$;

-- one MC account hangs on ONE device now: confirming a link removes the
-- username from all the other device rows.
CREATE OR REPLACE FUNCTION public.rpc_lobby_link_account(p_code text, p_mc_username text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_count integer;
BEGIN
  IF p_code IS NULL OR length(p_code) < 8 OR p_mc_username IS NULL OR length(p_mc_username) > 16 THEN
    RETURN 'invalid';
  END IF;
  UPDATE public.koda_users SET mc_username = p_mc_username
  WHERE code = p_code AND mc_username IS NULL AND pending_mc_username = p_mc_username;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  IF v_count > 0 THEN
    -- clear the same name on the other devices (double display)
    UPDATE public.koda_users SET mc_username = NULL
    WHERE mc_username = p_mc_username AND code <> p_code;
    RETURN 'ok';
  END IF;
  UPDATE public.koda_users
  SET pending_mc_username = p_mc_username
  WHERE code = p_code AND mc_username IS NULL;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN CASE WHEN v_count > 0 THEN 'pending' ELSE 'invalid' END;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_regenerate_link_code(text, text) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
