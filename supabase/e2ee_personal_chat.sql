-- End-to-end encryption for the personal (mental support) chat.
-- Each device publishes an ECDH public key; messages are encrypted client side,
-- the server only stores ciphertext. These helpers exchange the public keys.

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
-- the admin gets the reporter's key, the reporter gets the admin's key.
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
    SELECT u.public_key INTO v_key
      FROM public.praetor_admins pa JOIN public.koda_users u ON u.app_uuid = pa.app_uuid
     WHERE u.public_key IS NOT NULL
     ORDER BY pa.app_uuid LIMIT 1;
  END IF;
  RETURN v_key;
END $fn$;

REVOKE ALL ON FUNCTION public.rpc_set_my_public_key(text, text, text) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_set_my_public_key(text, text, text) TO anon, authenticated;
REVOKE ALL ON FUNCTION public.rpc_get_chat_peer_key(uuid, text, text) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_get_chat_peer_key(uuid, text, text) TO anon, authenticated;

NOTIFY pgrst, 'reload schema';
