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
-- end-to-end encryption for the personal (mental support) chat.
-- the server only ever holds ciphertext, the keys stay on the devices.

ALTER TABLE public.koda_users ADD COLUMN IF NOT EXISTS public_key text;

CREATE OR REPLACE FUNCTION public.rpc_set_my_public_key(p_app_uuid text, p_device_token text, p_public_key text)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public' AS $fn$
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  UPDATE public.koda_users SET public_key = p_public_key WHERE app_uuid = p_app_uuid;
END $fn$;

-- hands out the peer's public key for an existing ticket:
-- the admin gets the reporter's key, the reporter gets THE admin key.
-- that admin key lives in app_settings ('personal_chat_admin_key', written by the
-- admin app that owns the matching private key) - exactly one canonical key, so
-- devices can never encrypt against a stale admin key again.
CREATE OR REPLACE FUNCTION public.rpc_get_chat_peer_key(p_ticket_id uuid, p_reporter_uuid text, p_device_token text)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public' AS $fn$
DECLARE v_is_admin boolean; v_key text;
BEGIN
  v_is_admin := public.fn_is_praetor_admin(p_reporter_uuid, p_device_token);
  IF NOT v_is_admin AND NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.support_tickets t WHERE t.id = p_ticket_id) THEN
    RAISE EXCEPTION 'Ticket not found';
  END IF;
  IF v_is_admin THEN
    SELECT u.public_key INTO v_key
      FROM public.support_tickets t JOIN public.koda_users u ON u.app_uuid = t.reporter_uuid
     WHERE t.id = p_ticket_id;
  ELSE
    SELECT (s.value ->> 'pub') INTO v_key
      FROM public.app_settings s
     WHERE s.key = 'personal_chat_admin_key';
  END IF;
  RETURN v_key;
END $fn$;

REVOKE ALL ON FUNCTION public.rpc_set_my_public_key(text, text, text) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_set_my_public_key(text, text, text) TO anon, authenticated;
REVOKE ALL ON FUNCTION public.rpc_get_chat_peer_key(uuid, text, text) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_get_chat_peer_key(uuid, text, text) TO anon, authenticated;

-- clear the stale admin keys: no device reads them anymore, and those mixed
-- keys were why personal messages reached the admin as scrambled text.
UPDATE public.koda_users u SET public_key = NULL
 WHERE u.public_key IS NOT NULL
   AND EXISTS (SELECT 1 FROM public.praetor_admins pa WHERE pa.app_uuid = u.app_uuid);

NOTIFY pgrst, 'reload schema';
