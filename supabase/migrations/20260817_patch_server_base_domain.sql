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
-- 2026-08-17: make base_domain patchable in the server patch rpcs
-- so the app can sync the base domain (.kodanetwork.eu <-> .kodaserv.eu) up to
-- supabase whenever someone changes their join address.
-- applied live on 2026-08-17 (rollback: supabase/backup-20260817/01_functions_backup.sql).

CREATE OR REPLACE FUNCTION public.rpc_patch_server(p_app_uuid text, p_host text, p_payload jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $function$
DECLARE
  v_owner text;
BEGIN
  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE host = p_host;
  IF v_owner IS NULL THEN
    RAISE EXCEPTION 'Server not found';
  END IF;

  IF v_owner != p_app_uuid THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_servers
  SET
    host = CASE WHEN p_payload ? 'host' THEN p_payload->>'host' ELSE host END,
    base_domain = CASE WHEN p_payload ? 'base_domain' THEN p_payload->>'base_domain' ELSE base_domain END,
    server_version = CASE WHEN p_payload ? 'server_version' THEN p_payload->>'server_version' ELSE server_version END,
    owner_app_uuid = CASE WHEN p_payload ? 'owner_app_uuid' THEN p_payload->>'owner_app_uuid' ELSE owner_app_uuid END,
    online_players = CASE WHEN p_payload ? 'online_players' THEN (p_payload->>'online_players')::integer ELSE online_players END,
    last_online = CASE WHEN p_payload ? 'last_online' THEN (p_payload->>'last_online')::timestamp with time zone ELSE last_online END,
    gamemode = CASE WHEN p_payload ? 'gamemode' THEN p_payload->>'gamemode' ELSE gamemode END,
    difficulty = CASE WHEN p_payload ? 'difficulty' THEN p_payload->>'difficulty' ELSE difficulty END,
    pvp = CASE WHEN p_payload ? 'pvp' THEN (p_payload->>'pvp')::boolean ELSE pvp END,
    whitelist = CASE WHEN p_payload ? 'whitelist' THEN (p_payload->>'whitelist')::boolean ELSE whitelist END,
    motd = CASE WHEN p_payload ? 'motd' THEN p_payload->>'motd' ELSE motd END,
    max_players = CASE WHEN p_payload ? 'max_players' THEN (p_payload->>'max_players')::integer ELSE max_players END,
    kodadash_port = CASE WHEN p_payload ? 'kodadash_port' THEN (p_payload->>'kodadash_port')::integer ELSE kodadash_port END,
    kodadash_token = CASE WHEN p_payload ? 'kodadash_token' THEN p_payload->>'kodadash_token' ELSE kodadash_token END
  WHERE host = p_host;
END;
$function$;

CREATE OR REPLACE FUNCTION public.rpc_patch_server_by_id(p_app_uuid text, p_id uuid, p_payload jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $function$
DECLARE
  v_owner text;
BEGIN
  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE id = p_id;
  IF v_owner IS NULL THEN
    RAISE EXCEPTION 'Server not found';
  END IF;

  IF v_owner != p_app_uuid THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  UPDATE public.koda_servers
  SET
    host = CASE WHEN p_payload ? 'host' THEN p_payload->>'host' ELSE host END,
    base_domain = CASE WHEN p_payload ? 'base_domain' THEN p_payload->>'base_domain' ELSE base_domain END,
    server_version = CASE WHEN p_payload ? 'server_version' THEN p_payload->>'server_version' ELSE server_version END,
    owner_app_uuid = CASE WHEN p_payload ? 'owner_app_uuid' THEN p_payload->>'owner_app_uuid' ELSE owner_app_uuid END,
    online_players = CASE WHEN p_payload ? 'online_players' THEN (p_payload->>'online_players')::integer ELSE online_players END,
    last_online = CASE WHEN p_payload ? 'last_online' THEN (p_payload->>'last_online')::timestamp with time zone ELSE last_online END,
    gamemode = CASE WHEN p_payload ? 'gamemode' THEN p_payload->>'gamemode' ELSE gamemode END,
    difficulty = CASE WHEN p_payload ? 'difficulty' THEN p_payload->>'difficulty' ELSE difficulty END,
    pvp = CASE WHEN p_payload ? 'pvp' THEN (p_payload->>'pvp')::boolean ELSE pvp END,
    whitelist = CASE WHEN p_payload ? 'whitelist' THEN (p_payload->>'whitelist')::boolean ELSE whitelist END,
    motd = CASE WHEN p_payload ? 'motd' THEN p_payload->>'motd' ELSE motd END,
    max_players = CASE WHEN p_payload ? 'max_players' THEN (p_payload->>'max_players')::integer ELSE max_players END
  WHERE id = p_id;
END;
$function$;
