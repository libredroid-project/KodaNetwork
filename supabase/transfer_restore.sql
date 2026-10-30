-- Copyright (c) 2026 KodaHosting
--
-- This file is part of KodaHosting (KodaNetwork).
-- KodaHosting is free software: you can redistribute it and/or modify it under the
-- terms of the GNU General Public License as published by the Free Software
-- Foundation, version 3 of the License.
--
-- SPDX-FileCopyrightText: 2026 KodaHosting
-- SPDX-License-Identifier: GPL-3.0-only
-- ============================================================================
-- TRANSFER-REPARATUR (2026-10-17): beim Geraete-Transfer wurde die Cloud-Zeile
-- des Servers nach der Uebergabe noch als `deleted_<host>_<uuid>` umbenannt
-- (der alte Cleanup-Pfad schrieb nach dem Adoptieren einen Tombstone). Ergebnis:
-- der Server laeuft auf dem neuen Geraet, hat aber keinen Status, keinen
-- Domain-Link und kein KodaDash-Panel mehr, weil die Zeile "geloescht" ist.
-- Diese Funktion holt genau so einen Tombstone zurueck, aber nur fuer ein
-- Geraet desselben Kontos und nur wenn es keine lebende Zeile fuer den Host gibt.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.rpc_restore_server(p_app_uuid text, p_device_token text, p_host text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE v_auth uuid; v_id uuid;
BEGIN
  IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;
  SELECT auth_id INTO v_auth FROM public.koda_users WHERE app_uuid = p_app_uuid LIMIT 1;
  IF v_auth IS NULL THEN
    RETURN false;
  END IF;
  -- never touch a host that already has a living row
  IF EXISTS (
    SELECT 1 FROM public.koda_servers
    WHERE host = p_host AND COALESCE(server_version, '') <> 'DELETED'
  ) THEN
    RETURN false;
  END IF;
  SELECT s.id INTO v_id
  FROM public.koda_servers s
  WHERE s.host LIKE 'deleted\_' || p_host || '\_%' ESCAPE '\'
    AND EXISTS (
      SELECT 1 FROM public.koda_users u
      WHERE u.app_uuid = s.owner_app_uuid AND u.auth_id = v_auth
    )
  ORDER BY s.created_at DESC
  LIMIT 1;
  IF v_id IS NULL THEN
    RETURN false;
  END IF;
  UPDATE public.koda_servers
     SET host = p_host,
         owner_app_uuid = p_app_uuid,
         server_version = '',
         command_at = NULL
   WHERE id = v_id;
  RETURN true;
END;
$function$;

REVOKE ALL ON FUNCTION public.rpc_restore_server(text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.rpc_restore_server(text, text, text) TO anon, authenticated;

-- adoptieren holt jetzt auch einen Tombstone zurueck, der erst NACH einer frueheren
-- Uebergabe geschrieben wurde (genau der Fall aus dem Video-Dreh).
CREATE OR REPLACE FUNCTION public.rpc_adopt_hosts(p_app_uuid text, p_device_token text, p_hosts text[])
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE v_auth uuid; v_count integer; v_host text; v_id uuid;
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

  FOREACH v_host IN ARRAY p_hosts LOOP
    IF NOT EXISTS (
      SELECT 1 FROM public.koda_servers
      WHERE host = v_host AND COALESCE(server_version, '') <> 'DELETED'
    ) THEN
      SELECT s.id INTO v_id
      FROM public.koda_servers s
      WHERE s.host LIKE 'deleted\_' || v_host || '\_%' ESCAPE '\'
        AND EXISTS (
          SELECT 1 FROM public.koda_users u
          WHERE u.app_uuid = s.owner_app_uuid AND u.auth_id = v_auth
        )
      ORDER BY s.created_at DESC
      LIMIT 1;
      IF v_id IS NOT NULL THEN
        UPDATE public.koda_servers
           SET host = v_host,
               owner_app_uuid = p_app_uuid,
               server_version = '',
               command_at = NULL
         WHERE id = v_id;
        v_count := v_count + 1;
      END IF;
    END IF;
  END LOOP;
  RETURN v_count;
END;
$function$;
