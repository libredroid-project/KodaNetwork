-- End-to-end encryption for the personal (mental support) chat.
-- The server only stores ciphertext; keys live on the devices.

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

-- Returns the peer's public key for an existing ticket:
-- the admin gets the reporter's key, the reporter gets THE admin key.
-- The admin key lives in app_settings ('personal_chat_admin_key', written by the
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

-- Drop the stale admin keys: user devices no longer read them, and old mixed
-- keys were the reason personal messages arrived scrambled at the admin.
UPDATE public.koda_users u SET public_key = NULL
 WHERE u.public_key IS NOT NULL
   AND EXISTS (SELECT 1 FROM public.praetor_admins pa WHERE pa.app_uuid = u.app_uuid);

NOTIFY pgrst, 'reload schema';
