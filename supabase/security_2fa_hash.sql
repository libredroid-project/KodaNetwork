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
-- 2FA HASHING (2026-10-16): the lobby 2FA password was checked server side but
-- stored as plaintext in koda_users.two_fa_password. writes now store a bcrypt
-- hash (pgcrypto lives in the extensions schema), verification compares with
-- crypt(), and the one existing plaintext value is hashed in place. only these
-- three functions ever touch the column (verified via pg_proc), nothing reads
-- it back out, so no client needed changes.
-- ============================================================================

UPDATE public.koda_users
SET two_fa_password = extensions.crypt(two_fa_password, extensions.gen_salt('bf'))
WHERE two_fa_password IS NOT NULL
  AND two_fa_password <> ''
  AND two_fa_password NOT LIKE '$2%';

CREATE OR REPLACE FUNCTION public.rpc_patch_user_by_id(p_app_uuid text, p_device_token text, p_id uuid, p_payload jsonb)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE v_caller_auth uuid; v_target_auth uuid; v_self boolean;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT auth_id INTO v_caller_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  SELECT auth_id INTO v_target_auth FROM public.koda_users WHERE id = p_id;
  v_self := EXISTS (SELECT 1 FROM public.koda_users WHERE id = p_id AND app_uuid = p_app_uuid);

  IF NOT v_self
     AND NOT public.fn_is_praetor_admin(p_app_uuid, p_device_token)
     AND NOT (v_caller_auth IS NOT NULL AND v_caller_auth = v_target_auth) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  -- permissions must never get set from your own device
  IF v_self AND p_payload ? 'permissions' THEN
    p_payload := p_payload - 'permissions';
  END IF;

  UPDATE public.koda_users SET
    mc_username = CASE WHEN p_payload ? 'mc_username' THEN p_payload->>'mc_username' ELSE mc_username END,
    nickname = CASE WHEN p_payload ? 'nickname' THEN p_payload->>'nickname' ELSE nickname END,
    two_fa_enabled = CASE WHEN p_payload ? 'two_fa_enabled' THEN (p_payload->>'two_fa_enabled')::boolean ELSE two_fa_enabled END,
    two_fa_password = CASE WHEN p_payload ? 'two_fa_password' THEN
        CASE WHEN NULLIF(p_payload->>'two_fa_password', '') IS NULL THEN NULL
             ELSE extensions.crypt(p_payload->>'two_fa_password', extensions.gen_salt('bf')) END
      ELSE two_fa_password END,
    device_ram_mb = CASE WHEN p_payload ? 'device_ram_mb' THEN (p_payload->>'device_ram_mb')::integer ELSE device_ram_mb END,
    is_main = CASE WHEN p_payload ? 'is_main' THEN (p_payload->>'is_main')::boolean ELSE is_main END,
    code = CASE WHEN p_payload ? 'code' THEN p_payload->>'code' ELSE code END,
    app_state = CASE WHEN p_payload ? 'app_state' THEN p_payload->>'app_state' ELSE app_state END,
    app_last_ping = CASE WHEN p_payload ? 'app_last_ping' THEN (p_payload->>'app_last_ping')::timestamp with time zone ELSE app_last_ping END,
    device_model = CASE WHEN p_payload ? 'device_model' THEN p_payload->>'device_model' ELSE device_model END,
    os_version = CASE WHEN p_payload ? 'os_version' THEN p_payload->>'os_version' ELSE os_version END,
    app_version = CASE WHEN p_payload ? 'app_version' THEN p_payload->>'app_version' ELSE app_version END,
    permissions = CASE WHEN p_payload ? 'permissions' THEN p_payload->'permissions' ELSE permissions END
  WHERE id = p_id;
END;
$function$

CREATE OR REPLACE FUNCTION public.rpc_patch_user(p_app_uuid text, p_device_token text, p_payload jsonb)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_users SET
    mc_username = CASE WHEN p_payload ? 'mc_username' THEN p_payload->>'mc_username' ELSE mc_username END,
    nickname = CASE WHEN p_payload ? 'nickname' THEN p_payload->>'nickname' ELSE nickname END,
    two_fa_enabled = CASE WHEN p_payload ? 'two_fa_enabled' THEN (p_payload->>'two_fa_enabled')::boolean ELSE two_fa_enabled END,
    two_fa_password = CASE WHEN p_payload ? 'two_fa_password' THEN
        CASE WHEN NULLIF(p_payload->>'two_fa_password', '') IS NULL THEN NULL
             ELSE extensions.crypt(p_payload->>'two_fa_password', extensions.gen_salt('bf')) END
      ELSE two_fa_password END,
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
$function$

CREATE OR REPLACE FUNCTION public.rpc_lobby_verify_2fa(p_mc_username text, p_password text)
 RETURNS boolean
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  SELECT EXISTS (
    SELECT 1 FROM public.koda_users u
    WHERE lower(u.mc_username) = lower(p_mc_username)
      AND u.two_fa_enabled = true
      AND u.two_fa_password IS NOT NULL
      AND u.two_fa_password = extensions.crypt(p_password, u.two_fa_password)
  );
$function$

NOTIFY pgrst, 'reload schema';
