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
--
-- data requests + in-app notifications, 2026-11-08.
--
-- what this adds:
--   1. app_notifications / app_notification_reads / app_notification_mutes: the inbox every
--      device polls, per device read + delete state, per kind mute and e-mail mirror
--   2. data_requests: the DSGVO Art. 15 export and Art. 17 delete request, both need a
--      decision from the admin side before anything happens
--   3. koda_retained_user_data: the only data that survives a full delete, per retention
--      class (billing 10 years, contract 6 years, security 180 days) and with a hard
--      retained_until so the daily job can remove it
--   4. rpc_delete_my_account: account delete that only removes the auth binding, servers
--      and their files stay on the device
--   5. the ticket whitelist learns QUESTION and IDEA
--
-- service role only: fn_snapshot_and_purge_user, fn_purge_expired_retained,
-- rpc_admin_decide_data_request. everything the app calls checks the device token.

-- ---------------------------------------------------------------------------
-- 1) tables
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.app_notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamptz NOT NULL DEFAULT now(),
    kind text NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    -- deep link for the app: ticket:<id>, server:<id>, release, data_request:<id>, url:https://...
    action text NULL,
    -- null target = every device
    target_app_uuid text NULL,
    target_auth_id uuid NULL,
    severity text NOT NULL DEFAULT 'NORMAL',
    expires_at timestamptz NULL,
    email_mirror boolean NOT NULL DEFAULT false,
    archived boolean NOT NULL DEFAULT false,
    created_by text NOT NULL DEFAULT 'admin',
    -- system notifications carry a key + args so the app can localise them
    i18n_key text NULL,
    i18n_args jsonb NULL
);

CREATE INDEX IF NOT EXISTS idx_app_notifications_created
    ON public.app_notifications (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_notifications_target
    ON public.app_notifications (target_app_uuid, created_at DESC);

CREATE TABLE IF NOT EXISTS public.app_notification_reads (
    notification_id uuid NOT NULL REFERENCES public.app_notifications (id) ON DELETE CASCADE,
    app_uuid text NOT NULL,
    read_at timestamptz NULL,
    deleted_at timestamptz NULL,
    PRIMARY KEY (notification_id, app_uuid)
);

CREATE INDEX IF NOT EXISTS idx_app_notification_reads_app
    ON public.app_notification_reads (app_uuid);

CREATE TABLE IF NOT EXISTS public.app_notification_mutes (
    app_uuid text NOT NULL,
    kind text NOT NULL,
    muted boolean NOT NULL DEFAULT true,
    email_enabled boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (app_uuid, kind)
);

CREATE TABLE IF NOT EXISTS public.data_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    app_uuid text NOT NULL,
    auth_id uuid NULL,
    kind text NOT NULL CHECK (kind IN ('EXPORT', 'DELETE')),
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'FULFILLED', 'CANCELLED')),
    note text NULL,
    admin_note text NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz NULL,
    decided_by text NULL,
    fulfilled_at timestamptz NULL,
    export_path text NULL,
    export_expires_at timestamptz NULL,
    email_sent_at timestamptz NULL
);

CREATE INDEX IF NOT EXISTS idx_data_requests_status
    ON public.data_requests (status, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_data_requests_app
    ON public.data_requests (app_uuid, requested_at DESC);

-- the only place personal data survives a full delete. app_uuid is stored as a hash,
-- never in clear, and retained_until is the hard stop for the daily cleanup.
CREATE TABLE IF NOT EXISTS public.koda_retained_user_data (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    original_user_id uuid NULL,
    app_uuid_hash text NOT NULL,
    retention_class text NOT NULL,
    data jsonb NOT NULL,
    retained_until date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_retained_until
    ON public.koda_retained_user_data (retained_until);

ALTER TABLE public.app_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_notification_reads ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_notification_mutes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.data_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.koda_retained_user_data ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.app_notifications FROM anon, authenticated;
REVOKE ALL ON public.app_notification_reads FROM anon, authenticated;
REVOKE ALL ON public.app_notification_mutes FROM anon, authenticated;
REVOKE ALL ON public.data_requests FROM anon, authenticated;
REVOKE ALL ON public.koda_retained_user_data FROM anon, authenticated;

GRANT ALL ON public.app_notifications TO service_role;
GRANT ALL ON public.app_notification_reads TO service_role;
GRANT ALL ON public.app_notification_mutes TO service_role;
GRANT ALL ON public.data_requests TO service_role;
GRANT ALL ON public.koda_retained_user_data TO service_role;

-- ---------------------------------------------------------------------------
-- 2) defaults in app_settings
-- ---------------------------------------------------------------------------
INSERT INTO public.app_settings (key, value)
VALUES ('data_retention', '{"BILLING": 3650, "CONTRACT": 2190, "SECURITY": 180}'::jsonb)
ON CONFLICT (key) DO NOTHING;

INSERT INTO public.app_settings (key, value)
VALUES ('notification_email_from', '"no-reply@kodaserv.eu"'::jsonb)
ON CONFLICT (key) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3) notifications for the app
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_get_my_notifications(
    p_app_uuid text,
    p_device_token text,
    p_limit integer DEFAULT 60,
    p_kind text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_result jsonb;
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;

    SELECT COALESCE(jsonb_agg(row_to_json(q)::jsonb ORDER BY q.sort_at DESC), '[]'::jsonb)
      INTO v_result
      FROM (
        SELECT n.id,
               n.created_at,
               n.created_at AS sort_at,
               n.kind,
               n.title,
               n.body,
               n.action,
               n.severity,
               n.expires_at,
               n.i18n_key,
               n.i18n_args,
               (r.read_at IS NOT NULL) AS is_read,
               (n.target_app_uuid IS NOT NULL) AS is_personal
          FROM public.app_notifications n
          LEFT JOIN public.app_notification_reads r
                 ON r.notification_id = n.id AND r.app_uuid = p_app_uuid
         WHERE n.archived = false
           AND (n.expires_at IS NULL OR n.expires_at > now())
           AND (n.target_app_uuid IS NULL OR n.target_app_uuid = p_app_uuid)
           AND (p_kind IS NULL OR n.kind = p_kind)
           AND (r.deleted_at IS NULL)
         ORDER BY n.created_at DESC
         LIMIT GREATEST(1, LEAST(COALESCE(p_limit, 60), 200))
      ) q;

    RETURN v_result;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_get_my_notifications(text, text, integer, text) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.rpc_mark_notification_read(
    p_app_uuid text, p_device_token text, p_id uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.app_notifications n
        WHERE n.id = p_id
          AND (n.target_app_uuid IS NULL OR n.target_app_uuid = p_app_uuid)
    ) THEN
        RAISE EXCEPTION 'Unknown notification';
    END IF;

    INSERT INTO public.app_notification_reads (notification_id, app_uuid, read_at)
    VALUES (p_id, p_app_uuid, now())
    ON CONFLICT (notification_id, app_uuid)
    DO UPDATE SET read_at = COALESCE(public.app_notification_reads.read_at, now());
    RETURN true;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_mark_notification_read(text, text, uuid) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.rpc_mark_all_notifications_read(p_app_uuid text, p_device_token text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_count integer;
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;

    WITH visible AS (
        SELECT n.id FROM public.app_notifications n
         WHERE n.archived = false
           AND (n.expires_at IS NULL OR n.expires_at > now())
           AND (n.target_app_uuid IS NULL OR n.target_app_uuid = p_app_uuid)
    ), upserted AS (
        INSERT INTO public.app_notification_reads (notification_id, app_uuid, read_at)
        SELECT v.id, p_app_uuid, now() FROM visible v
        ON CONFLICT (notification_id, app_uuid)
        DO UPDATE SET read_at = COALESCE(public.app_notification_reads.read_at, now())
        RETURNING 1
    )
    SELECT count(*) INTO v_count FROM upserted;

    RETURN v_count;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_mark_all_notifications_read(text, text) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.rpc_delete_notification(
    p_app_uuid text, p_device_token text, p_id uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.app_notifications n
        WHERE n.id = p_id
          AND (n.target_app_uuid IS NULL OR n.target_app_uuid = p_app_uuid)
    ) THEN
        RAISE EXCEPTION 'Unknown notification';
    END IF;

    INSERT INTO public.app_notification_reads (notification_id, app_uuid, read_at, deleted_at)
    VALUES (p_id, p_app_uuid, now(), now())
    ON CONFLICT (notification_id, app_uuid)
    DO UPDATE SET deleted_at = now(),
                  read_at = COALESCE(public.app_notification_reads.read_at, now());
    RETURN true;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_delete_notification(text, text, uuid) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.rpc_clear_notifications(p_app_uuid text, p_device_token text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_count integer;
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;

    WITH visible AS (
        SELECT n.id FROM public.app_notifications n
         WHERE n.archived = false
           AND (n.target_app_uuid IS NULL OR n.target_app_uuid = p_app_uuid)
    ), upserted AS (
        INSERT INTO public.app_notification_reads (notification_id, app_uuid, read_at, deleted_at)
        SELECT v.id, p_app_uuid, now(), now() FROM visible v
        ON CONFLICT (notification_id, app_uuid)
        DO UPDATE SET deleted_at = now(),
                      read_at = COALESCE(public.app_notification_reads.read_at, now())
        RETURNING 1
    )
    SELECT count(*) INTO v_count FROM upserted;

    RETURN v_count;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_clear_notifications(text, text) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.rpc_set_notification_mute(
    p_app_uuid text, p_device_token text, p_kind text,
    p_muted boolean, p_email boolean DEFAULT false)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;
    IF p_kind IS NULL OR p_kind NOT IN ('SYSTEM', 'SUPPORT', 'RELEASE', 'SECURITY', 'ACCOUNT', 'DATA') THEN
        RAISE EXCEPTION 'Unknown notification kind';
    END IF;

    INSERT INTO public.app_notification_mutes (app_uuid, kind, muted, email_enabled, updated_at)
    VALUES (p_app_uuid, p_kind, COALESCE(p_muted, true), COALESCE(p_email, false), now())
    ON CONFLICT (app_uuid, kind)
    DO UPDATE SET muted = COALESCE(p_muted, true),
                  email_enabled = COALESCE(p_email, false),
                  updated_at = now();
    RETURN true;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_set_notification_mute(text, text, text, boolean, boolean) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.rpc_get_notification_mutes(p_app_uuid text, p_device_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;

    RETURN COALESCE(
        (SELECT jsonb_agg(jsonb_build_object(
                    'kind', m.kind,
                    'muted', m.muted,
                    'email_enabled', m.email_enabled)
                ORDER BY m.kind)
           FROM public.app_notification_mutes m
          WHERE m.app_uuid = p_app_uuid),
        '[]'::jsonb);
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_get_notification_mutes(text, text) TO anon, authenticated;

-- ---------------------------------------------------------------------------
-- 4) data requests (DSGVO Art. 15 and Art. 17)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_create_data_request(
    p_app_uuid text, p_device_token text, p_kind text, p_note text DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_id uuid; v_auth uuid; v_retry integer;
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;
    IF p_kind IS NULL OR p_kind NOT IN ('EXPORT', 'DELETE') THEN
        RAISE EXCEPTION 'Unknown request kind';
    END IF;
    IF p_note IS NOT NULL AND length(p_note) > 1000 THEN
        RAISE EXCEPTION 'Note is too long';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.data_requests r
        WHERE r.app_uuid = p_app_uuid
          AND r.kind = p_kind
          AND r.status IN ('PENDING', 'APPROVED')
    ) THEN
        RAISE EXCEPTION 'Request already open';
    END IF;

    -- three requests per device and day are plenty, also stops accidental double taps
    v_retry := public.fn_api_rate_take('data_request:' || p_app_uuid, 3, 86400);
    IF v_retry > 0 THEN
        RAISE EXCEPTION 'Slow down';
    END IF;

    SELECT u.auth_id INTO v_auth FROM public.koda_users u
     WHERE u.app_uuid = p_app_uuid AND u.auth_id IS NOT NULL
     LIMIT 1;

    INSERT INTO public.data_requests (app_uuid, auth_id, kind, note, status)
    VALUES (p_app_uuid, v_auth, p_kind, NULLIF(TRIM(p_note), ''), 'PENDING')
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_create_data_request(text, text, text, text) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.rpc_get_my_data_requests(p_app_uuid text, p_device_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;

    RETURN COALESCE(
        (SELECT jsonb_agg(jsonb_build_object(
                    'id', r.id,
                    'kind', r.kind,
                    'status', r.status,
                    'note', r.note,
                    'admin_note', r.admin_note,
                    'requested_at', r.requested_at,
                    'decided_at', r.decided_at,
                    'fulfilled_at', r.fulfilled_at,
                    'export_expires_at', r.export_expires_at,
                    'has_export', r.export_path IS NOT NULL) ORDER BY r.requested_at DESC)
           FROM public.data_requests r
          WHERE r.app_uuid = p_app_uuid
          LIMIT 20),
        '[]'::jsonb);
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_get_my_data_requests(text, text) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.rpc_cancel_data_request(
    p_app_uuid text, p_device_token text, p_id uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;

    UPDATE public.data_requests
       SET status = 'CANCELLED'
     WHERE id = p_id
       AND app_uuid = p_app_uuid
       AND status = 'PENDING';
    RETURN FOUND;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_cancel_data_request(text, text, uuid) TO anon, authenticated;

-- account delete means: the login goes, the device and its servers stay. the full
-- data delete is the separate DELETE request below.
CREATE OR REPLACE FUNCTION public.rpc_delete_my_account(p_app_uuid text, p_device_token text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
BEGIN
    IF NOT public.fn_verify_device_token(p_app_uuid, p_device_token) THEN
        RAISE EXCEPTION 'Not authorized';
    END IF;

    UPDATE public.koda_users
       SET auth_id = NULL,
           mc_username = NULL,
           mc_uuid = NULL,
           pending_mc_username = NULL,
           nickname = NULL,
           permissions = NULL,
           public_key = NULL,
           two_fa_enabled = false,
           two_fa_password = NULL
     WHERE app_uuid = p_app_uuid;

    RETURN true;
END;
$fn$;

GRANT EXECUTE ON FUNCTION public.rpc_delete_my_account(text, text) TO anon, authenticated;

-- ---------------------------------------------------------------------------
-- 5) admin side of the data requests (service role only)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_admin_decide_data_request(
    p_request_id uuid, p_status text, p_admin_note text DEFAULT NULL)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_req public.data_requests%ROWTYPE;
BEGIN
    IF p_status IS NULL OR p_status NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'Unknown decision';
    END IF;

    UPDATE public.data_requests
       SET status = p_status,
           admin_note = NULLIF(TRIM(p_admin_note), ''),
           decided_at = now(),
           decided_by = 'admin'
     WHERE id = p_request_id
       AND status = 'PENDING'
     RETURNING * INTO v_req;

    IF v_req.id IS NULL THEN
        RAISE EXCEPTION 'Request not found or already decided';
    END IF;

    INSERT INTO public.app_notifications
        (kind, title, body, action, target_app_uuid, severity, created_by, i18n_key, i18n_args)
    VALUES
        ('DATA',
         CASE WHEN p_status = 'APPROVED' THEN 'Data request approved' ELSE 'Data request rejected' END,
         CASE WHEN p_status = 'APPROVED'
              THEN 'Your request was approved, we are on it.'
              ELSE COALESCE(NULLIF(TRIM(p_admin_note), ''), 'Your request was rejected.') END,
         'data_request:' || p_request_id,
         v_req.app_uuid,
         'IMPORTANT',
         'admin',
         CASE WHEN p_status = 'APPROVED' THEN 'data_request_approved' ELSE 'data_request_rejected' END,
         jsonb_build_object('kind', v_req.kind));

    RETURN true;
END;
$fn$;

REVOKE ALL ON FUNCTION public.rpc_admin_decide_data_request(uuid, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_admin_decide_data_request(uuid, text, text) TO service_role;

-- ---------------------------------------------------------------------------
-- 6) retention + purge
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fn_retention_days(p_class text)
RETURNS integer LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
  SELECT COALESCE(
    (SELECT (value ->> p_class)::int FROM public.app_settings WHERE key = 'data_retention'),
    CASE p_class WHEN 'BILLING' THEN 3650 WHEN 'CONTRACT' THEN 2190 ELSE 180 END);
$fn$;

REVOKE ALL ON FUNCTION public.fn_retention_days(text) FROM PUBLIC, anon, authenticated;

-- moves what the law requires into the retention table and removes the rest. the
-- snapshot never contains tokens, passwords or keys, only what billing/contract needs.
CREATE OR REPLACE FUNCTION public.fn_snapshot_and_purge_user(p_app_uuid text, p_original_user_id uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE
    v_auth uuid;
    v_hash text;
    v_servers jsonb;
    v_rows integer := 0;
    v_kept integer := 0;
    v_contract_days integer;
    v_security_days integer;
BEGIN
    SELECT u.auth_id INTO v_auth FROM public.koda_users u
     WHERE u.app_uuid = p_app_uuid AND u.auth_id IS NOT NULL
     LIMIT 1;

    v_hash := encode(sha256(convert_to(p_app_uuid, 'UTF8')), 'hex');
    v_contract_days := public.fn_retention_days('CONTRACT');
    v_security_days := public.fn_retention_days('SECURITY');

    SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'host', s.host,
                'created_at', s.created_at,
                'last_online', s.last_online,
                'ram_mb', s.ram_mb,
                'server_version', s.server_version)), '[]'::jsonb)
      INTO v_servers
      FROM public.koda_servers s
     WHERE s.owner_app_uuid = p_app_uuid
        OR (v_auth IS NOT NULL AND s.owner_app_uuid IN (
              SELECT u.app_uuid FROM public.koda_users u WHERE u.auth_id = v_auth));

    INSERT INTO public.koda_retained_user_data
        (original_user_id, app_uuid_hash, retention_class, data, retained_until)
    SELECT p_original_user_id,
           v_hash,
           'CONTRACT',
           jsonb_build_object(
               'app_uuid_hash', v_hash,
               'auth_id_present', v_auth IS NOT NULL,
               'accounts', (
                   SELECT COALESCE(jsonb_agg(jsonb_build_object(
                              'code', u.code,
                              'mc_username', u.mc_username,
                              'created_at', u.created_at,
                              'is_banned', u.is_banned)), '[]'::jsonb)
                     FROM public.koda_users u
                    WHERE u.app_uuid = p_app_uuid
                       OR (v_auth IS NOT NULL AND u.auth_id = v_auth)),
               'servers', v_servers),
           current_date + v_contract_days;

    INSERT INTO public.koda_retained_user_data
        (original_user_id, app_uuid_hash, retention_class, data, retained_until)
    SELECT p_original_user_id,
           v_hash,
           'SECURITY',
           jsonb_build_object(
               'app_uuid_hash', v_hash,
               'tickets', (
                   SELECT COALESCE(jsonb_agg(jsonb_build_object(
                              'title', t.title,
                              'type', t.ticket_type,
                              'status', t.status,
                              'created_at', t.created_at)), '[]'::jsonb)
                     FROM public.support_tickets t
                    WHERE t.reporter_uuid = p_app_uuid)),
           current_date + v_security_days;

    -- tickets stay readable for support history, the reporter is anonymised
    UPDATE public.support_tickets SET reporter_uuid = 'deleted'
     WHERE reporter_uuid = p_app_uuid;
    UPDATE public.support_ticket_messages SET sender_uuid = 'deleted'
     WHERE sender_uuid = p_app_uuid;

    UPDATE public.koda_servers
       SET server_version = 'DELETED',
           host = 'deleted_' || host
     WHERE (owner_app_uuid = p_app_uuid
            OR (v_auth IS NOT NULL AND owner_app_uuid IN (
                  SELECT u.app_uuid FROM public.koda_users u WHERE u.auth_id = v_auth)))
       AND host NOT LIKE 'deleted_%';

    GET DIAGNOSTICS v_rows = ROW_COUNT;

    DELETE FROM public.koda_users u
     WHERE u.app_uuid = p_app_uuid
        OR (v_auth IS NOT NULL AND u.auth_id = v_auth);
    GET DIAGNOSTICS v_kept = ROW_COUNT;

    DELETE FROM public.app_notification_reads r WHERE r.app_uuid = p_app_uuid;
    DELETE FROM public.app_notification_mutes m WHERE m.app_uuid = p_app_uuid;

    RETURN jsonb_build_object(
        'app_uuid_hash', v_hash,
        'users_removed', v_kept,
        'servers_tombstoned', v_rows,
        'contract_until', current_date + v_contract_days,
        'security_until', current_date + v_security_days);
END;
$fn$;

REVOKE ALL ON FUNCTION public.fn_snapshot_and_purge_user(text, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.fn_snapshot_and_purge_user(text, uuid) TO service_role;

CREATE OR REPLACE FUNCTION public.fn_purge_expired_retained()
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_count integer;
BEGIN
    DELETE FROM public.koda_retained_user_data WHERE retained_until < current_date;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$fn$;

REVOKE ALL ON FUNCTION public.fn_purge_expired_retained() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.fn_purge_expired_retained() TO service_role;

-- daily cleanup, only when pg_cron is installed
DO $do$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        IF EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'koda-retention-purge') THEN
            PERFORM cron.unschedule('koda-retention-purge');
        END IF;
        PERFORM cron.schedule('koda-retention-purge', '15 3 * * *',
                              'SELECT public.fn_purge_expired_retained();');
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- pg_cron missing or not allowed in this project, the edge function can call it too
    NULL;
END;
$do$;

-- ---------------------------------------------------------------------------
-- 7) ticket types: QUESTION and IDEA join the whitelist
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_create_ticket(p_reporter_uuid text, p_device_token text, p_ticket_type text, p_reference_id text, p_title text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE new_ticket_id uuid; v_open integer;
BEGIN
  IF NOT public.fn_verify_device_token(p_reporter_uuid, p_device_token)
     AND NOT public.fn_is_praetor_admin(p_reporter_uuid, p_device_token) THEN
    RAISE EXCEPTION 'Not authorized';
  END IF;

  IF p_ticket_type IS NULL OR p_ticket_type NOT IN
       ('BUG', 'BUG_REPORT', 'APP_BUG', 'SERVER_REPORT', 'SUPPORT', 'PERSONAL', 'QUESTION', 'IDEA') THEN
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

GRANT EXECUTE ON FUNCTION public.rpc_create_ticket(text, text, text, text, text) TO anon, authenticated;

NOTIFY pgrst, 'reload schema';
