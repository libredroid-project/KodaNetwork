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
-- COMMAND GUARD (2026-10-16): server_version doubles as the command channel
-- (CMD:START/STOP/RESTART/HIBERNATE/DELETE/...), but the app's own status report
-- writes that column every 15 seconds - and it could overwrite a command the
-- device had not picked up yet. In practice "stop" from the web panel vanished
-- while "start" happened to get through. A write may replace a pending CMD: only
-- with another CMD:; state reports bounce off it until the device acknowledges
-- (the acknowledgement is a normal state value). This is the live definition of
-- rpc_patch_server with that one CASE branch replaced.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.rpc_patch_server(p_app_uuid text, p_device_token text, p_host text, p_payload jsonb)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
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
    -- the command channel lives in this column, and the app's own status report
    -- (every 15 s) used to overwrite commands the device had not picked up yet -
    -- that is how a "stop" from the web panel could vanish. A write may replace a
    -- pending CMD: only with another CMD:; state reports bounce off it until the
    -- device consumed the command (its acknowledgement is a normal state value).
    server_version = CASE
      WHEN p_payload ? 'server_version' AND p_payload->>'server_version' LIKE 'CMD:%'
        THEN p_payload->>'server_version'
      WHEN p_payload ? 'server_version' AND COALESCE(server_version, '') LIKE 'CMD:%'
        THEN server_version
      WHEN p_payload ? 'server_version'
        THEN p_payload->>'server_version'
      ELSE server_version END,
    online_players = CASE WHEN p_payload ? 'online_players' THEN (p_payload->>'online_players')::integer ELSE online_players END,
    last_online = CASE WHEN p_payload ? 'last_online' THEN (p_payload->>'last_online')::timestamp with time zone ELSE last_online END,
    gamemode = CASE WHEN p_payload ? 'gamemode' THEN p_payload->>'gamemode' ELSE gamemode END,
    difficulty = CASE WHEN p_payload ? 'difficulty' THEN p_payload->>'difficulty' ELSE difficulty END,
    pvp = CASE WHEN p_payload ? 'pvp' THEN (p_payload->>'pvp')::boolean ELSE pvp END,
    whitelist = CASE WHEN p_payload ? 'whitelist' THEN (p_payload->>'whitelist')::boolean ELSE whitelist END,
    motd = CASE WHEN p_payload ? 'motd' THEN p_payload->>'motd' ELSE motd END,
    max_players = CASE WHEN p_payload ? 'max_players' THEN (p_payload->>'max_players')::integer ELSE max_players END,
    ram_mb = CASE WHEN p_payload ? 'ram_mb' THEN (p_payload->>'ram_mb')::integer ELSE ram_mb END,
    bedrock_port = CASE WHEN p_payload ? 'bedrock_port' THEN (p_payload->>'bedrock_port')::integer ELSE bedrock_port END,
    voicechat_port = CASE WHEN p_payload ? 'voicechat_port' THEN (p_payload->>'voicechat_port')::integer ELSE voicechat_port END,
    kodadash_port = CASE WHEN p_payload ? 'kodadash_port' THEN (p_payload->>'kodadash_port')::integer ELSE kodadash_port END,
    kodadash_token = CASE WHEN p_payload ? 'kodadash_token' THEN p_payload->>'kodadash_token' ELSE kodadash_token END
  WHERE host = p_host;
END;
$function$
