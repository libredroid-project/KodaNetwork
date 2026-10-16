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
-- SERVER INVITES (2026-10-16): expiring guest links. the owner creates a token
-- on the device, the friend opens https://host.kodanetwork.eu/invite/<token>
-- and sees name, live status and the join address. RLS on with NO policies:
-- only the definer RPCs (device+owner checked) and the service role touch it.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.server_invites (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  host text NOT NULL,
  token text NOT NULL UNIQUE,
  created_by_app_uuid text,
  note text,
  max_uses integer,
  uses integer NOT NULL DEFAULT 0,
  expires_at timestamptz,
  revoked boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.server_invites ENABLE ROW LEVEL SECURITY;

CREATE INDEX IF NOT EXISTS server_invites_host_idx ON public.server_invites (host);

-- owner creates an invite; p_hours = 0 means no expiry, p_max_uses NULL/0 = unlimited
CREATE OR REPLACE FUNCTION public.rpc_create_server_invite(
  p_app_uuid text,
  p_device_token text,
  p_host text,
  p_hours integer DEFAULT 168,
  p_max_uses integer DEFAULT NULL,
  p_note text DEFAULT NULL
)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $fn$
DECLARE v_owner text; v_token text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE host = p_host;
  IF v_owner IS NULL THEN RAISE EXCEPTION 'Server not found'; END IF;
  IF v_owner != p_app_uuid THEN RAISE EXCEPTION 'Not authorized'; END IF;

  v_token := encode(extensions.gen_random_bytes(9), 'hex');
  INSERT INTO public.server_invites (host, token, created_by_app_uuid, note, max_uses, expires_at)
  VALUES (p_host, v_token, p_app_uuid, NULLIF(p_note, ''), NULLIF(p_max_uses, 0),
          CASE WHEN p_hours > 0 THEN now() + make_interval(hours => p_hours) ELSE NULL END);
  RETURN v_token;
END;
$fn$;

-- the active invites of one host, for the owner's device
CREATE OR REPLACE FUNCTION public.rpc_list_server_invites(p_app_uuid text, p_device_token text, p_host text)
RETURNS TABLE(token text, note text, uses integer, max_uses integer, expires_at timestamptz, created_at timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $fn$
DECLARE v_owner text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE host = p_host;
  IF v_owner IS NULL OR v_owner != p_app_uuid THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  RETURN QUERY
    SELECT i.token, i.note, i.uses, i.max_uses, i.expires_at, i.created_at
    FROM public.server_invites i
    WHERE i.host = p_host
      AND NOT i.revoked
      AND (i.expires_at IS NULL OR i.expires_at > now())
      AND (i.max_uses IS NULL OR i.uses < i.max_uses)
    ORDER BY i.created_at DESC
    LIMIT 20;
END;
$fn$;

CREATE OR REPLACE FUNCTION public.rpc_revoke_server_invite(p_app_uuid text, p_device_token text, p_host text, p_token text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $fn$
DECLARE v_owner text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE host = p_host;
  IF v_owner IS NULL OR v_owner != p_app_uuid THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  UPDATE public.server_invites SET revoked = true WHERE host = p_host AND token = lower(p_token);
END;
$fn$;

-- single atomic use: validity guard and counter in one statement, so two people
-- clicking a one-time link at the same time cannot both get through.
CREATE OR REPLACE FUNCTION public.fn_use_server_invite(p_token text)
RETURNS TABLE(host text, note text, uses integer, max_uses integer, expires_at timestamptz)
LANGUAGE sql
SECURITY DEFINER
SET search_path TO 'public'
AS $fn$
  UPDATE public.server_invites i
  SET uses = i.uses + 1
  WHERE i.token = lower(trim(p_token))
    AND NOT i.revoked
    AND (i.expires_at IS NULL OR i.expires_at > now())
    AND (i.max_uses IS NULL OR i.uses < i.max_uses)
  RETURNING i.host, i.note, i.uses, i.max_uses, i.expires_at;
$fn$;

-- service role only: the api edge function calls it, nobody else may burn uses
REVOKE ALL ON FUNCTION public.fn_use_server_invite(text) FROM PUBLIC, anon, authenticated;

-- ============================================================================
-- CONTROL GUESTS (2026-10-16): an invite can also grant remote control. guests
-- then start/stop/restart the server from the invite page; the api function
-- writes CMD:START/STOP/RESTART into the row and the owner's device executes it
-- through the existing command channel. fn_check_server_invite validates without
-- burning a use, the page-open path still counts.
-- ============================================================================

ALTER TABLE public.server_invites ADD COLUMN IF NOT EXISTS can_control boolean NOT NULL DEFAULT false;

DROP FUNCTION IF EXISTS public.rpc_create_server_invite(text, text, text, integer, integer, text);

CREATE OR REPLACE FUNCTION public.rpc_create_server_invite(
  p_app_uuid text,
  p_device_token text,
  p_host text,
  p_hours integer DEFAULT 168,
  p_max_uses integer DEFAULT NULL,
  p_note text DEFAULT NULL,
  p_can_control boolean DEFAULT false
)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $fn$
DECLARE v_owner text; v_token text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE host = p_host;
  IF v_owner IS NULL THEN RAISE EXCEPTION 'Server not found'; END IF;
  IF v_owner != p_app_uuid THEN RAISE EXCEPTION 'Not authorized'; END IF;

  v_token := encode(extensions.gen_random_bytes(9), 'hex');
  INSERT INTO public.server_invites (host, token, created_by_app_uuid, note, max_uses, expires_at, can_control)
  VALUES (p_host, v_token, p_app_uuid, NULLIF(p_note, ''), NULLIF(p_max_uses, 0),
          CASE WHEN p_hours > 0 THEN now() + make_interval(hours => p_hours) ELSE NULL END,
          COALESCE(p_can_control, false));
  RETURN v_token;
END;
$fn$;

DROP FUNCTION IF EXISTS public.rpc_list_server_invites(text, text, text);

CREATE OR REPLACE FUNCTION public.rpc_list_server_invites(p_app_uuid text, p_device_token text, p_host text)
RETURNS TABLE(token text, note text, uses integer, max_uses integer, expires_at timestamptz, can_control boolean, created_at timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $fn$
DECLARE v_owner text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT owner_app_uuid INTO v_owner FROM public.koda_servers WHERE host = p_host;
  IF v_owner IS NULL OR v_owner != p_app_uuid THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  RETURN QUERY
    SELECT i.token, i.note, i.uses, i.max_uses, i.expires_at, i.can_control, i.created_at
    FROM public.server_invites i
    WHERE i.host = p_host
      AND NOT i.revoked
      AND (i.expires_at IS NULL OR i.expires_at > now())
      AND (i.max_uses IS NULL OR i.uses < i.max_uses)
    ORDER BY i.created_at DESC
    LIMIT 20;
END;
$fn$;

DROP FUNCTION IF EXISTS public.fn_use_server_invite(text);

CREATE OR REPLACE FUNCTION public.fn_use_server_invite(p_token text)
RETURNS TABLE(host text, note text, uses integer, max_uses integer, expires_at timestamptz, can_control boolean)
LANGUAGE sql
SECURITY DEFINER
SET search_path TO 'public'
AS $fn$
  UPDATE public.server_invites i
  SET uses = i.uses + 1
  WHERE i.token = lower(trim(p_token))
    AND NOT i.revoked
    AND (i.expires_at IS NULL OR i.expires_at > now())
    AND (i.max_uses IS NULL OR i.uses < i.max_uses)
  RETURNING i.host, i.note, i.uses, i.max_uses, i.expires_at, i.can_control;
$fn$;

REVOKE ALL ON FUNCTION public.fn_use_server_invite(text) FROM PUBLIC, anon, authenticated;

-- validates a token for a control action WITHOUT counting a use
CREATE OR REPLACE FUNCTION public.fn_check_server_invite(p_token text)
RETURNS TABLE(host text, can_control boolean)
LANGUAGE sql
SECURITY DEFINER
SET search_path TO 'public'
AS $fn$
  SELECT i.host, i.can_control
  FROM public.server_invites i
  WHERE i.token = lower(trim(p_token))
    AND NOT i.revoked
    AND (i.expires_at IS NULL OR i.expires_at > now())
    AND (i.max_uses IS NULL OR i.uses < i.max_uses)
  LIMIT 1;
$fn$;

REVOKE ALL ON FUNCTION public.fn_check_server_invite(text) FROM PUBLIC, anon, authenticated;

NOTIFY pgrst, 'reload schema';
