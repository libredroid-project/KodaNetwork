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

-- security round 2026-10-10, before the open testing release.
--
-- the hole: rpc_lobby_get_users and rpc_lobby_get_servers hand app_uuids to
-- everyone, and rpc_get_is_banned answered with the matching device_token for any
-- app_uuid - so the public anon key was enough to take over any account (read the
-- servers, the dashboard token, patch hosts, write into the support chats).
--
-- from now on an existing row only gets its token back when the caller either
-- already has the token or proves the hardware it belongs to: the app_uuid is only
-- "KODA-" plus the first 16 chars of the sha256 hwid, so the FULL hwid is the part
-- nobody can read out of a public list. a fresh app_uuid still gets a fresh token
-- (that is a new identity, nobody owns it yet), which keeps the very first start
-- and every reinstall-without-backup path working.

-- 1) the full hwid of the owning device, the recovery factor
ALTER TABLE public.koda_users ADD COLUMN IF NOT EXISTS hwid_full text;

-- 2) the token gate. the old one-argument version has to GO, otherwise the leak
--    stays reachable through the old signature.
DROP FUNCTION IF EXISTS public.rpc_get_is_banned(text);
CREATE OR REPLACE FUNCTION public.rpc_get_is_banned(
    p_app_uuid text,
    p_hwid text DEFAULT NULL,
    p_device_token text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
  v_banned boolean;
  v_token text;
  v_hwid text;
BEGIN
  IF p_app_uuid IS NULL OR p_app_uuid !~ '^KODA-[A-Za-z0-9_-]{6,40}$' THEN
    RETURN jsonb_build_object('banned', false);
  END IF;

  SELECT is_banned, device_token, hwid_full INTO v_banned, v_token, v_hwid
  FROM public.koda_users WHERE app_uuid = p_app_uuid
  ORDER BY created_at DESC LIMIT 1;

  IF NOT FOUND THEN
    INSERT INTO public.koda_users (app_uuid, hwid_full)
    VALUES (p_app_uuid, NULLIF(p_hwid, ''))
    ON CONFLICT (app_uuid) DO NOTHING;
    IF FOUND THEN
      -- we created it, so the token belongs to this caller
      SELECT device_token, COALESCE(is_banned, false) INTO v_token, v_banned
      FROM public.koda_users WHERE app_uuid = p_app_uuid;
      RETURN jsonb_build_object('banned', v_banned, 'device_token', v_token);
    END IF;
    -- lost the create race, treat it as an existing row
    SELECT is_banned, device_token, hwid_full INTO v_banned, v_token, v_hwid
    FROM public.koda_users WHERE app_uuid = p_app_uuid
    ORDER BY created_at DESC LIMIT 1;
  END IF;

  -- the device that already holds the token: give it back and remember its hwid
  -- so the next reinstall can be recovered
  IF p_device_token IS NOT NULL AND p_device_token <> ''
     AND v_token IS NOT NULL AND p_device_token = v_token THEN
    IF p_hwid IS NOT NULL AND p_hwid <> '' AND COALESCE(v_hwid, '') <> p_hwid THEN
      UPDATE public.koda_users SET hwid_full = p_hwid WHERE app_uuid = p_app_uuid;
    END IF;
    RETURN jsonb_build_object('banned', COALESCE(v_banned, false), 'device_token', v_token);
  END IF;

  -- reinstall case: same hardware, no token in the fresh install, the full hwid
  -- (64 hex chars, only this phone can compute it) proves the ownership
  IF p_hwid IS NOT NULL AND p_hwid <> '' AND COALESCE(v_hwid, '') = p_hwid THEN
    RETURN jsonb_build_object('banned', COALESCE(v_banned, false), 'device_token', v_token);
  END IF;

  -- everyone else gets the ban status and nothing to write with
  RETURN jsonb_build_object('banned', COALESCE(v_banned, false));
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_get_is_banned(text, text, text) TO anon, authenticated;

-- 3) direct inserts into koda_users are legacy: every creation goes through
--    rpc_get_is_banned / rpc_regenerate_link_code. the old policy let anyone with
--    the anon key insert rows with self-chosen is_main/permissions (the lobby
--    grounds every panel permission on is_main).
DROP POLICY IF EXISTS "Allow insert for public" ON public.koda_users;
CREATE POLICY "Authenticated can insert own profile" ON public.koda_users
  FOR INSERT TO authenticated WITH CHECK (auth.uid() IS NOT NULL);

-- 4) same story for support_reports, reports flow through the RPCs
DROP POLICY IF EXISTS "Allow anonymous insert reports" ON public.support_reports;
CREATE POLICY "Authenticated can insert reports" ON public.support_reports
  FOR INSERT TO authenticated WITH CHECK (auth.uid() IS NOT NULL);

-- 5) transfer secrets (lan address, http token, aes key) only go to a device that
--    proves its token. the app sends app_uuid + device_token already.
CREATE OR REPLACE FUNCTION public.rpc_get_transfer(
    p_code text,
    p_app_uuid text DEFAULT '',
    p_device_token text DEFAULT '')
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_row public.server_transfers%ROWTYPE;
BEGIN
  IF p_code IS NULL OR length(p_code) < 8 THEN
    RETURN jsonb_build_object('state', 'invalid');
  END IF;
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RETURN jsonb_build_object('state', 'forbidden');
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

GRANT EXECUTE ON FUNCTION public.rpc_get_transfer(text, text, text) TO anon, authenticated;

-- 6) the two ban-list writers need a verified device token, otherwise anyone with
--    the anon key could overwrite hwid reasons and flood the lists
CREATE OR REPLACE FUNCTION public.report_tamper(
    p_hwid text,
    p_reason text,
    p_user_uuid uuid DEFAULT NULL::uuid,
    p_app_uuid text DEFAULT '',
    p_device_token text DEFAULT '')
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RETURN;
  END IF;
  INSERT INTO public.banned_hwids (hwid, reason, is_scary)
  VALUES (p_hwid, p_reason,
    CASE WHEN p_reason LIKE 'PERMANENT_BAN_%' THEN true ELSE false END)
  ON CONFLICT (hwid) DO UPDATE SET
    reason = EXCLUDED.reason;
END;
$fn$;

CREATE OR REPLACE FUNCTION public.report_high_risk(
    p_hwid text,
    p_reason text,
    p_user_uuid uuid DEFAULT NULL::uuid,
    p_app_uuid text DEFAULT '',
    p_device_token text DEFAULT '')
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RETURN;
  END IF;
  INSERT INTO public.high_risk_hwids (hwid, reason)
  VALUES (p_hwid, p_reason)
  ON CONFLICT (hwid) DO UPDATE SET
    reason = EXCLUDED.reason;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.report_tamper(text, text, uuid, text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.report_high_risk(text, text, uuid, text, text) TO anon, authenticated;

-- 7) support attachments: real user screenshots are in that bucket, so nobody may
--    LIST them any more. the bucket stays public, a single object is still
--    readable by its URL (the admin app and the chat view rely on that), and the
--    app can still upload. follow-up for later: move uploads behind a device-token
--    checked edge function.
DROP POLICY IF EXISTS "Give users access to own folder" ON storage.objects;
DROP POLICY IF EXISTS "Public access to support attachments" ON storage.objects;
CREATE POLICY "Anyone can upload support attachments" ON storage.objects
  FOR INSERT TO anon, authenticated WITH CHECK (bucket_id = 'support_attachments');

NOTIFY pgrst, 'reload schema';
