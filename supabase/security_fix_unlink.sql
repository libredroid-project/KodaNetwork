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
-- patch_user_by_id: own row OR admin OR same auth_id (family = the other devices of that account)
CREATE OR REPLACE FUNCTION public.rpc_patch_user_by_id(p_app_uuid text, p_device_token text, p_id uuid, p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_caller_auth uuid; v_target_auth uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT auth_id INTO v_caller_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  SELECT auth_id INTO v_target_auth FROM public.koda_users WHERE id = p_id;

  IF NOT EXISTS (SELECT 1 FROM public.koda_users WHERE id = p_id AND app_uuid = p_app_uuid)
     AND NOT public.fn_is_praetor_admin(p_app_uuid, p_device_token)
     AND NOT (v_caller_auth IS NOT NULL AND v_caller_auth = v_target_auth) THEN
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

-- unlink: drops the account link of one row (same rules as patch_user_by_id)
CREATE OR REPLACE FUNCTION public.rpc_unlink_account(p_app_uuid text, p_device_token text, p_target_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_caller_auth uuid; v_target_auth uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT auth_id INTO v_caller_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  SELECT auth_id INTO v_target_auth FROM public.koda_users WHERE id = p_target_id;

  IF NOT EXISTS (SELECT 1 FROM public.koda_users WHERE id = p_target_id AND app_uuid = p_app_uuid)
     AND NOT public.fn_is_praetor_admin(p_app_uuid, p_device_token)
     AND NOT (v_caller_auth IS NOT NULL AND v_caller_auth = v_target_auth) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_users SET mc_username = NULL, auth_id = NULL WHERE id = p_target_id;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_unlink_account(text, text, uuid) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
-- permissions are patchable ONLY on foreign rows, inside the family/admin rule
-- (setting your own permissions would be self escalation)
CREATE OR REPLACE FUNCTION public.rpc_patch_user_by_id(p_app_uuid text, p_device_token text, p_id uuid, p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
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
    two_fa_password = CASE WHEN p_payload ? 'two_fa_password' THEN p_payload->>'two_fa_password' ELSE two_fa_password END,
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
$fn$;
NOTIFY pgrst, 'reload schema';
