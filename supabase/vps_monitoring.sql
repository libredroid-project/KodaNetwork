-- VPS-Monitoring: Last, RAM, Verbindungen und Tunnel des Tunnel-Servers.
--
-- Ein kleiner Reporter auf dem VPS schreibt alle 60 Sekunden eine Zeile (Service-Role-Key),
-- die App liest die jeweils neueste ueber rpc_get_vps_stats() und zeigt bei Ueberlastung einen
-- Hinweis an. Alte Zeilen werden vom Reporter selbst weggeraeumt.
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
    players_connected  integer,   -- established sockets on the game port ranges
    tunnel_clients     integer,   -- frpc clients connected to bindPort
    open_tunnels       integer,   -- bound remote ports (one per proxy)
    net_rx_mb          bigint,
    net_tx_mb          bigint,
    uptime_seconds     bigint
);

CREATE INDEX IF NOT EXISTS vps_stats_created_idx ON public.vps_stats (created_at DESC);

ALTER TABLE public.vps_stats ENABLE ROW LEVEL SECURITY;
-- Kein Policy fuer anon/authenticated: geschrieben und gelesen wird ueber die Funktionen unten.

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

-- Die letzten Messwerte fuer einen Verlauf (Reserve fuer spaetere Diagramme).
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
