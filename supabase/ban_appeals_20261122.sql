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
--
-- ban appeals, 2026-11-22
--
-- A banned device may open one UNBAN ticket. When staff reject it the device has to
-- wait 7 days before opening the next one. Staff can lock ticket creation completely
-- per user (`tickets_disabled`), which is the server side of the "scary ban": the
-- ban screen stays normal but there is no appeal.
--
-- Tickets are deliberately NOT deleted when an account is deleted. The purge in
-- data_requests_notifications_20261108.sql anonymizes them (reporter_uuid='deleted')
-- and keeps the row, so a later appeal/report stays auditable. PERSONAL (crisis
-- support) is exempt from the ticket lock on purpose: a mental health chat must
-- never be blocked by a moderation flag.

-- 1) the per-user controls and the rejection timestamp -----------------------
ALTER TABLE public.koda_users ADD COLUMN IF NOT EXISTS tickets_disabled boolean NOT NULL DEFAULT false;
ALTER TABLE public.koda_users ADD COLUMN IF NOT EXISTS ban_reason text;
ALTER TABLE public.koda_users ADD COLUMN IF NOT EXISTS banned_at timestamp with time zone;
ALTER TABLE public.support_tickets ADD COLUMN IF NOT EXISTS decided_at timestamp with time zone;

-- bans handed out before this column existed start their 14 day clock now, so
-- existing bans do not hibernate a server the moment the migration runs
UPDATE public.koda_users SET banned_at = now()
 WHERE is_banned IS TRUE AND banned_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_support_tickets_unban
    ON public.support_tickets (reporter_uuid, ticket_type, status, decided_at DESC);

-- 2) ticket creation: UNBAN type, ticket lock, one open appeal, 7 day cooldown
CREATE OR REPLACE FUNCTION public.rpc_create_ticket(p_reporter_uuid text, p_device_token text, p_ticket_type text, p_reference_id text, p_title text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
  new_ticket_id uuid;
  v_open integer;
  v_appeal_open integer;
  v_banned boolean := false;
  v_tickets_disabled boolean := false;
  v_hwid_banned boolean := false;
  v_scary boolean := false;
  v_last_reject timestamp with time zone;
BEGIN
  IF NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token)
     AND NOT public.fn_is_praetor_admin(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  IF p_ticket_type IS NULL OR p_ticket_type NOT IN
       ('BUG', 'BUG_REPORT', 'APP_BUG', 'SERVER_REPORT', 'SUPPORT', 'PERSONAL', 'QUESTION', 'IDEA', 'UNBAN') THEN
    RAISE EXCEPTION 'Unknown ticket type';
  END IF;
  IF p_title IS NULL OR length(p_title) < 2 OR length(p_title) > 160 THEN
    RAISE EXCEPTION 'Title must be between 2 and 160 characters';
  END IF;
  IF p_reference_id IS NOT NULL AND length(p_reference_id) > 120 THEN
    RAISE EXCEPTION 'Reference is too long';
  END IF;

  SELECT COALESCE(is_banned, false), COALESCE(tickets_disabled, false)
    INTO v_banned, v_tickets_disabled
  FROM public.koda_users
  WHERE app_uuid = p_reporter_uuid
  ORDER BY created_at DESC
  LIMIT 1;

  -- older bans live only in banned_hwids, the koda_users flag was never set. a
  -- normal HWID ban can be appealed too; a scary one never can.
  SELECT count(*) > 0, COALESCE(bool_or(b.is_scary), false)
    INTO v_hwid_banned, v_scary
  FROM public.banned_hwids b
  JOIN public.koda_users u ON u.app_uuid = p_reporter_uuid
  WHERE b.hwid = u.hwid_full
     OR b.hwid = p_reporter_uuid
     OR ('KODA-' || upper(substr(b.hwid, 1, 16))) = p_reporter_uuid;
  v_banned := COALESCE(v_banned, false) OR COALESCE(v_hwid_banned, false);

  -- the ticket lock. personal support stays reachable even for a locked account.
  IF COALESCE(v_tickets_disabled, false) AND p_ticket_type <> 'PERSONAL' THEN
    RAISE EXCEPTION 'tickets_disabled';
  END IF;

  -- an unban appeal only exists for an actually banned device, one at a time,
  -- and only after the 7 day cooldown that follows a rejection.
  IF p_ticket_type = 'UNBAN' THEN
    IF NOT COALESCE(v_banned, false) THEN
      RAISE EXCEPTION 'not_banned';
    END IF;
    IF COALESCE(v_scary, false) THEN
      RAISE EXCEPTION 'appeals_disabled';
    END IF;

    SELECT count(*) INTO v_appeal_open
    FROM public.support_tickets
    WHERE reporter_uuid = p_reporter_uuid
      AND ticket_type = 'UNBAN'
      AND status NOT IN ('RESOLVED', 'CLOSED', 'REJECTED');
    IF v_appeal_open > 0 THEN
      RAISE EXCEPTION 'appeal_already_open';
    END IF;

    SELECT max(COALESCE(decided_at, created_at)) INTO v_last_reject
    FROM public.support_tickets
    WHERE reporter_uuid = p_reporter_uuid
      AND ticket_type = 'UNBAN'
      AND status = 'REJECTED';
    IF v_last_reject IS NOT NULL AND v_last_reject > now() - interval '7 days' THEN
      RAISE EXCEPTION 'appeal_cooldown';
    END IF;
  END IF;

  -- appeals do not compete with the 5 open ticket limit, they have their own rule
  IF p_ticket_type NOT IN ('PERSONAL', 'UNBAN') THEN
    SELECT count(*) INTO v_open FROM public.support_tickets
    WHERE reporter_uuid = p_reporter_uuid
      AND ticket_type <> 'PERSONAL'
      AND status NOT IN ('RESOLVED', 'CLOSED');
    IF v_open >= 5 THEN
      RAISE EXCEPTION 'Too many open tickets';
    END IF;
  END IF;

  INSERT INTO public.support_tickets (reporter_uuid, ticket_type, reference_id, title, status)
  VALUES (p_reporter_uuid, p_ticket_type, p_reference_id, p_title, 'OPEN')
  RETURNING id INTO new_ticket_id;

  IF p_ticket_type IN ('BUG', 'BUG_REPORT', 'APP_BUG') THEN
    INSERT INTO public.support_bugs (id, description, reporter_uuid, status)
    VALUES (new_ticket_id, p_title, p_reporter_uuid, 'OPEN');
  ELSIF p_ticket_type = 'SERVER_REPORT' THEN
    INSERT INTO public.support_reports (id, server_host, reason, reporter_uuid, status)
    VALUES (new_ticket_id, p_reference_id, p_title, p_reporter_uuid, 'OPEN');
  END IF;

  RETURN new_ticket_id;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_create_ticket(text, text, text, text, text) TO anon, authenticated;

-- 3) the ban screen asks this for reason, lock state and appeal state ---------
CREATE OR REPLACE FUNCTION public.rpc_get_ban_appeal(p_app_uuid text, p_device_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
  v_banned boolean := false;
  v_reason text;
  v_disabled boolean := false;
  v_hwid_banned boolean := false;
  v_hwid_reason text;
  v_scary boolean := false;
  v_open boolean := false;
  v_last_status text;
  v_available timestamp with time zone;
  v_decided timestamp with time zone;
  v_banned_at timestamp with time zone;
  v_hwid_banned_at timestamp with time zone;
  v_ticket_id uuid;
  v_ticket_title text;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token)
     AND NOT public.fn_is_praetor_admin(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT COALESCE(is_banned, false), ban_reason, COALESCE(tickets_disabled, false), banned_at
    INTO v_banned, v_reason, v_disabled, v_banned_at
  FROM public.koda_users
  WHERE app_uuid = p_app_uuid
  ORDER BY created_at DESC
  LIMIT 1;

  -- HWID-only bans and the scary flag live in banned_hwids
  SELECT count(*) > 0, COALESCE(bool_or(b.is_scary), false),
         max(b.reason) FILTER (WHERE b.reason IS NOT NULL),
         max(b.created_at)
    INTO v_hwid_banned, v_scary, v_hwid_reason, v_hwid_banned_at
  FROM public.banned_hwids b
  JOIN public.koda_users u ON u.app_uuid = p_app_uuid
  WHERE b.hwid = u.hwid_full
     OR b.hwid = p_app_uuid
     OR ('KODA-' || upper(substr(b.hwid, 1, 16))) = p_app_uuid;
  v_banned := COALESCE(v_banned, false) OR COALESCE(v_hwid_banned, false);
  v_reason := COALESCE(NULLIF(v_reason, ''), v_hwid_reason);
  v_banned_at := COALESCE(v_banned_at, v_hwid_banned_at);

  SELECT count(*) > 0 INTO v_open
  FROM public.support_tickets
  WHERE reporter_uuid = p_app_uuid
    AND ticket_type = 'UNBAN'
    AND status NOT IN ('RESOLVED', 'CLOSED', 'REJECTED');

  SELECT id, status, COALESCE(decided_at, created_at), title
    INTO v_ticket_id, v_last_status, v_decided, v_ticket_title
  FROM public.support_tickets
  WHERE reporter_uuid = p_app_uuid
    AND ticket_type = 'UNBAN'
  ORDER BY created_at DESC
  LIMIT 1;

  IF v_last_status = 'REJECTED' THEN
    v_available := v_decided + interval '7 days';
  END IF;

  RETURN jsonb_build_object(
    'banned', COALESCE(v_banned, false),
    'ban_reason', v_reason,
    'tickets_disabled', COALESCE(v_disabled, false),
    'appeals_disabled', COALESCE(v_scary, false),
    'appeal_open', COALESCE(v_open, false),
    'appeal_status', v_last_status,
    'appeal_ticket_id', v_ticket_id,
    'appeal_title', v_ticket_title,
    'appeal_available_at', v_available,
    'cooldown_days', 7,
    'banned_at', v_banned_at,
    'ban_days', CASE WHEN v_banned_at IS NULL THEN 0
                     ELSE GREATEST(0, floor(extract(epoch FROM (now() - v_banned_at)) / 86400))::int END,
    -- after 14 days the local servers are hibernated. the app zips them away and
    -- the daily job below also sends CMD:HIBERNATE to the phone.
    'hibernate_servers', COALESCE(v_banned, false) AND v_banned_at IS NOT NULL
                         AND v_banned_at < now() - interval '14 days'
  );
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_get_ban_appeal(text, text) TO anon, authenticated;

-- 4) an appeal has no close request. otherwise a rejected appeal could be pushed
--    to CLOSE_REQUESTED and the 7 day cooldown would no longer match status='REJECTED'.
CREATE OR REPLACE FUNCTION public.rpc_request_ticket_close(p_ticket_id uuid, p_reporter_uuid text, p_device_token text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.support_tickets
    WHERE id = p_ticket_id
      AND reporter_uuid = p_reporter_uuid
      AND ticket_type = 'UNBAN'
  ) THEN
    RAISE EXCEPTION 'appeals_cannot_be_closed';
  END IF;
  UPDATE public.support_tickets SET status = 'CLOSE_REQUESTED'
  WHERE id = p_ticket_id
    AND reporter_uuid = p_reporter_uuid
    AND status NOT IN ('RESOLVED', 'CLOSED', 'CLOSE_REQUESTED');
  RETURN FOUND;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_request_ticket_close(uuid, text, text) TO anon, authenticated;

-- 5) after 14 days a ban hibernates the servers of that device -----------------
-- the phone picks CMD:HIBERNATE up on its next status poll and runs the normal
-- stop + zip flow. hibernated servers can only be downloaded, never started, and
-- wake up again once the ban is lifted.
DO $do$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        IF EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'koda-ban-hibernate') THEN
            PERFORM cron.unschedule('koda-ban-hibernate');
        END IF;
        PERFORM cron.schedule('koda-ban-hibernate', '25 3 * * *', $job$
            UPDATE public.koda_servers s
               SET server_version = 'CMD:HIBERNATE'
              FROM public.koda_users u
             WHERE s.owner_app_uuid = u.app_uuid
               AND u.is_banned IS TRUE
               AND u.banned_at IS NOT NULL
               AND u.banned_at < now() - interval '14 days'
               AND s.host NOT LIKE 'deleted_%'
               AND s.server_version NOT IN ('HIBERNATED', 'DELETED')
               AND s.server_version NOT LIKE 'CMD:%';
        $job$);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- pg_cron missing, the app still hibernates locally from the ban screen
    NULL;
END $do$;

NOTIFY pgrst, 'reload schema';
