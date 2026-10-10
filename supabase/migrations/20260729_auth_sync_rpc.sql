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
-- copy this into the supabase dashboard, SQL editor tab, and run it!

-- 1. new function for the login/logout sync
CREATE OR REPLACE FUNCTION public.rpc_sync_auth_id(p_app_uuid text, p_auth_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
  -- push the new auth_id onto every existing row of this app_uuid
  UPDATE public.koda_users 
  SET auth_id = p_auth_id 
  WHERE app_uuid = p_app_uuid;
  
  -- no row yet (fresh app install), so make a hidden background entry
  IF NOT FOUND THEN
    INSERT INTO public.koda_users (app_uuid, auth_id, code) 
    VALUES (p_app_uuid, p_auth_id, 'LINKED');
  END IF;
END;
$$;


-- 2. repair the patch function (device_model, battery_level and the rest were not getting saved!)
CREATE OR REPLACE FUNCTION public.rpc_patch_user(p_app_uuid text, p_payload jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
  UPDATE public.koda_users SET 
    mc_username = CASE WHEN p_payload ? 'mc_username' THEN p_payload->>'mc_username' ELSE mc_username END,
    two_fa_enabled = CASE WHEN p_payload ? 'two_fa_enabled' THEN (p_payload->>'two_fa_enabled')::boolean ELSE two_fa_enabled END,
    two_fa_password = CASE WHEN p_payload ? 'two_fa_password' THEN p_payload->>'two_fa_password' ELSE two_fa_password END,
    device_ram_mb = CASE WHEN p_payload ? 'device_ram_mb' THEN (p_payload->>'device_ram_mb')::integer ELSE device_ram_mb END,
    is_main = CASE WHEN p_payload ? 'is_main' THEN (p_payload->>'is_main')::boolean ELSE is_main END,
    code = CASE WHEN p_payload ? 'code' THEN p_payload->>'code' ELSE code END,
    app_state = CASE WHEN p_payload ? 'app_state' THEN p_payload->>'app_state' ELSE app_state END,
    app_last_ping = CASE WHEN p_payload ? 'app_last_ping' THEN (p_payload->>'app_last_ping')::timestamp with time zone ELSE app_last_ping END,
    
    -- these were the missing ones:
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
$$;
