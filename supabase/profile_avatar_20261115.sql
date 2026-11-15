/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */

-- the app's account sheet has always asked the profile RPC for avatar_url, but
-- the function never returned it. The avatar lives in account_profiles, keyed by
-- auth_id, so join it here. An account without a profile row or avatar gets "".

CREATE OR REPLACE FUNCTION public.rpc_get_user_profile(p_app_uuid text, p_device_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
  v_row public.koda_users%ROWTYPE;
  v_avatar text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT * INTO v_row FROM public.koda_users WHERE app_uuid = p_app_uuid;

  IF v_row.auth_id IS NOT NULL THEN
    SELECT avatar_url INTO v_avatar
    FROM public.account_profiles
    WHERE auth_id = v_row.auth_id;
  END IF;

  RETURN jsonb_build_object(
    'app_uuid', v_row.app_uuid,
    'nickname', v_row.nickname,
    'mc_username', v_row.mc_username,
    'code', v_row.code,
    'two_fa_enabled', v_row.two_fa_enabled,
    'is_banned', v_row.is_banned,
    'is_main', v_row.is_main,
    'permissions', v_row.permissions,
    'kodadash_beta', COALESCE(v_row.kodadash_beta, false),
    'avatar_url', COALESCE(v_avatar, ''),
    'auth_id', v_row.auth_id
  );
END;
$fn$;
