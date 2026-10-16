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
-- API RATE LIMITS 2026-10-15
-- ============================================================================
-- the sliding windows behind every limit on api.kodanetwork.eu. in the database
-- on purpose: edge workers are several warm instances, an in-memory counter per
-- instance would multiply every limit by the number of instances. the api calls
-- fn_api_rate_take() once per request (per ip) and once per account on the jwt
-- routes; the sharper per-feature limits (ticket messages, uploads) use the same
-- function with their own keys.
--
--   ip:<addr>      120/min reads, 30/min writes
--   user:<auth_id>  90/min
--   msg:<auth_id>   20/min (ticket replies)
--   asset:<auth_id> 20/min (icon/screenshot uploads)

CREATE TABLE IF NOT EXISTS public.api_rate_limits (
    key          text PRIMARY KEY,
    hits         integer NOT NULL DEFAULT 0,
    window_start timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.api_rate_limits ENABLE ROW LEVEL SECURITY;
-- no policies: only the api function (service role) touches this table.

REVOKE ALL ON public.api_rate_limits FROM anon, authenticated;
GRANT ALL ON public.api_rate_limits TO service_role;

-- returns 0 when the call is allowed, otherwise the seconds until retry.
-- callers send the value back as Retry-After.
CREATE OR REPLACE FUNCTION public.fn_api_rate_take(p_key text, p_max integer, p_window_seconds integer DEFAULT 60)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE v_hits integer; v_start timestamptz; v_retry integer;
BEGIN
    INSERT INTO public.api_rate_limits (key, hits, window_start)
    VALUES (p_key, 0, now())
    ON CONFLICT (key) DO NOTHING;

    -- the row lock serialises concurrent requests of the same key
    SELECT hits, window_start INTO v_hits, v_start
      FROM public.api_rate_limits WHERE key = p_key FOR UPDATE;

    IF v_start < now() - make_interval(secs => p_window_seconds) THEN
        UPDATE public.api_rate_limits SET hits = 1, window_start = now() WHERE key = p_key;
        RETURN 0;
    END IF;

    IF v_hits >= p_max THEN
        v_retry := GREATEST(1, CEIL(EXTRACT(EPOCH FROM (v_start + make_interval(secs => p_window_seconds) - now())))::int);
        RETURN v_retry;
    END IF;

    UPDATE public.api_rate_limits SET hits = v_hits + 1 WHERE key = p_key;

    -- occasional cleanup of stale buckets (window older than 15 minutes)
    IF random() < 0.02 THEN
        DELETE FROM public.api_rate_limits WHERE window_start < now() - interval '15 minutes';
    END IF;
    RETURN 0;
END;
$fn$;

REVOKE ALL ON FUNCTION public.fn_api_rate_take(text, integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fn_api_rate_take(text, integer, integer) TO service_role;

NOTIFY pgrst, 'reload schema';
