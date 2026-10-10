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
-- VPS monitoring: load, RAM, connections and tunnels of the tunnel server.
--
-- a small reporter on the vps writes one row every 60 seconds (service role key),
-- the app reads the newest one via rpc_get_vps_stats() and shows a warning when the box
-- is overloaded. old rows are cleaned up by the reporter itself.
--
--   INSERT INTO vps_stats (host, cpu_cores, load1, ram_total_mb, ram_used_mb, players_connected, ...)
--   VALUES (...);

CREATE TABLE IF NOT EXISTS public.vps_stats (
    id                 bigserial PRIMARY KEY,
    created_at         timestamptz NOT NULL DEFAULT now(),
    host               text NOT NULL DEFAULT 'vps',
    cpu_cores          integer,
    load1              real,
    load5              real,
    load15             real,
    ram_total_mb       integer,
    ram_used_mb        integer,
    ram_available_mb   integer,
    swap_used_mb       integer,
    disk_free_gb       real,
    players_connected  integer,   -- sockets open on the game port ranges
    tunnel_clients     integer,   -- frpc clients hooked to bindPort
    open_tunnels       integer,   -- remote ports bound, one per proxy
    net_rx_mb          bigint,
    net_tx_mb          bigint,
    uptime_seconds     bigint
);

CREATE INDEX IF NOT EXISTS vps_stats_created_idx ON public.vps_stats (created_at DESC);

ALTER TABLE public.vps_stats ENABLE ROW LEVEL SECURITY;
-- no policies for anon/authenticated on purpose: writing and reading both go through the functions below.

CREATE OR REPLACE FUNCTION public.rpc_get_vps_stats()
RETURNS TABLE(measured_at timestamptz, host text, cpu_cores integer, load1 real, load5 real, load15 real,
              ram_total_mb integer, ram_used_mb integer, ram_available_mb integer, swap_used_mb integer,
              disk_free_gb real, players_connected integer, tunnel_clients integer, open_tunnels integer,
              net_rx_mb bigint, net_tx_mb bigint, uptime_seconds bigint)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
    SELECT s.created_at, s.host::text, s.cpu_cores, s.load1, s.load5, s.load15,
           s.ram_total_mb, s.ram_used_mb, s.ram_available_mb, s.swap_used_mb,
           s.disk_free_gb, s.players_connected, s.tunnel_clients, s.open_tunnels,
           s.net_rx_mb, s.net_tx_mb, s.uptime_seconds
      FROM public.vps_stats s
     ORDER BY s.created_at DESC
     LIMIT 1;
$fn$;

-- the recent measurements as a series (sitting here for charts later).
CREATE OR REPLACE FUNCTION public.rpc_get_vps_history(p_minutes integer DEFAULT 60)
RETURNS TABLE(measured_at timestamptz, load1 real, ram_used_mb integer, players_connected integer, open_tunnels integer)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
    SELECT s.created_at, s.load1, s.ram_used_mb, s.players_connected, s.open_tunnels
      FROM public.vps_stats s
     WHERE s.created_at > now() - make_interval(mins => GREATEST(1, LEAST(COALESCE(p_minutes, 60), 1440)))
     ORDER BY s.created_at ASC;
$fn$;

REVOKE ALL ON FUNCTION public.rpc_get_vps_stats() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.rpc_get_vps_history(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.rpc_get_vps_stats() TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_get_vps_history(integer) TO anon, authenticated;
GRANT INSERT, SELECT, DELETE ON public.vps_stats TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.vps_stats_id_seq TO service_role;

NOTIFY pgrst, 'reload schema';
