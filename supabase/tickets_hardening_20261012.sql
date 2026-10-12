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
-- ticket hardening 2026-10-12: attachments get their own column, sizes and rates are
-- capped, the 5 open ticket rule lives here now and the user can ask for a close.
--
-- NOTE on the app side (already shipped): photos ride in `p_attachment_url`, and the
-- close request sets the status through rpc_request_ticket_close AND posts the
-- [KODA-CLOSE-REQUEST] message so both admin tools can show it.

-- 1) ticket creation: length limits, a whitelist for the type and at most 5 open
--    tickets per device. personal (mental support) tickets are exempt on purpose,
--    a crisis chat must never be blocked by a queue limit.
CREATE OR REPLACE FUNCTION public.rpc_create_ticket(p_reporter_uuid text, p_device_token text, p_ticket_type text, p_reference_id text, p_title text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE new_ticket_id uuid; v_open integer;
BEGIN
  IF NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token)
     AND NOT public.fn_is_praetor_admin(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  IF p_ticket_type IS NULL OR p_ticket_type NOT IN
       ('BUG', 'BUG_REPORT', 'APP_BUG', 'SERVER_REPORT', 'SUPPORT', 'PERSONAL') THEN
    RAISE EXCEPTION 'Unknown ticket type';
  END IF;
  IF p_title IS NULL OR length(p_title) < 2 OR length(p_title) > 160 THEN
    RAISE EXCEPTION 'Title must be between 2 and 160 characters';
  END IF;
  IF p_reference_id IS NOT NULL AND length(p_reference_id) > 120 THEN
    RAISE EXCEPTION 'Reference is too long';
  END IF;

  IF p_ticket_type <> 'PERSONAL' THEN
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

-- 2) messages: the attachment gets its own column, plus length caps and a per device
--    rate limit. the old four argument version has to GO, otherwise postgrest keeps
--    matching the unhardened function for calls without an attachment.
DROP FUNCTION IF EXISTS public.rpc_create_ticket_message(uuid, text, text, text);
CREATE OR REPLACE FUNCTION public.rpc_create_ticket_message(
    p_ticket_id uuid,
    p_sender_uuid text,
    p_device_token text,
    p_message text,
    p_attachment_url text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_is_admin boolean := false; v_recent integer; v_status text;
BEGIN
  v_is_admin := public.fn_is_praetor_admin(p_sender_uuid, p_device_token);

  IF NOT v_is_admin AND NOT public.fn_verify_device_token(p_sender_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  -- Nicht-Admins duerfen nur in eigene Tickets schreiben
  IF NOT v_is_admin AND NOT EXISTS (
    SELECT 1 FROM public.support_tickets t
    WHERE t.id = p_ticket_id AND t.reporter_uuid = p_sender_uuid
  ) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  SELECT status INTO v_status FROM public.support_tickets WHERE id = p_ticket_id;
  IF v_status IN ('RESOLVED', 'CLOSED') AND NOT v_is_admin THEN
    RAISE EXCEPTION 'Ticket already closed';
  END IF;

  IF p_message IS NOT NULL AND length(p_message) > 4000 THEN
    RAISE EXCEPTION 'Message is too long';
  END IF;
  IF p_attachment_url IS NOT NULL AND p_attachment_url <> '' THEN
    IF length(p_attachment_url) > 600 THEN
      RAISE EXCEPTION 'Attachment link is too long';
    END IF;
    -- only our own upload bucket, no random links
    IF p_attachment_url NOT LIKE '%/support_attachments/%' THEN
      RAISE EXCEPTION 'Attachment must be a support upload';
    END IF;
  END IF;

  -- Spam-Bremse: hoechstens 20 Nachrichten pro Minute und Geraet
  SELECT count(*) INTO v_recent FROM public.support_ticket_messages
  WHERE sender_uuid = p_sender_uuid AND created_at > now() - interval '1 minute';
  IF v_recent >= 20 THEN
    RAISE EXCEPTION 'Slow down';
  END IF;

  INSERT INTO public.support_ticket_messages (ticket_id, sender_uuid, message, attachment_url, is_admin)
  VALUES (p_ticket_id, p_sender_uuid, COALESCE(p_message, ''),
          NULLIF(TRIM(p_attachment_url), ''), v_is_admin);
END;
$fn$;

-- 3) the user can ask for a close. the team still does the closing, but the status
--    shows up everywhere (app list, admin app, admin panel).
CREATE OR REPLACE FUNCTION public.rpc_request_ticket_close(p_ticket_id uuid, p_reporter_uuid text, p_device_token text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  UPDATE public.support_tickets SET status = 'CLOSE_REQUESTED'
  WHERE id = p_ticket_id
    AND reporter_uuid = p_reporter_uuid
    AND status NOT IN ('RESOLVED', 'CLOSED', 'CLOSE_REQUESTED');
  RETURN FOUND;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_create_ticket(text, text, text, text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_create_ticket_message(uuid, text, text, text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_request_ticket_close(uuid, text, text) TO anon, authenticated;

NOTIFY pgrst, 'reload schema';
