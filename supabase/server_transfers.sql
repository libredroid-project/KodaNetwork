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
-- SERVER TRANSFER (DEVICE SWITCH) 2026-10-02
-- ============================================================================
-- direct transfer of servers between devices of the same user (wireless, both
-- devices in range). the old device's app serves encrypted full server zips
-- over the local network; supabase stores ONLY the handshake:
-- pairing code, LAN endpoint, http token, aes key and the manifest.
-- the server data itself never goes through the cloud.
--
-- lifecycle: open -> done | expired (TTL 15 minutes, single use code).

CREATE TABLE IF NOT EXISTS public.server_transfers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(8) NOT NULL UNIQUE,
  from_app_uuid text NOT NULL,
  auth_id uuid,
  lan_ip text NOT NULL,
  lan_port integer NOT NULL,
  http_token text NOT NULL,
  aes_key text NOT NULL,
  servers jsonb NOT NULL DEFAULT '[]',
  state text NOT NULL DEFAULT 'open',
  claimed_by text,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT now() + interval '15 minutes'
);

ALTER TABLE public.server_transfers ENABLE ROW LEVEL SECURITY;
-- writes go through rpcs only: no direct table access for anon/authenticated
REVOKE ALL ON public.server_transfers FROM anon, authenticated;

-- create a transfer (old device). checks the device token and that every host
-- in the manifest belongs to the sender (no transferring other people's servers).
CREATE OR REPLACE FUNCTION public.rpc_create_transfer(
  p_app_uuid text, p_device_token text,
  p_lan_ip text, p_lan_port integer,
  p_http_token text, p_aes_key text, p_servers jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $fn$
DECLARE v_auth uuid; v_code text; v_host text; v_row public.server_transfers%ROWTYPE;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  IF p_lan_ip IS NULL OR length(p_lan_ip) < 7 OR p_lan_port IS NULL
     OR p_lan_port < 1024 OR p_lan_port > 65535
     OR COALESCE(p_http_token, '') = '' OR COALESCE(p_aes_key, '') = ''
     OR p_servers IS NULL OR jsonb_array_length(p_servers) = 0 THEN
    RAISE EXCEPTION 'invalid payload';
  END IF;

  -- every host must belong to the sender and must not be deleted
  FOR v_host IN SELECT jsonb_array_elements_text(p_servers) AS host LOOP
    IF NOT EXISTS (
      SELECT 1 FROM public.koda_servers
      WHERE owner_app_uuid = p_app_uuid AND host = v_host
        AND host NOT LIKE 'deleted_%'
        AND COALESCE(server_version, '') NOT IN ('DELETED')
    ) THEN
      RAISE EXCEPTION 'server not owned: %', v_host;
    END IF;
  END LOOP;

  SELECT auth_id INTO v_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;

  -- one open transfer per device, so let the old open ones expire
  UPDATE public.server_transfers SET state = 'expired'
  WHERE from_app_uuid = p_app_uuid AND state = 'open';

  -- cleanup: throw out rows older than 24 hours for good
  DELETE FROM public.server_transfers WHERE created_at < now() - interval '24 hours';

  -- pull a collision free 8 character code (same style as koda_users.code)
  LOOP
    v_code := upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 8));
    EXIT WHEN NOT EXISTS (SELECT 1 FROM public.server_transfers WHERE code = v_code);
  END LOOP;

  INSERT INTO public.server_transfers (code, from_app_uuid, auth_id, lan_ip, lan_port,
    http_token, aes_key, servers, expires_at)
  VALUES (v_code, p_app_uuid, v_auth, p_lan_ip, p_lan_port,
    p_http_token, p_aes_key, p_servers, now() + interval '15 minutes')
  RETURNING * INTO v_row;

  RETURN to_jsonb(v_row) - 'http_token' - 'aes_key' || jsonb_build_object('code', v_code);
END;
$fn$;

-- fetch a transfer (new device). the 8 character code is the knowledge that
-- opens it (single use + 15 minute TTL). endpoint, token, key and manifest only
-- come out for a valid, still open transfer.
CREATE OR REPLACE FUNCTION public.rpc_get_transfer(p_code text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_row public.server_transfers%ROWTYPE;
BEGIN
  IF p_code IS NULL OR length(p_code) < 8 THEN
    RETURN jsonb_build_object('state', 'invalid');
  END IF;
  SELECT * INTO v_row FROM public.server_transfers WHERE code = upper(p_code) LIMIT 1;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('state', 'invalid');
  END IF;
  IF v_row.state = 'open' AND now() > v_row.expires_at THEN
    UPDATE public.server_transfers SET state = 'expired' WHERE id = v_row.id;
    v_row.state := 'expired';
  END IF;
  IF v_row.state <> 'open' THEN
    RETURN jsonb_build_object('state', v_row.state);
  END IF;
  RETURN to_jsonb(v_row);
END;
$fn$;

-- finish the transfer (new device, after the import is complete).
CREATE OR REPLACE FUNCTION public.rpc_finish_transfer(
  p_code text, p_app_uuid text, p_device_token text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_count integer;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  UPDATE public.server_transfers
  SET state = 'done', claimed_by = p_app_uuid
  WHERE code = upper(p_code) AND state = 'open' AND now() <= expires_at;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count > 0;
END;
$fn$;

-- own transfers (the old device polls this for completion; MainActivity cleans
-- up on the next start in case the app was dead in between).
CREATE OR REPLACE FUNCTION public.rpc_my_transfers(p_app_uuid text, p_device_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  UPDATE public.server_transfers SET state = 'expired'
  WHERE from_app_uuid = p_app_uuid AND state = 'open' AND now() > expires_at;
  RETURN COALESCE((
    SELECT jsonb_agg(to_jsonb(t) - 'http_token' - 'aes_key')
    FROM (
      SELECT id, code, servers, state, claimed_by, created_at, expires_at
      FROM public.server_transfers
      WHERE from_app_uuid = p_app_uuid
      ORDER BY created_at DESC LIMIT 5
    ) t
  ), '[]'::jsonb);
END;
$fn$;

-- adopt selected hosts (new device): moves ONLY the transferred cloud rows, so
-- servers that should stay on the old device keep running there.
-- rpc_adopt_servers on the other hand would steal the whole family.
CREATE OR REPLACE FUNCTION public.rpc_adopt_hosts(
  p_app_uuid text, p_device_token text, p_hosts text[])
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
    AND s.host = ANY(p_hosts)
    AND s.host NOT LIKE 'deleted_%';
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_create_transfer(text, text, text, integer, text, text, jsonb) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_get_transfer(text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_finish_transfer(text, text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_my_transfers(text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_adopt_hosts(text, text, text[]) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';

-- ============================================================================
-- SECTION 2: DEVICE PAIRING + SENDER DISPLAY (2026-10-02, round 2)
-- ============================================================================
-- the sender picks the target device from its account family, and only that one
-- can redeem the transfer (code AND auto prompt). before downloading, the
-- receiver sees a P.R.A.E.T.O.R. confirmation screen: nickname, device name,
-- or the sender's device uuid as a fallback.

ALTER TABLE public.server_transfers
  ADD COLUMN IF NOT EXISTS target_app_uuid text,
  ADD COLUMN IF NOT EXISTS from_nickname text,
  ADD COLUMN IF NOT EXISTS from_device_model text;

-- devices of your own account family (for the target picker in the sender).
-- unlike rpc_get_linked_accounts WITHOUT the mc_username filter: every device
-- counts here, not just linked minecraft accounts.
CREATE OR REPLACE FUNCTION public.rpc_list_family_devices(p_app_uuid text, p_device_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_auth uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT auth_id INTO v_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  IF v_auth IS NULL THEN
    RETURN '[]'::jsonb;
  END IF;
  RETURN COALESCE((
    SELECT jsonb_agg(jsonb_build_object(
      'app_uuid', u.app_uuid,
      'nickname', COALESCE(NULLIF(u.nickname, ''), ''),
      'device_model', COALESCE(NULLIF(u.device_model, ''), ''),
      'is_main', u.is_main,
      'last_active', u.last_active
    ) ORDER BY u.is_main DESC NULLS LAST, u.last_active DESC NULLS LAST)
    FROM public.koda_users u
    WHERE u.auth_id = v_auth
      AND u.app_uuid <> p_app_uuid
  ), '[]'::jsonb);
END;
$fn$;

-- an open transfer aimed at exactly MYSELF (the auto prompt on the receiver).
CREATE OR REPLACE FUNCTION public.rpc_get_pending_transfer_for(p_app_uuid text, p_device_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_row public.server_transfers%ROWTYPE;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  UPDATE public.server_transfers SET state = 'expired'
  WHERE target_app_uuid = p_app_uuid AND state = 'open' AND now() > expires_at;
  SELECT * INTO v_row FROM public.server_transfers
  WHERE target_app_uuid = p_app_uuid AND state = 'open'
  ORDER BY created_at DESC LIMIT 1;
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;
  RETURN to_jsonb(v_row);
END;
$fn$;

-- rpc_create_transfer: target device + sender display (the server reads nickname
-- and model out of koda_users itself, the client cannot fake them).
DROP FUNCTION IF EXISTS public.rpc_create_transfer(text, text, text, integer, text, text, jsonb);
CREATE OR REPLACE FUNCTION public.rpc_create_transfer(
  p_app_uuid text, p_device_token text,
  p_lan_ip text, p_lan_port integer,
  p_http_token text, p_aes_key text, p_servers jsonb,
  p_target_app_uuid text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $fn$
DECLARE v_auth uuid; v_code text; v_host text; v_nick text; v_model text;
        v_row public.server_transfers%ROWTYPE;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  IF p_lan_ip IS NULL OR length(p_lan_ip) < 7 OR p_lan_port IS NULL
     OR p_lan_port < 1024 OR p_lan_port > 65535
     OR COALESCE(p_http_token, '') = '' OR COALESCE(p_aes_key, '') = ''
     OR p_servers IS NULL OR jsonb_array_length(p_servers) = 0 THEN
    RAISE EXCEPTION 'invalid payload';
  END IF;
  IF p_target_app_uuid IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.koda_users
    WHERE app_uuid = p_target_app_uuid AND auth_id IS NOT NULL
      AND auth_id = (SELECT auth_id FROM public.koda_users WHERE app_uuid = p_app_uuid)
  ) THEN
    RAISE EXCEPTION 'target not in family';
  END IF;

  FOR v_host IN SELECT jsonb_array_elements_text(p_servers) AS host LOOP
    IF NOT EXISTS (
      SELECT 1 FROM public.koda_servers
      WHERE owner_app_uuid = p_app_uuid AND host = v_host
        AND host NOT LIKE 'deleted_%'
        AND COALESCE(server_version, '') NOT IN ('DELETED')
    ) THEN
      RAISE EXCEPTION 'server not owned: %', v_host;
    END IF;
  END LOOP;

  SELECT auth_id, nickname, device_model INTO v_auth, v_nick, v_model
  FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;

  UPDATE public.server_transfers SET state = 'expired'
  WHERE from_app_uuid = p_app_uuid AND state = 'open';

  DELETE FROM public.server_transfers WHERE created_at < now() - interval '24 hours';

  LOOP
    v_code := upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 8));
    EXIT WHEN NOT EXISTS (SELECT 1 FROM public.server_transfers WHERE code = v_code);
  END LOOP;

  INSERT INTO public.server_transfers (code, from_app_uuid, auth_id, lan_ip, lan_port,
    http_token, aes_key, servers, expires_at, target_app_uuid, from_nickname, from_device_model)
  VALUES (v_code, p_app_uuid, v_auth, p_lan_ip, p_lan_port,
    p_http_token, p_aes_key, p_servers, now() + interval '15 minutes',
    p_target_app_uuid, COALESCE(v_nick, ''), COALESCE(v_model, ''))
  RETURNING * INTO v_row;

  RETURN to_jsonb(v_row) - 'http_token' - 'aes_key' || jsonb_build_object('code', v_code);
END;
$fn$;

-- rpc_get_transfer: once a target is set, only that device may redeem it.
DROP FUNCTION IF EXISTS public.rpc_get_transfer(text);
CREATE OR REPLACE FUNCTION public.rpc_get_transfer(p_code text, p_app_uuid text DEFAULT '', p_device_token text DEFAULT '')
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_row public.server_transfers%ROWTYPE;
BEGIN
  IF p_code IS NULL OR length(p_code) < 8 THEN
    RETURN jsonb_build_object('state', 'invalid');
  END IF;
  SELECT * INTO v_row FROM public.server_transfers WHERE code = upper(p_code) LIMIT 1;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('state', 'invalid');
  END IF;
  IF v_row.target_app_uuid IS NOT NULL AND v_row.target_app_uuid <> p_app_uuid THEN
    RETURN jsonb_build_object('state', 'forbidden');
  END IF;
  IF v_row.state = 'open' AND now() > v_row.expires_at THEN
    UPDATE public.server_transfers SET state = 'expired' WHERE id = v_row.id;
    v_row.state := 'expired';
  END IF;
  IF v_row.state <> 'open' THEN
    RETURN jsonb_build_object('state', v_row.state);
  END IF;
  RETURN to_jsonb(v_row);
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_list_family_devices(text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_get_pending_transfer_for(text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_create_transfer(text, text, text, integer, text, text, jsonb, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_get_transfer(text, text, text) TO anon, authenticated;
NOTIFY pgrst, 'reload schema';
