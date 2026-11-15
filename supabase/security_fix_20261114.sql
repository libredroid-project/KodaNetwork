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
-- 2026-11-14: fixes from the external security assessment.
-- Applied live; keep this file as the source of the current policies.

-- ---------------------------------------------------------------------------
-- M1: service_checks is internal monitoring data (operator host name, loopback
-- ports, latency history). No client reads it, so remove it from the API roles.
-- The monitor writes with the service role, which keeps its grants.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS service_checks_public_read ON public.service_checks;
REVOKE ALL ON TABLE public.service_checks FROM anon, authenticated;

-- The history table and the effective view were missed in the first report; they
-- expose the VPS address, disk usage, backup snapshot names and loopback ports.
-- Same treatment for everything in that monitoring family.
DROP POLICY IF EXISTS service_check_history_public_read ON public.service_check_history;
REVOKE ALL ON TABLE public.service_check_history FROM anon, authenticated;
REVOKE ALL ON TABLE public.v_service_checks_effective FROM anon, authenticated;

-- ---------------------------------------------------------------------------
-- M2: v_servers_public is readable with the anon key. Deleted servers keep a
-- tombstone row whose host is "deleted_<name>_<auth-uuid>", which exposed 218
-- auth UUIDs. Hibernated rows stay visible because the app checks them to decide
-- whether an offline server still needs its world zip.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW public.v_servers_public AS
SELECT host, server_version, base_domain, online_players, max_players, motd,
       gamemode, difficulty, pvp, whitelist, is_banned, ban_reason, last_online,
       ram_mb, domain_switched_from
FROM public.koda_servers
WHERE host NOT LIKE 'deleted%'
  AND server_version <> 'DELETED';

-- PostgREST only needs SELECT. The view is read-only for clients; revoking the
-- default DML grants keeps the surface small even if a policy changes later.
REVOKE ALL ON TABLE public.v_servers_public FROM anon, authenticated;
GRANT SELECT ON public.v_servers_public TO anon, authenticated;

-- ---------------------------------------------------------------------------
-- L2: extension_assets and avatars are public buckets, so object URLs keep
-- working without a storage.objects SELECT policy. The policy only powered the
-- list endpoint, which exposed every per-user auth UUID. Remove it; the app and
-- the store use direct object URLs, and the admin client bypasses RLS.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS extension_assets_public_read ON storage.objects;
DROP POLICY IF EXISTS avatars_public_read ON storage.objects;
